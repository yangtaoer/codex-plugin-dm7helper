import { test as base, expect, type Page, type Route } from '@playwright/test'

export type FixtureState = {
  version: string
  connections: Array<Record<string, unknown>>
  history: Array<Record<string, unknown>>
  exported: boolean
  runtimeMode: 'ready' | 'error' | 'expired'
  releaseMode: 'ready' | 'conflict' | 'missing' | 'tampered' | 'recoverable'
}

const now = '2026-07-11T12:00:00Z'
const connection = {
  id: 'demo-connection', name: '华东生产只读', configured: true, connected: true,
  hasPassword: true, driverFileName: 'Dm7JdbcDriver-7.0.jar', driverSha256: 'A'.repeat(64),
  driverClass: 'dm.jdbc.driver.DmDriver', jdbcUrl: 'jdbc:dm7://dm7.example.invalid:5236?dbname=SYSTEM',
  urlSummary: 'jdbc:dm7://dm7…:5236?dbname=SYSTEM', username: 'SAFE_DEMO_USER', schema: 'SYSTEM',
  connectTimeoutSeconds: 10, socketTimeoutSeconds: 30, queryTimeoutSeconds: 60,
  maxRows: 1000, maxBytes: 10485760, isDefault: true,
}

function json(route: Route, value: unknown, status = 200) {
  return route.fulfill({ status, contentType: 'application/json; charset=utf-8', body: JSON.stringify(value) })
}

