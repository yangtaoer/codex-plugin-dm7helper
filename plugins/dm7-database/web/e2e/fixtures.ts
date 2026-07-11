import { createHash } from 'node:crypto'
import { test as base, expect, type Page, type Route } from '@playwright/test'

type SafeConnectionRecord = {
  id: string; name: string; configured: boolean; connected: boolean; hasPassword: boolean
  driverFileName: string; driverSha256: string; driverClass: string; jdbcUrl: string
  urlSummary: string; username: string; schema: string | null; connectTimeoutSeconds: number
  socketTimeoutSeconds: number; queryTimeoutSeconds: number; maxRows: number; maxBytes: number
  isDefault: boolean
}

type HistoryRecord = {
  executionId: string; correlationId: string; connectionFingerprint: string; source: 'MCP' | 'CONSOLE'
  purpose: string | null; status: string; startedAt: string; completedAt: string | null
  affectedRows: number; returnedRows: number; recorded: boolean; exclusionReason: string | null
  sqlSummary: string
  kind: 'QUERY' | 'EXPLAIN' | 'DDL' | 'DML'
  success: boolean
}

export type FixtureState = {
  version: string
  connections: SafeConnectionRecord[]
  history: HistoryRecord[]
  exported: boolean
  runtimeMode: 'ready' | 'loading' | 'error' | 'expired'
  connectionWriteMode: 'ready' | 'conflict' | 'validation'
  releaseMode: 'ready' | 'conflict' | 'missing' | 'tampered' | 'recoverable'
  queryMode: 'ready' | 'pending'
  knownExecutions: Set<string>
  cancelRequested: Set<string>
  pendingQueries: Map<string, Route>
  networkResponses: Array<{ method: string; path: string; status: number; body: string }>
  expectedHttpStatuses: Set<number>
}

const now = '2026-07-11T12:00:00Z'
const phases = ['queued', 'connecting', 'parsing', 'executing', 'committing', 'logging', 'completed', 'failed', 'cancelled', 'rejected'] as const
const secretKeys = new Set(['password', 'clearPassword', 'driverJar'])
const exportBytes = Buffer.from("-- DM7 Codex Plugin\r\nUPDATE CUSTOMER_PROFILE SET DISPLAY_NAME = '演示数据';\r\n", 'utf8')
const exportSha = createHash('sha256').update(exportBytes).digest('hex')

const connection: SafeConnectionRecord = {
  id: 'demo-connection', name: '华东生产只读', configured: true, connected: true,
  hasPassword: true, driverFileName: 'Dm7JdbcDriver-7.0.jar', driverSha256: 'A'.repeat(64),
  driverClass: 'dm.jdbc.driver.DmDriver', jdbcUrl: 'jdbc:dm7://dm7.example.invalid:5236?dbname=SYSTEM',
  urlSummary: 'jdbc:dm7://dm7…:5236?dbname=SYSTEM', username: 'SAFE_DEMO_USER', schema: 'SYSTEM',
  connectTimeoutSeconds: 10, socketTimeoutSeconds: 30, queryTimeoutSeconds: 60,
  maxRows: 1000, maxBytes: 10485760, isDefault: true,
}

function assertSafe(value: unknown) {
  if (!value || typeof value !== 'object') return
  for (const [key, child] of Object.entries(value)) {
    if (secretKeys.has(key)) throw new Error(`Fixture response leaked forbidden field: ${key}`)
    assertSafe(child)
  }
}

async function json(route: Route, state: FixtureState, value: unknown, status = 200) {
  assertSafe(value)
  const body = JSON.stringify(value)
  const request = route.request()
  const url = new URL(request.url())
  state.networkResponses.push({ method: request.method(), path: `${url.pathname}${url.search}`, status, body })
  return route.fulfill({ status, contentType: 'application/json; charset=utf-8', body })
}

function safeString(value: unknown, fallback: string) { return typeof value === 'string' && value ? value : fallback }
function safeNumber(value: unknown, fallback: number) { return typeof value === 'number' && Number.isFinite(value) ? value : fallback }
function fileNameOnly(value: unknown, fallback: string) {
  if (typeof value !== 'string' || !value) return fallback
  return value.replaceAll('\\', '/').split('/').at(-1) || fallback
}

