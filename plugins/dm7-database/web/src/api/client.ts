import type { ApiClient, CancelResult, ConnectionInput, ConnectionList, ConnectionTestResult, DeleteConnectionResult, DownloadArtifact, ExecuteInput, ExecuteResult, ExecutionDetail, ExportArtifact, HistoryPage, QueryInput, QueryResult, ReleaseSnapshot, RuntimeSummary, SafeConnection, SchemaPage, UrlDiagnostics } from './types'

type Fetcher = typeof fetch
type ClientOptions = { fetcher?: Fetcher; timeoutMs?: number }
export type ApiErrorCategory = 'UNAUTHENTICATED' | 'CONFLICT' | 'RATE_LIMITED' | 'TIMEOUT' | 'ABORTED' | 'HTTP_ERROR' | 'NETWORK_ERROR' | 'MALFORMED_RESPONSE'

function categoryFor(status: number, code: string): ApiErrorCategory {
  if (code === 'TIMEOUT') return 'TIMEOUT'
  if (code === 'ABORTED') return 'ABORTED'
  if (code === 'MALFORMED_RESPONSE') return 'MALFORMED_RESPONSE'
  if (status === 401) return 'UNAUTHENTICATED'
  if (status === 409) return 'CONFLICT'
  if (status === 429) return 'RATE_LIMITED'
  return status === 0 ? 'NETWORK_ERROR' : 'HTTP_ERROR'
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    public readonly correlationId?: string,
    public readonly category: ApiErrorCategory = categoryFor(status, code),
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

const fallbackByStatus = (status: number) => {
  if (status === 401) return { code: 'UNAUTHENTICATED', message: '控制台会话已失效，请重新打开。' }
  if (status === 409) return { code: 'CONFLICT', message: '请求与当前状态冲突。' }
  if (status === 429) return { code: 'RATE_LIMITED', message: '请求过于频繁，请稍后重试。' }
  return { code: `HTTP_${status}`, message: '请求失败，请稍后重试。' }
}

async function errorFromResponse(response: Response) {
  const fallback = fallbackByStatus(response.status)
  const type = response.headers.get('Content-Type')?.toLowerCase() ?? ''
  if (!type.startsWith('application/json')) return new ApiError(response.status, fallback.code, fallback.message)
  let envelope: unknown
  try { envelope = await response.json() } catch { return new ApiError(response.status, fallback.code, fallback.message) }
  const safe = envelope && typeof envelope === 'object' ? envelope as Record<string, unknown> : {}
  const code = typeof safe.code === 'string' && safe.code.length <= 128 ? safe.code : fallback.code
  const message = typeof safe.message === 'string' && safe.message.length <= 2_048 ? safe.message : fallback.message
  const correlationId = typeof safe.correlationId === 'string' && safe.correlationId.length <= 128 ? safe.correlationId : undefined
  return new ApiError(response.status, code, message, correlationId)
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
      if (!response.ok) throw await errorFromResponse(response)
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
      if (!response.ok) throw await errorFromResponse(response)
      return { filename: contentFilename(response), blob: await response.blob() }
    } catch (error) {
      if (error instanceof ApiError) throw error
      if (error instanceof DOMException && (error.name === 'AbortError' || error.name === 'TimeoutError')) throw new ApiError(0, error.name === 'TimeoutError' ? 'TIMEOUT' : 'ABORTED', error.name === 'TimeoutError' ? '请求超时。' : '请求已取消。')
      throw new ApiError(0, 'NETWORK_ERROR', '无法下载文件。')
    }
  }

  const queryString = (query: Record<string, string | number | boolean | undefined> = {}) => {
    const search = new URLSearchParams()
    for (const [key, value] of Object.entries(query)) if (value !== undefined) search.set(key, String(value))
    const encoded = search.toString()
    return encoded ? `?${encoded}` : ''
  }

  return {
    runtime: (signal) => request<RuntimeSummary>('/api/runtime', { method: 'GET' }, signal),
    history: (query, signal) => request<HistoryPage>(`/api/history${queryString(query)}`, { method: 'GET' }, signal),
    removeConnection: (id, signal) => request<DeleteConnectionResult>(`/api/connections/${encodeURIComponent(id)}`, { method: 'DELETE' }, signal),
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