export async function installFixture(page: Page, initial?: Partial<FixtureState>) {
  const state: FixtureState = {
    version: 'v001', connections: [{ ...connection }], exported: false, runtimeMode: 'ready', releaseMode: 'ready',
    history: [{
      executionId: '11111111-1111-4111-8111-111111111111', correlationId: 'corr-demo-001',
      connectionFingerprint: 'd'.repeat(64), source: 'CONSOLE', purpose: 'PRODUCTION_CHANGE',
      status: 'COMPLETED', startedAt: now, completedAt: '2026-07-11T12:00:01Z',
      affectedRows: 1, returnedRows: 0, recorded: true, exclusionReason: null,
      sqlSummary: "UPDATE CUSTOMER_PROFILE SET DISPLAY_NAME = ? WHERE ID = 42",
    }], ...initial,
  }

  await page.addInitScript(() => {
    Object.defineProperty(crypto, 'randomUUID', { value: () => '22222222-2222-4222-8222-222222222222', configurable: true })
    class FixtureEventSource extends EventTarget {
      static OPEN = 1
      readyState = 1
      url: string
      withCredentials = false
      onopen: ((event: Event) => void) | null = null
      onmessage: ((event: MessageEvent) => void) | null = null
      onerror: ((event: Event) => void) | null = null
      constructor(url: string | URL) {
        super(); this.url = String(url)
        queueMicrotask(() => { const event = new Event('open'); this.onopen?.(event); this.dispatchEvent(event) })
      }
      close() { this.readyState = 2 }
    }
    Object.defineProperty(window, 'EventSource', { value: FixtureEventSource, configurable: true })
  })

  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const method = request.method()
    if (path === '/api/runtime') {
      if (state.runtimeMode === 'expired') return json(route, { code: 'UNAUTHENTICATED', message: '控制台会话已失效，请重新打开。', correlationId: 'corr-session-expired' }, 401)
      if (state.runtimeMode === 'error') return json(route, { code: 'RUNTIME_UNAVAILABLE', message: '本地运行状态暂时不可用。', correlationId: 'corr-runtime-error' }, 503)
      return json(route, { sessionShortId: '7fa2c9e1', currentVersion: state.version, runningCount: state.history.filter((item) => !['COMPLETED', 'FAILED', 'CANCELLED', 'REJECTED'].includes(String(item.status))).length, connections: state.connections.length })
    }
    if (path === '/api/events') return route.fulfill({ status: 200, contentType: 'text/event-stream', body: '' })
    if (path === '/api/connections/diagnostics') return json(route, { urlSummary: 'jdbc:dm7://dm7…:5236/SYSTEM', warnings: ['URL 路径段可能被旧版驱动忽略，请改用 dbname=SYSTEM 或 schema=SYSTEM。'] })
    if (path === '/api/connections' && method === 'GET') return json(route, { connections: state.connections })
    if (path === '/api/connections' && method === 'POST') {
      const body = request.postDataJSON() as Record<string, unknown>
      const created = { ...connection, id: `demo-${state.connections.length + 1}`, name: body.name, username: body.username, hasPassword: Boolean(body.password), isDefault: state.connections.length === 0 }
      state.connections.push(created); return json(route, created, 201)
    }
    const connectionMatch = path.match(/^\/api\/connections\/([^/]+)(?:\/(default|test))?$/)
    if (connectionMatch) {
      const index = state.connections.findIndex((item) => item.id === connectionMatch[1])
      if (connectionMatch[2] === 'test') return json(route, { success: true, latencyMs: 18, driverVersion: '7.0', serverVersion: 'DM Database Server 7.6', actualUser: 'SAFE_DEMO_USER', actualSchema: 'SYSTEM', chineseRoundTrip: true, restartRequired: false, warnings: [] })
      if (connectionMatch[2] === 'default') { state.connections = state.connections.map((item, i) => ({ ...item, isDefault: i === index })); return json(route, state.connections[index]) }
      if (method === 'GET') return json(route, state.connections[index])
      if (method === 'PUT') { const body = request.postDataJSON(); state.connections[index] = { ...state.connections[index], ...body, hasPassword: body.clearPassword ? false : state.connections[index].hasPassword || Boolean(body.password) }; return json(route, state.connections[index]) }
      if (method === 'DELETE') { state.connections.splice(index, 1); return json(route, { deleted: true, defaultConnectionId: state.connections.find((item) => item.isDefault)?.id ?? null }) }
    }
    if (path === '/api/sql/classify') {
      const sql = String((request.postDataJSON() as { sql: string }).sql).trim()
      const mutation = /^(UPDATE|INSERT|DELETE|MERGE|CREATE|ALTER|DROP|TRUNCATE)/i.test(sql)
      return json(route, { statementCount: 1, kinds: [mutation ? (/^(CREATE|ALTER|DROP|TRUNCATE)/i.test(sql) ? 'DDL' : 'DML') : 'QUERY'], queryOnly: !mutation, requiresPurpose: mutation, atomicAllowed: mutation && !/^(CREATE|ALTER|DROP|TRUNCATE)/i.test(sql) })
    }
    if (path === '/api/query') {
      const body = request.postDataJSON() as { executionId: string }
      return json(route, { executionId: body.executionId, success: true, columns: [{ outputLabel: '中文列', originalLabel: '中文列', originalName: '中文列', jdbcType: 12, typeName: 'VARCHAR' }], rows: [{ 中文列: '达梦数据库 · 中文结果已验证' }], truncated: false, returnedRows: 1, bytes: 42, elapsedMillis: 36, databaseFingerprint: 'd'.repeat(64), error: null })
    }
    if (path === '/api/execute') {
      const body = request.postDataJSON() as { executionId: string }
      return json(route, { executionId: body.executionId, success: true, status: 'COMPLETED', statements: [{ index: 1, kind: 'DML', success: true, committed: true, rowCount: 1, recorded: true, exclusionReason: null, commitBehavior: 'COMMITTED', elapsedMillis: 24, error: null }], elapsedMillis: 31, databaseFingerprint: 'd'.repeat(64), error: null })
    }
    if (path === '/api/history') return json(route, { items: state.history, offset: 0, limit: 50, hasMore: false })
    const execution = path.match(/^\/api\/executions\/([^/]+)(?:\/cancel)?$/)
    if (execution) {
      if (path.endsWith('/cancel')) { if (state.history[0]) state.history[0].status = 'CANCELLED'; return json(route, { executionId: execution[1], cancelRequested: true }) }
      const item = state.history[0]
      return json(route, { summary: { ...item, phase: 'COMPLETED', error: null }, statements: [{ index: 1, kind: 'DML', status: 'SUCCEEDED', phase: 'COMPLETED', rowCount: 1, success: true, committed: true, commitBehavior: 'COMMITTED', elapsedMillis: 24, recorded: true, exclusionReason: null, sqlSummary: item.sqlSummary, error: null }], events: [{ sequence: 1, status: 'COMPLETED', timestamp: now, detail: '执行完成' }] })
    }
    if (path === '/api/release' && method === 'GET') {
      if (state.releaseMode === 'missing') return json(route, { code: 'RELEASE_MISSING', message: '发版状态不存在。', correlationId: 'corr-release-missing' }, 404)
      return json(route, releaseSnapshot(state))
    }
    if (path === '/api/release/export') {
      if (state.releaseMode === 'conflict') return json(route, { code: 'RELEASE_CONFLICT', message: '发版状态已变化，请刷新后重试。', correlationId: 'corr-release-conflict' }, 409)
      state.exported = true; const old = state.version; state.version = 'v002'; return json(route, artifact(old, state.version))
    }
    if (path === '/api/release/recover') {
      if (state.releaseMode === 'tampered') return json(route, { code: 'RELEASE_RECOVERY_UNAVAILABLE', message: '该密封导出当前不可恢复。', correlationId: 'corr-recovery-tampered' }, 409)
      return json(route, artifact('v000', state.version))
    }
    if (/^\/api\/release\/artifacts\/.+\/download$/.test(path)) return route.fulfill({ status: 200, headers: { 'Content-Type': 'application/sql; charset=utf-8', 'Content-Disposition': 'attachment; filename="dm7-demo-v001.sql"' }, body: '-- DM7 Codex Plugin\r\nUPDATE CUSTOMER_PROFILE SET DISPLAY_NAME = \'演示数据\';\r\n' })
    return json(route, { code: 'NOT_FOUND', message: '演示接口不存在。', correlationId: 'corr-not-found' }, 404)
  })
  return state
}