function safeConnectionFromInput(existing: SafeConnectionRecord | undefined, body: Record<string, unknown>, id: string): SafeConnectionRecord {
  const base = existing ?? { ...connection, id, hasPassword: false, isDefault: false }
  return {
    id,
    name: safeString(body.name, base.name),
    configured: true,
    connected: false,
    hasPassword: body.clearPassword === true ? false : base.hasPassword || (typeof body.password === 'string' && body.password.length > 0),
    driverFileName: fileNameOnly(body.driverJar, base.driverFileName),
    driverSha256: base.driverSha256,
    driverClass: safeString(body.driverClass, base.driverClass),
    jdbcUrl: safeString(body.jdbcUrl, base.jdbcUrl),
    urlSummary: safeString(body.jdbcUrl, base.urlSummary).replace(/\/\/([^/:]+)(?=:)/, '//dm7…'),
    username: safeString(body.username, base.username),
    schema: body.schema === null ? null : safeString(body.schema, base.schema ?? '') || null,
    connectTimeoutSeconds: safeNumber(body.connectTimeoutSeconds, base.connectTimeoutSeconds),
    socketTimeoutSeconds: safeNumber(body.socketTimeoutSeconds, base.socketTimeoutSeconds),
    queryTimeoutSeconds: safeNumber(body.queryTimeoutSeconds, base.queryTimeoutSeconds),
    maxRows: safeNumber(body.maxRows, base.maxRows),
    maxBytes: safeNumber(body.maxBytes, base.maxBytes),
    isDefault: body.isDefault === true || base.isDefault,
  }
}

export async function emitExecutionEvent(page: Page, state: FixtureState, status: typeof phases[number], executionId = '22222222-2222-4222-8222-222222222222') {
  const item = state.history.find((entry) => entry.executionId === executionId)
  if (item && ['completed', 'failed', 'cancelled', 'rejected'].includes(status)) {
    item.status = status.toUpperCase(); item.completedAt = now; item.success = status === 'completed'
  }
  await page.evaluate(({ status, executionId }) => {
    const emit = (window as unknown as { __dm7EmitEvent(status: string, executionId: string): void }).__dm7EmitEvent
    emit(status, executionId)
  }, { status, executionId })
  const pending = state.pendingQueries.get(executionId)
  if (pending && ['completed', 'failed', 'cancelled', 'rejected'].includes(status)) {
    state.pendingQueries.delete(executionId)
    await json(pending, state, { executionId, success: false, columns: [], rows: [], truncated: false, returnedRows: 0, bytes: 0, elapsedMillis: 20, databaseFingerprint: 'd'.repeat(64), error: { correlationId: 'corr-pending-terminal', phase: status.toUpperCase(), message: '执行已进入终态。', sqlState: null, errorCode: null, restartRequired: false } })
  }
}

