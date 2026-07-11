import type { ApiClient, CancelResult, ConnectionInput, ConnectionList, ConnectionTestResult, DownloadArtifact, ExecuteInput, ExecuteResult, ExecutionDetail, ExportArtifact, HistoryPage, HistoryQuery, MetadataQuery, QueryInput, QueryResult, ReleaseSnapshot, RuntimeSummary, SafeConnection, SchemaPage, UrlDiagnostics } from './types'

type Fetcher = typeof fetch
type ClientOptions = { fetcher?: Fetcher; timeoutMs?: number }

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly correlationId?: string,
  ) { super(message); this.name = 'ApiError' }
}

const safePath = (path: string) => {
  if (!path.startsWith('/api/') || path.startsWith('//') || path.includes('://')) throw new TypeError('API path must be same-origin')
  return path
}

function combinedSignal(caller: AbortSignal | undefined, timeoutMs: number) {
  const timeout = AbortSignal.timeout(timeoutMs)
  return caller ? AbortSignal.any([caller, timeout]) : timeout
}

function contentFilename(response: Response) {
  const disposition = response.headers.get('Content-Disposition') ?? ''
  return disposition.match(/filename="?([^";]+)"?/i)?.[1] ?? 'download'
}

export function createApiClient(options: ClientOptions = {}): ApiClient {
  if ('baseUrl' in options) throw new TypeError('Arbitrary API origins are not supported')
  const fetcher = options.fetcher ?? fetch
  const timeoutMs = options.timeoutMs ?? 15_000

  async function request<T>(path: string, init: RequestInit = {}, signal?: AbortSignal): Promise<T> {
    try {
      const response = await fetcher(safePath(path), {
        ...init,
        credentials: 'same-origin',
        signal: combinedSignal(signal, timeoutMs),
        headers: init.body ? { 'Content-Type': 'application/json', ...init.headers } : init.headers,
      })
      if (response.status === 204) return undefined as T
      if (!response.ok) {
        let envelope: unknown
        try { envelope = await response.json() } catch { envelope = undefined }
        const safe = envelope && typeof envelope === 'object' ? envelope as Record<string, unknown> : {}
        throw new ApiError(
          response.status,
          typeof safe.code === 'string' ? safe.code : `HTTP_${response.status}`,
          typeof safe.message === 'string' ? safe.message : '请求失败，请稍后重试。',
          typeof safe.correlationId === 'string' ? safe.correlationId : undefined,
        )
      }
      try { return await response.json() as T }
      catch { throw new ApiError(response.status, 'MALFORMED_RESPONSE', '服务器返回了无法识别的数据。') }
    } catch (error) {
      if (error instanceof ApiError) throw error
      if (error instanceof DOMException && (error.name === 'AbortError' || error.name === 'TimeoutError')) {
        throw new ApiError(0, error.name === 'TimeoutError' ? 'TIMEOUT' : 'ABORTED', error.name === 'TimeoutError' ? '请求超时。' : '请求已取消。')
      }
      throw new ApiError(0, 'NETWORK_ERROR', '无法连接本地控制台。')
    }
  }

  async function download(path: string, signal?: AbortSignal): Promise<DownloadArtifact> {
    try {
      const response = await fetcher(safePath(path), { method: 'GET', credentials: 'same-origin', signal: combinedSignal(signal, timeoutMs) })
      if (!response.ok) throw new ApiError(response.status, `HTTP_${response.status}`, '下载失败。')
      return { filename: contentFilename(response), blob: await response.blob() }
    } catch (error) {
      if (error instanceof ApiError) throw error
      if (error instanceof DOMException && error.name === 'AbortError') throw new ApiError(0, 'ABORTED', '请求已取消。')
      throw new ApiError(0, 'NETWORK_ERROR', '无法下载文件。')
    }
  }

  const queryString = (query: HistoryQuery | MetadataQuery = {}) => {
    const search = new URLSearchParams()
    for (const [key, value] of Object.entries(query)) if (value !== undefined) search.set(key, String(value))
    const encoded = search.toString()
    return encoded ? `?${encoded}` : ''
  }

  return {
    runtime: (signal) => request<RuntimeSummary>('/api/runtime', { method: 'GET' }, signal),
    history: (query, signal) => request<HistoryPage>(`/api/history${queryString(query)}`, { method: 'GET' }, signal),
    removeConnection: (id, signal) => request<void>(`/api/connections/${encodeURIComponent(id)}`, { method: 'DELETE' }, signal),
    downloadArtifact: (id, signal) => download(`/api/release/artifacts/${encodeURIComponent(id)}/download`, signal),
    listConnections: (signal) => request<ConnectionList>('/api/connections', { method: 'GET' }, signal),
    getConnection: (id, signal) => request<SafeConnection>(`/api/connections/${encodeURIComponent(id)}`, { method: 'GET' }, signal),
    createConnection: (input: ConnectionInput, signal) => request<SafeConnection>('/api/connections', { method: 'POST', body: JSON.stringify(input) }, signal),
    updateConnection: (id, input, signal) => request<SafeConnection>(`/api/connections/${encodeURIComponent(id)}`, { method: 'PUT', body: JSON.stringify(input) }, signal),
    setDefaultConnection: (id, signal) => request<SafeConnection>(`/api/connections/${encodeURIComponent(id)}/default`, { method: 'POST', body: '{}' }, signal),
    testConnection: (id, signal) => request<ConnectionTestResult>(`/api/connections/${encodeURIComponent(id)}/test`, { method: 'POST', body: '{}' }, signal),
    diagnoseUrl: (jdbcUrl, signal) => request<UrlDiagnostics>(`/api/connections/diagnostics${queryString({ jdbcUrl })}`, { method: 'GET' }, signal),
    query: (input: QueryInput, signal) => request<QueryResult>('/api/query', { method: 'POST', body: JSON.stringify(input) }, signal),
    execute: (input: ExecuteInput, signal) => request<ExecuteResult>('/api/execute', { method: 'POST', body: JSON.stringify(input) }, signal),
    metadata: (query, signal) => request<SchemaPage>(`/api/metadata${queryString(query)}`, { method: 'GET' }, signal),
    getExecution: (id, signal) => request<ExecutionDetail>(`/api/executions/${encodeURIComponent(id)}`, { method: 'GET' }, signal),
    cancelExecution: (id, signal) => request<CancelResult>(`/api/executions/${encodeURIComponent(id)}/cancel`, { method: 'POST', body: '{}' }, signal),
    release: (signal) => request<ReleaseSnapshot>('/api/release', { method: 'GET' }, signal),
    releaseExport: (confirm, signal) => request<ExportArtifact>('/api/release/export', { method: 'POST', body: JSON.stringify({ confirm }) }, signal),
  }
}

export const api = createApiClient()
