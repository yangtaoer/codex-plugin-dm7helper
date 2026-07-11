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

  it('matches the real 200 delete shape and distinguishes downloads, malformed JSON, and aborts', async () => {
    const deleted = vi.fn().mockResolvedValue(response({ deleted: true }))
    const deletion = await createApiClient({ fetcher: deleted }).removeConnection('safe-id')
    expect(deletion.deleted).toBe(true)

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
    await client.execute({ sql: 'UPDATE T SET A=1', purpose: 'PRODUCTION_CHANGE', atomic: true })
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

  it('preserves safe download error envelopes and classifies actionable statuses', async () => {
    const fetcher = vi.fn().mockResolvedValue(response({ ok: false, code: 'ARTIFACT_CONFLICT', message: '导出物已变更', correlationId: 'download-corr' }, { status: 409 }))
    await expect(createApiClient({ fetcher }).downloadArtifact('artifact-1')).rejects.toMatchObject({
      status: 409, code: 'ARTIFACT_CONFLICT', message: '导出物已变更', correlationId: 'download-corr', category: 'CONFLICT',
    })
  })

  it('classifies validation failures separately from generic server failures', async () => {
    const validation = vi.fn().mockResolvedValue(response({ code: 'INVALID_SQL', message: 'SQL 不符合约束' }, { status: 422 }))
    await expect(createApiClient({ fetcher: validation }).runtime()).rejects.toMatchObject({ status: 422, category: 'VALIDATION' })
    const server = vi.fn().mockResolvedValue(response({ code: 'SERVER_FAILURE', message: '服务暂不可用' }, { status: 500 }))
    await expect(createApiClient({ fetcher: server }).runtime()).rejects.toMatchObject({ status: 500, category: 'HTTP_ERROR' })
  })

  it.each([
    [401, 'UNAUTHENTICATED'],
    [429, 'RATE_LIMITED'],
  ] as const)('uses a safe non-JSON download fallback for %s as %s', async (status, category) => {
    const fetcher = vi.fn().mockResolvedValue(new Response('private backend body', { status, headers: { 'Content-Type': 'text/plain' } }))
    const logger = vi.spyOn(console, 'error').mockImplementation(() => undefined)
    await expect(createApiClient({ fetcher }).downloadArtifact('artifact-1')).rejects.toMatchObject({ status, code: category, category })
    expect(logger).not.toHaveBeenCalled()
  })

  it('distinguishes download timeout from caller abort', async () => {
    const timedOut = vi.fn((_path: RequestInfo | URL, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new DOMException('deadline', 'TimeoutError')))
    }))
    await expect(createApiClient({ fetcher: timedOut, timeoutMs: 1 }).downloadArtifact('artifact-1')).rejects.toMatchObject({ code: 'TIMEOUT', category: 'TIMEOUT' })
    const aborted = vi.fn().mockRejectedValue(new DOMException('caller', 'AbortError'))
    await expect(createApiClient({ fetcher: aborted }).downloadArtifact('artifact-1')).rejects.toMatchObject({ code: 'ABORTED', category: 'ABORTED' })
  })
})
