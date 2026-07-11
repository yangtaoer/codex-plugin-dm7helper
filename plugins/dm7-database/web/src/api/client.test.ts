import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, createApiClient } from './client'

const response = (body: unknown, init: ResponseInit = {}) => new Response(
  body === undefined ? null : JSON.stringify(body),
  { headers: { 'Content-Type': 'application/json' }, ...init },
)

describe('same-origin API client', () => {
  afterEach(() => vi.restoreAllMocks())

  it('uses exact runtime request shape and same-origin credentials', async () => {
    const fetcher = vi.fn().mockResolvedValue(response({ sessionShortId: '会话-019f4a71', currentVersion: 'v001', runningCount: 2, connections: 1 }))
    const runtime = await createApiClient({ fetcher }).runtime()
    expect(fetcher).toHaveBeenCalledWith('/api/runtime', expect.objectContaining({ method: 'GET', credentials: 'same-origin' }))
    expect(runtime.currentVersion).toBe('v001')
  })

  it('encodes query values with URLSearchParams and supports caller abort', async () => {
    const fetcher = vi.fn().mockResolvedValue(response({ items: [], offset: 0, limit: 25, hasMore: false }))
    const controller = new AbortController()
    await createApiClient({ fetcher }).history({ source: 'CONSOLE', limit: 25 }, controller.signal)
    expect(fetcher.mock.calls[0][0]).toBe('/api/history?source=CONSOLE&limit=25')
    expect(fetcher.mock.calls[0][1].signal).toBeInstanceOf(AbortSignal)
  })

  it.each([401, 409, 422, 429])('maps safe %s error envelopes without retaining bodies', async (status) => {
    const fetcher = vi.fn().mockResolvedValue(response({ ok: false, code: 'SAFE_CODE', message: '安全提示', correlationId: 'corr-safe' }, { status }))
    await expect(createApiClient({ fetcher }).runtime()).rejects.toMatchObject({ status, code: 'SAFE_CODE', message: '安全提示', correlationId: 'corr-safe' })
  })

  it('distinguishes 204, downloads, malformed JSON, and aborts', async () => {
    const noContent = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    await expect(createApiClient({ fetcher: noContent }).removeConnection('safe-id')).resolves.toBeUndefined()

    const malformed = vi.fn().mockResolvedValue(new Response('{broken', { headers: { 'Content-Type': 'application/json' } }))
    await expect(createApiClient({ fetcher: malformed }).runtime()).rejects.toMatchObject({ code: 'MALFORMED_RESPONSE' })

    const aborted = vi.fn().mockRejectedValue(new DOMException('aborted', 'AbortError'))
    await expect(createApiClient({ fetcher: aborted }).runtime()).rejects.toMatchObject({ code: 'ABORTED' })

    const download = vi.fn().mockResolvedValue(new Response('文件', { headers: { 'Content-Disposition': 'attachment; filename="release.sql"' } }))
    const artifact = await createApiClient({ fetcher: download }).downloadArtifact('artifact-1')
    expect(artifact.filename).toBe('release.sql')
    expect(await artifact.blob.text()).toBe('文件')
  })

  it('maps its own request deadline separately from caller abort', async () => {
    const fetcher = vi.fn((_path: RequestInfo | URL, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new DOMException('deadline', 'TimeoutError')))
    }))
    await expect(createApiClient({ fetcher, timeoutMs: 1 }).runtime()).rejects.toMatchObject({ code: 'TIMEOUT' })
  })

  it('rejects arbitrary origins and never logs sensitive response content', async () => {
    expect(() => createApiClient({ baseUrl: 'https://example.invalid' } as never)).toThrow()
    const logger = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const fetcher = vi.fn().mockResolvedValue(new Response('password=never-log', { status: 500 }))
    await expect(createApiClient({ fetcher }).runtime()).rejects.toBeInstanceOf(ApiError)
    expect(logger).not.toHaveBeenCalled()
  })

  it('uses exact JSON operations for the remaining Task 8 endpoint surface', async () => {
    const fetcher = vi.fn().mockImplementation(() => Promise.resolve(response({ ok: true })))
    const client = createApiClient({ fetcher })
    await client.createConnection({ name: '本地库', driverJar: 'driver.jar', jdbcUrl: 'jdbc:dm7://host', username: 'user', password: 'secret' })
    await client.setDefaultConnection('connection-1')
    await client.testConnection('connection-1')
    await client.query({ sql: 'SELECT 1' })
    await client.execute({ sql: 'UPDATE T SET A=1', purpose: 'SCHEMA_CHANGE', atomic: true })
    await client.cancelExecution('execution-1')
    await client.releaseExport(true)
    expect(fetcher.mock.calls.map(([path]) => path)).toEqual([
      '/api/connections', '/api/connections/connection-1/default', '/api/connections/connection-1/test',
      '/api/query', '/api/execute', '/api/executions/execution-1/cancel', '/api/release/export',
    ])
    for (const [, init] of fetcher.mock.calls) {
      expect(init).toEqual(expect.objectContaining({ credentials: 'same-origin' }))
      expect(init.headers).toEqual(expect.objectContaining({ 'Content-Type': 'application/json' }))
    }
    expect(JSON.parse(fetcher.mock.calls[0][1].body)).toMatchObject({ name: '本地库', password: 'secret' })
    expect(JSON.parse(fetcher.mock.calls[6][1].body)).toEqual({ confirm: true })
  })
})