function artifact(version: string, next: string) {
  return { id: 'artifact-v001', version, newActiveVersion: next, filename: 'dm7-demo-v001.sql', byteLength: 104, sha256: 'a'.repeat(64), sealedSourceSha256: 'b'.repeat(64), statementCount: 2, firstSequence: 12, lastSequence: 13, createdAt: now, downloadUrl: '/api/release/artifacts/artifact-v001/download' }
}

function releaseSnapshot(state: FixtureState) {
  const empty = state.version === 'v002'
  const complete = { id: 'artifact-v001', state: 'COMPLETE', version: 'v001', filename: 'dm7-demo-v001.sql', sha256: 'a'.repeat(64), byteLength: 104, statementCount: 2, firstSequence: 12, lastSequence: 13, createdAt: now, completedAt: now, downloadAvailable: true, downloadUrl: '/api/release/artifacts/artifact-v001/download', integrityState: 'VERIFIED' }
  const recoverable = { ...complete, id: 'artifact-v000', state: 'RECOVERY_REQUIRED', version: 'v000', filename: null, sha256: null, byteLength: null, completedAt: null, downloadAvailable: false, downloadUrl: null, integrityState: 'RECOVERABLE' }
  return { sessionShortId: '7fa2c9e1', currentVersion: state.version, databaseFingerprint: 'd'.repeat(64), bindingState: 'MATCH', statementCount: empty ? 0 : 2, excludedCount: empty ? 0 : 1, failedCount: empty ? 0 : 1, sqlPreview: empty ? '' : "ALTER TABLE CUSTOMER_PROFILE ADD VERIFIED_AT TIMESTAMP;\nUPDATE CUSTOMER_PROFILE SET DISPLAY_NAME = '演示数据' WHERE ID = 42;", previewTruncated: false, firstSequence: empty ? null : 12, lastSequence: empty ? null : 13, runningCount: 0, entriesTruncated: false, entries: empty ? [] : [{ sequence: 12, index: 1, kind: 'DDL', status: 'SUCCEEDED', source: 'CONSOLE', purpose: 'MIGRATION', recorded: true, exclusionReason: null, createdAt: now, sqlSummary: 'ALTER TABLE CUSTOMER_PROFILE ADD VERIFIED_AT TIMESTAMP' }, { sequence: null, index: 2, kind: 'DML', status: 'SUCCEEDED', source: 'CONSOLE', purpose: 'TEST', recorded: false, exclusionReason: 'TEST', createdAt: now, sqlSummary: 'UPDATE CUSTOMER_PROFILE SET DISPLAY_NAME = ? WHERE ID = 42' }], artifacts: state.releaseMode === 'recoverable' || state.releaseMode === 'tampered' ? [recoverable] : state.exported ? [complete] : [] }
}

export const test = base.extend<{ fixtureState: FixtureState }>({
  fixtureState: [async ({ page }, use) => { await use(await installFixture(page)) }, { auto: true }],
})
export { expect }