export async function installFixture(page: Page, initial?: Partial<FixtureState>) {
  const state: FixtureState = {
    version: 'v001', connections: [{ ...connection }], exported: false, runtimeMode: 'ready',
    connectionWriteMode: 'ready', releaseMode: 'ready', queryMode: 'ready', knownExecutions: new Set(), cancelRequested: new Set(), pendingQueries: new Map(), networkResponses: [], expectedHttpStatuses: new Set(),
    history: [{
      executionId: '11111111-1111-4111-8111-111111111111', correlationId: '11111111-1111-4111-8111-111111111111',
      connectionFingerprint: 'd'.repeat(64), source: 'CONSOLE', purpose: 'PRODUCTION_CHANGE',
      status: 'COMPLETED', startedAt: now, completedAt: '2026-07-11T12:00:01Z',
      affectedRows: 1, returnedRows: 0, recorded: true, exclusionReason: null,
      sqlSummary: 'UPDATE CUSTOMER_PROFILE SET DISPLAY_NAME = ? WHERE ID = 42',
      kind: 'DML', success: true,
    }], ...initial,
  }

  const browserFaults: string[] = []
  page.on('pageerror', (error) => browserFaults.push(`pageerror:${error.message}`))
  page.on('console', (message) => {
    if (message.type() !== 'error') return
    const expectedHttpNoise = [...state.expectedHttpStatuses].some((status) => message.text().includes(`status of ${status}`))
    if (!expectedHttpNoise) browserFaults.push(`console:${message.text()}`)
  })
  page.on('requestfailed', (request) => {
    const reason = request.failure()?.errorText ?? 'unknown'
    const url = new URL(request.url())
    const intentionalAbort = reason.includes('ERR_ABORTED') && url.origin === 'http://127.0.0.1:4173'
    if (!intentionalAbort) browserFaults.push(`requestfailed:${url.pathname}:${reason}`)
  })
  page.on('request', (request) => {
    const url = new URL(request.url())
    if (!['127.0.0.1', 'localhost'].includes(url.hostname) && !['data:', 'blob:'].includes(url.protocol)) browserFaults.push(`external:${url.origin}`)
  })

  await page.addInitScript((eventNames) => {
    Object.defineProperty(crypto, 'randomUUID', { value: () => '22222222-2222-4222-8222-222222222222', configurable: true })
    const sources: EventTarget[] = []
    let sequence = 0
    class FixtureEventSource extends EventTarget {
      static OPEN = 1; readyState = 1; url: string; withCredentials = false
      onopen: ((event: Event) => void) | null = null; onmessage: ((event: MessageEvent) => void) | null = null; onerror: ((event: Event) => void) | null = null
      constructor(url: string | URL) { super(); this.url = String(url); sources.push(this); queueMicrotask(() => { const event = new Event('open'); this.onopen?.(event); this.dispatchEvent(event) }) }
      close() { this.readyState = 2; const index = sources.indexOf(this); if (index >= 0) sources.splice(index, 1) }
    }
    Object.defineProperty(window, 'EventSource', { value: FixtureEventSource, configurable: true })
    Object.defineProperty(window, '__dm7EmitEvent', { value: (status: string, executionId: string) => {
      if (!eventNames.includes(status)) throw new Error(`Unsupported fixture event ${status}`)
      const data = JSON.stringify({ id: String(++sequence), executionId, status, timestamp: '2026-07-11T12:00:00Z', detail: `阶段 ${status}` })
      for (const source of sources) source.dispatchEvent(new MessageEvent(status, { data, lastEventId: String(sequence) }))
    } })
  }, phases)

  await page.route('**/api/**', async (route) => {
    const request = route.request(); const url = new URL(request.url()); const path = url.pathname; const method = request.method()
    if (path === '/api/runtime') {
      if (state.runtimeMode === 'loading') return new Promise<void>(() => undefined)
      if (state.runtimeMode === 'expired') return json(route, state, { code: 'UNAUTHENTICATED', message: '控制台会话已失效，请重新打开。', correlationId: 'corr-session-expired' }, 401)
      if (state.runtimeMode === 'error') return json(route, state, { code: 'RUNTIME_UNAVAILABLE', message: '本地运行状态暂时不可用。', correlationId: 'corr-runtime-error' }, 503)
      return json(route, state, { sessionShortId: '7fa2c9e1', currentVersion: state.version, runningCount: state.history.filter((item) => !['COMPLETED', 'FAILED', 'CANCELLED', 'REJECTED'].includes(item.status)).length, connections: state.connections.length })
    }
    if (path === '/api/events') return route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' })
    if (path === '/api/connections/diagnostics') return json(route, state, { urlSummary: 'jdbc:dm7://dm7…:5236/SYSTEM', warnings: ['URL 路径段可能被旧版驱动忽略，请改用 dbname=SYSTEM 或 schema=SYSTEM。'] })
    if (path === '/api/connections' && method === 'GET') return json(route, state, { connections: state.connections })
    if (path === '/api/connections' && method === 'POST') {
      if (state.connectionWriteMode === 'conflict') return json(route, state, { code: 'CONNECTION_CONFLICT', message: '连接名称已存在。', correlationId: 'corr-connection-conflict' }, 409)
      if (state.connectionWriteMode === 'validation') return json(route, state, { code: 'VALIDATION', message: '连接参数无效。', correlationId: 'corr-connection-validation' }, 422)
      const body = request.postDataJSON() as Record<string, unknown>
      const created = safeConnectionFromInput(undefined, body, `demo-${state.connections.length + 1}`)
      if (created.isDefault) state.connections.forEach((item) => { item.isDefault = false })
      if (!state.connections.length) created.isDefault = true
      state.connections.push(created); return json(route, state, created, 201)
    }
    const connectionMatch = path.match(/^\/api\/connections\/([^/]+)(?:\/(default|test))?$/)
    if (connectionMatch) {
      const index = state.connections.findIndex((item) => item.id === connectionMatch[1])
      if (index < 0) return json(route, state, { code: 'CONNECTION_NOT_FOUND', message: '连接不存在。', correlationId: 'corr-connection-missing' }, 404)
      if (connectionMatch[2] === 'test') return json(route, state, { success: true, latencyMs: 18, driverVersion: '7.0', serverVersion: 'DM Database Server 7.6', actualUser: 'SAFE_DEMO_USER', actualSchema: 'SYSTEM', chineseRoundTrip: true, restartRequired: false, warnings: [] })
      if (connectionMatch[2] === 'default') { state.connections.forEach((item, i) => { item.isDefault = i === index }); return json(route, state, state.connections[index]) }
      if (method === 'GET') return json(route, state, state.connections[index])
      if (method === 'PUT') {
        if (state.connectionWriteMode !== 'ready') return json(route, state, { code: state.connectionWriteMode === 'conflict' ? 'CONNECTION_CONFLICT' : 'VALIDATION', message: '连接更新被拒绝。', correlationId: 'corr-connection-write' }, state.connectionWriteMode === 'conflict' ? 409 : 422)
        const body = request.postDataJSON() as Record<string, unknown>
        state.connections[index] = safeConnectionFromInput(state.connections[index], body, state.connections[index].id)
        return json(route, state, state.connections[index])
      }
      if (method === 'DELETE') {
        const body = (request.postDataJSON() ?? {}) as Record<string, unknown>; const deleting = state.connections[index]
        if (deleting.isDefault && state.connections.length > 1 && typeof body.replacementDefaultId !== 'string' && body.leaveWithoutDefault !== true) return json(route, state, { code: 'VALIDATION', message: '请明确删除后的默认连接。', correlationId: 'corr-delete-default' }, 422)
        state.connections.splice(index, 1)
        if (typeof body.replacementDefaultId === 'string') state.connections.forEach((item) => { item.isDefault = item.id === body.replacementDefaultId })
        if (body.leaveWithoutDefault === true) state.connections.forEach((item) => { item.isDefault = false })
        return json(route, state, { deleted: true, defaultConnectionId: state.connections.find((item) => item.isDefault)?.id ?? null })
      }
    }
    if (path === '/api/sql/classify') {
      const sql = String((request.postDataJSON() as { sql: string }).sql).trim(); const mutation = /^(UPDATE|INSERT|DELETE|MERGE|CREATE|ALTER|DROP|TRUNCATE)/i.test(sql); const ddl = /^(CREATE|ALTER|DROP|TRUNCATE)/i.test(sql)
      return json(route, state, { statementCount: sql.split(';').filter(Boolean).length || 1, kinds: [mutation ? (ddl ? 'DDL' : 'DML') : 'QUERY'], queryOnly: !mutation, requiresPurpose: mutation, atomicAllowed: mutation && !ddl })
    }
    if (path === '/api/query') {
      const body = request.postDataJSON() as { executionId: string; sql: string }; state.knownExecutions.add(body.executionId)
      if (state.queryMode === 'pending') { state.pendingQueries.set(body.executionId, route); return }
      const rows = body.sql.includes('LONG_RESULT') ? Array.from({ length: 250 }, (_, i) => ({ 中文列: `达梦数据库长结果-${String(i).padStart(3, '0')}` })) : [{ 中文列: '达梦数据库 · 中文结果已验证' }]
      return json(route, state, { executionId: body.executionId, success: true, columns: [{ outputLabel: '中文列', originalLabel: '中文列', originalName: '中文列', jdbcType: 12, typeName: 'VARCHAR' }], rows, truncated: rows.length > 100, returnedRows: rows.length, bytes: rows.length * 42, elapsedMillis: 36, databaseFingerprint: 'd'.repeat(64), error: null })
    }
    if (path === '/api/execute') {
      const body = request.postDataJSON() as { executionId: string }; state.knownExecutions.add(body.executionId)
      return json(route, state, { executionId: body.executionId, success: true, status: 'COMPLETED', statements: [{ index: 1, kind: 'DML', success: true, committed: true, rowCount: 1, recorded: true, exclusionReason: null, commitBehavior: 'COMMITTED', elapsedMillis: 24, error: null }], elapsedMillis: 31, databaseFingerprint: 'd'.repeat(64), error: null })
    }
    if (path === '/api/history') {
      const correlationId = url.searchParams.get('correlationId')
      if (correlationId && !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(correlationId)) return json(route, state, { code: 'VALIDATION', message: '关联 ID 必须是 UUID。', correlationId: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee' }, 422)
      return json(route, state, historyPage(state, url.searchParams))
    }
    const execution = path.match(/^\/api\/executions\/([^/]+)(?:\/cancel)?$/)
    if (execution) {
      const id = execution[1]; const item = state.history.find((entry) => entry.executionId === id)
      if (path.endsWith('/cancel')) { if (!item && !state.knownExecutions.has(id)) return json(route, state, { code: 'EXECUTION_NOT_FOUND', message: '执行不存在。', correlationId: 'corr-execution-missing' }, 404); state.cancelRequested.add(id); return json(route, state, { executionId: id, cancelRequested: true }) }
      if (!item) return json(route, state, { code: 'EXECUTION_NOT_FOUND', message: '执行不存在。', correlationId: 'corr-execution-missing' }, 404)
      return json(route, state, executionDetail(item))
    }
    if (path === '/api/release' && method === 'GET') { if (state.releaseMode === 'missing') return json(route, state, { code: 'RELEASE_MISSING', message: '发版状态不存在。', correlationId: 'corr-release-missing' }, 404); return json(route, state, releaseSnapshot(state)) }
    if (path === '/api/release/export') { if (state.releaseMode === 'conflict') return json(route, state, { code: 'RELEASE_CONFLICT', message: '发版状态已变化，请刷新后重试。', correlationId: 'corr-release-conflict' }, 409); state.exported = true; const old = state.version; state.version = 'v002'; return json(route, state, artifact(old, state.version)) }
    if (path === '/api/release/recover') { if (state.releaseMode === 'tampered') return json(route, state, { code: 'RELEASE_RECOVERY_UNAVAILABLE', message: '该密封导出当前不可恢复。', correlationId: 'corr-recovery-tampered' }, 409); state.releaseMode = 'ready'; state.exported = true; return json(route, state, artifact('v000', state.version)) }
    if (/^\/api\/release\/artifacts\/.+\/download$/.test(path)) return route.fulfill({ status: 200, headers: { 'Content-Type': 'application/sql; charset=utf-8', 'Content-Disposition': 'attachment; filename="dm7-demo-v001.sql"' }, body: exportBytes })
    return json(route, state, { code: 'NOT_FOUND', message: '演示接口不存在。', correlationId: 'corr-not-found' }, 404)
  })
  return { state, browserFaults }
}

function historyPage(state: FixtureState, search: URLSearchParams) {
  const matches = state.history.filter((item) => {
    for (const key of ['status', 'source', 'purpose', 'correlationId'] as const) if (search.has(key) && String(item[key]) !== search.get(key)) return false
    if (search.has('recorded') && String(item.recorded) !== search.get('recorded')) return false
    if (search.has('success') && String(item.success) !== search.get('success')) return false
    if (search.has('kind') && item.kind !== search.get('kind')) return false
    if (search.has('startedAfter') && Date.parse(item.startedAt) < Date.parse(String(search.get('startedAfter')))) return false
    if (search.has('startedBefore') && Date.parse(item.startedAt) > Date.parse(String(search.get('startedBefore')))) return false
    return true
  })
  const offset = Math.max(0, Number(search.get('offset') ?? 0)); const limit = Math.min(100, Math.max(1, Number(search.get('limit') ?? 50)))
  const unique = [...new Map(matches.map((item) => [item.executionId, item])).values()]
  return { items: unique.slice(offset, offset + limit).map(publicHistory), offset, limit, hasMore: offset + limit < unique.length }
}

function publicHistory(item: HistoryRecord) {
  const { kind: _kind, success: _success, ...contract } = item
  return contract
}

function executionDetail(item: HistoryRecord) {
  return { summary: { ...publicHistory(item), phase: item.status, error: null }, statements: [{ index: 1, kind: item.kind, status: item.success ? 'SUCCEEDED' : item.status, phase: item.status, rowCount: item.affectedRows, success: item.success, committed: item.success, commitBehavior: item.success ? 'COMMITTED' : 'NONE', elapsedMillis: 24, recorded: item.recorded, exclusionReason: item.exclusionReason, sqlSummary: item.sqlSummary, error: null }], events: [{ sequence: 1, status: item.status, timestamp: now, detail: `阶段 ${item.status}` }] }
}

function artifact(version: string, next: string) { return { id: 'artifact-v001', version, newActiveVersion: next, filename: 'dm7-demo-v001.sql', byteLength: exportBytes.length, sha256: exportSha, sealedSourceSha256: 'b'.repeat(64), statementCount: 2, firstSequence: 12, lastSequence: 13, createdAt: now, downloadUrl: '/api/release/artifacts/artifact-v001/download' } }

function releaseSnapshot(state: FixtureState) {
  const empty = state.version === 'v002'
  const complete = { id: 'artifact-v001', state: 'COMPLETE', version: 'v001', filename: 'dm7-demo-v001.sql', sha256: exportSha, byteLength: exportBytes.length, statementCount: 2, firstSequence: 12, lastSequence: 13, createdAt: now, completedAt: now, downloadAvailable: true, downloadUrl: '/api/release/artifacts/artifact-v001/download', integrityState: 'VERIFIED' }
  const recoverable = { ...complete, id: 'artifact-v000', state: 'RECOVERY_REQUIRED', version: 'v000', filename: null, sha256: null, byteLength: null, completedAt: null, downloadAvailable: false, downloadUrl: null, integrityState: 'RECOVERABLE' }
  const tampered = { ...recoverable, integrityState: 'TAMPERED' }
  const artifacts = state.releaseMode === 'recoverable' ? [recoverable] : state.releaseMode === 'tampered' ? [tampered] : state.exported ? [complete] : []
  return { sessionShortId: '7fa2c9e1', currentVersion: state.version, databaseFingerprint: 'd'.repeat(64), bindingState: 'MATCH', statementCount: empty ? 0 : 2, excludedCount: empty ? 0 : 1, failedCount: empty ? 0 : 1, sqlPreview: empty ? '' : "ALTER TABLE CUSTOMER_PROFILE ADD VERIFIED_AT TIMESTAMP;\nUPDATE CUSTOMER_PROFILE SET DISPLAY_NAME = '演示数据' WHERE ID = 42;", previewTruncated: false, firstSequence: empty ? null : 12, lastSequence: empty ? null : 13, runningCount: 0, entriesTruncated: false, entries: empty ? [] : [{ sequence: 12, index: 1, kind: 'DDL', status: 'SUCCEEDED', source: 'CONSOLE', purpose: 'MIGRATION', recorded: true, exclusionReason: null, createdAt: now, sqlSummary: 'ALTER TABLE CUSTOMER_PROFILE ADD VERIFIED_AT TIMESTAMP' }, { sequence: null, index: 2, kind: 'DML', status: 'SUCCEEDED', source: 'CONSOLE', purpose: 'TEST', recorded: false, exclusionReason: 'TEST', createdAt: now, sqlSummary: 'UPDATE CUSTOMER_PROFILE SET DISPLAY_NAME = ? WHERE ID = 42' }, { sequence: null, index: 3, kind: 'DML', status: 'FAILED', source: 'MCP', purpose: 'MIGRATION', recorded: false, exclusionReason: null, createdAt: now, sqlSummary: 'UPDATE CUSTOMER_PROFILE SET DISPLAY_NAME = ? WHERE ID = 999' }], artifacts }
}

export const test = base.extend<{ fixtureState: FixtureState }>({
  fixtureState: [async ({ page }, use) => {
    const fixture = await installFixture(page)
    await use(fixture.state)
    expect(fixture.browserFaults, 'browser console/page/network guard').toEqual([])
    for (const response of fixture.state.networkResponses) {
      expect(response.body).not.toMatch(/"(?:password|clearPassword|driverJar)"\s*:/)
    }
  }, { auto: true }],
})
export { expect, exportBytes, exportSha }
