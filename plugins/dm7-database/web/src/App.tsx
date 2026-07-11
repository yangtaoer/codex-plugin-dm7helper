import { Activity, Blocks, Cable, CircleGauge, Database, FileClock, Moon, PlaySquare, RefreshCw, Settings, ShieldCheck, Sun, TerminalSquare } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState, type AnchorHTMLAttributes, type ReactNode } from 'react'
import type { ApiClient, RuntimeSummary, SafeConnection } from './api/types'
import { EmptyState } from './components/EmptyState'
import { InlineError, LoadingSkeleton, ToastRegion } from './components/Feedback'
import { PageHeader } from './components/PageHeader'
import { StatusBadge } from './components/StatusBadge'
import { useEventStream, type StreamStatus } from './hooks/useEventStream'
import { usePathname } from './hooks/usePathname'
import { useTheme } from './hooks/useTheme'

type RuntimeState =
  | { kind: 'loading' }
  | { kind: 'ready' | 'stale' | 'reconnecting'; value: RuntimeSummary }
  | { kind: 'error'; message: string; correlationId?: string; value?: RuntimeSummary }
type Route = 'overview' | 'sql' | 'activity' | 'release' | 'connections' | 'settings'
const routes: { id: Route; path: string; label: string; icon: typeof CircleGauge; index: string }[] = [
  { id: 'overview', path: '/app/overview', label: '概览', icon: CircleGauge, index: '01' },
  { id: 'sql', path: '/app/sql', label: 'SQL 控制台', icon: TerminalSquare, index: '02' },
  { id: 'activity', path: '/app/activity', label: '实时执行', icon: Activity, index: '03' },
  { id: 'release', path: '/app/release', label: '发版日志', icon: FileClock, index: '04' },
  { id: 'connections', path: '/app/connections', label: '连接管理', icon: Cable, index: '05' },
  { id: 'settings', path: '/app/settings', label: '设置', icon: Settings, index: '06' },
]

function routeFor(path: string): Route | 'not-found' {
  if (path === '/app/' || path === '/app' || path === '/app/overview') return 'overview'
  return routes.find((item) => item.path === path)?.id ?? 'not-found'
}

type Navigate = ReturnType<typeof usePathname>['navigate']
function InternalLink({ to, navigate, children, ...attributes }: { to: string; navigate: Navigate; children: ReactNode } & Omit<AnchorHTMLAttributes<HTMLAnchorElement>, 'href' | 'onClick'>) {
  return <a {...attributes} href={to} onClick={(event) => navigate(event, to)}>{children}</a>
}

export function App({ api }: { api: ApiClient }) {
  const { path, navigate } = usePathname()
  const { theme, toggle } = useTheme()
  const [runtime, setRuntime] = useState<RuntimeState>({ kind: 'loading' })
  const [defaultConnection, setDefaultConnection] = useState<SafeConnection | undefined>()
  const [connectionLookup, setConnectionLookup] = useState<'loading' | 'ready' | 'error'>('loading')
  const requestRef = useRef<AbortController | null>(null)
  const previousStreamStatus = useRef('connecting')
  const active = routeFor(path)

  const refreshSnapshot = useCallback(async (mode: 'loading' | 'reconnecting' = 'loading') => {
    requestRef.current?.abort()
    const controller = new AbortController()
    requestRef.current = controller
    setRuntime((previous) => {
      const value = 'value' in previous ? previous.value : undefined
      return mode === 'loading' || !value ? { kind: 'loading' } : { kind: 'reconnecting', value }
    })
    setConnectionLookup('loading')
    try {
      const [value, connections] = await Promise.all([api.runtime(controller.signal), api.listConnections(controller.signal)])
      if (controller.signal.aborted) return
      setRuntime({ kind: 'ready', value })
      setDefaultConnection(connections.connections.find((connection) => connection.isDefault))
      setConnectionLookup('ready')
    } catch (error: unknown) {
      if (controller.signal.aborted) return
      const safe = error as { message?: string; correlationId?: string }
      setRuntime((previous) => ({ kind: 'error', message: safe.message ?? '无法读取运行状态。', correlationId: safe.correlationId, value: 'value' in previous ? previous.value : undefined }))
      setDefaultConnection(undefined)
      setConnectionLookup('error')
    } finally {
      if (requestRef.current === controller) requestRef.current = null
    }
  }, [api])

  const stream = useEventStream({ resync: () => refreshSnapshot('reconnecting') })
  useEffect(() => {
    void refreshSnapshot('loading')
    return () => requestRef.current?.abort()
  }, [refreshSnapshot])

  useEffect(() => {
    const previous = previousStreamStatus.current
    previousStreamStatus.current = stream.status
    if (stream.status === 'reconnecting') {
      setRuntime((current) => current.kind === 'ready' ? { kind: 'stale', value: current.value } : current)
    } else if (stream.status === 'resyncing') {
      setRuntime((current) => 'value' in current && current.value ? { kind: 'reconnecting', value: current.value } : current)
    } else if (stream.status === 'connected' && previous === 'reconnecting') {
      void refreshSnapshot('reconnecting')
    }
  }, [refreshSnapshot, stream.status])

  const runtimeValue = 'value' in runtime ? runtime.value : undefined
  return <div className="app-shell">
    <a className="skip-link" href="#main-content">跳到主要内容</a>
    <aside className="app-sidebar">
      <div className="brand"><div className="brand-mark" aria-hidden="true"><Database size={21} strokeWidth={1.7} /></div><div><strong>DM7</strong><span>DATABASE CONTROL</span></div></div>
      <div className="instrument-label"><span>LOCAL INSTRUMENT</span><span className="online-dot" aria-hidden="true" /></div>
      <nav aria-label="主导航">{routes.map(({ id, path: to, label, icon: Icon, index }) => <InternalLink key={id} to={to} navigate={navigate} aria-label={label} aria-current={active === id ? 'page' : undefined}><span className="nav-index">{index}</span><Icon size={18} strokeWidth={1.65} aria-hidden="true" /><span>{label}</span></InternalLink>)}</nav>
      <div className="sidebar-foot"><ShieldCheck size={17} aria-hidden="true" /><span>本地回环隔离</span></div>
    </aside>
    <div className="app-stage">
      <header className="status-bar">
        <div className="status-cluster"><span className="status-key">当前连接</span>{runtime.kind === 'loading' ? <span>读取中…</span> : runtime.kind === 'error' ? <StatusBadge tone="danger">状态异常</StatusBadge> : runtime.kind === 'stale' || runtime.kind === 'reconnecting' ? <StatusBadge tone="warning">连接状态已过期</StatusBadge> : runtime.value.connections === 0 ? <StatusBadge tone="warning">尚未配置连接</StatusBadge> : connectionLookup === 'loading' ? <StatusBadge>正在确认默认连接</StatusBadge> : connectionLookup === 'error' ? <StatusBadge tone="warning">无法确认默认连接</StatusBadge> : !defaultConnection ? <StatusBadge tone="warning">未选择默认连接</StatusBadge> : <StatusBadge tone="success">{defaultConnection.name} · {defaultConnection.connected ? '已连接' : '就绪'}</StatusBadge>}</div>
        <div className="status-metrics"><span><small>SESSION</small><code>{runtimeValue?.sessionShortId ?? '————————'}</code></span><span><small>VERSION</small><code>{runtimeValue?.currentVersion ?? '————'}</code></span><span><small>RUNNING</small><strong>{runtimeValue ? `${runtimeValue.runningCount} 个任务` : '—'}</strong></span></div>
        <button className="icon-button" aria-label={theme === 'dark' ? '切换为浅色主题' : '切换为深色主题'} onClick={toggle}>{theme === 'dark' ? <Sun size={18} /> : <Moon size={18} />}</button>
      </header>
      <main id="main-content" tabIndex={-1}>
        <RuntimeFeedback state={runtime} streamStatus={stream.status} onRetry={() => void refreshSnapshot('loading')} />
        <RouteContent active={active} runtime={runtimeValue} runtimeStatus={runtime.kind} theme={theme} onThemeToggle={toggle} navigate={navigate} />
      </main>
    </div>
    <ToastRegion />
  </div>
}

function RuntimeFeedback({ state, streamStatus, onRetry }: { state: RuntimeState; streamStatus: StreamStatus; onRetry(): void }) {
  if (state.kind === 'loading') return <div className="runtime-feedback"><LoadingSkeleton label="正在读取运行状态" /></div>
  if (state.kind === 'error') return <div className="runtime-feedback"><InlineError message={state.message} correlationId={state.correlationId} onRetry={onRetry} /></div>
  if (streamStatus === 'connecting') return <div className="runtime-feedback"><LoadingSkeleton label="实时通道连接中" /></div>
  if (streamStatus === 'resyncing') return <div className="runtime-feedback"><LoadingSkeleton label="正在重新同步" /></div>
  if (streamStatus === 'reconnecting') return <button className="refresh-runtime runtime-stale" aria-label="刷新运行状态" onClick={onRetry}><RefreshCw size={14} aria-hidden="true" />实时状态可能已过期</button>
  if (state.kind === 'stale') return <button className="refresh-runtime runtime-stale" aria-label="刷新运行状态" onClick={onRetry}><RefreshCw size={14} aria-hidden="true" />实时状态可能已过期</button>
  if (state.kind === 'reconnecting') return <div className="runtime-feedback"><LoadingSkeleton label="正在重新同步" /></div>
  return <button className="refresh-runtime" aria-label="刷新运行状态" onClick={onRetry}><RefreshCw size={14} aria-hidden="true" />状态已同步</button>
}

function RouteContent({ active, runtime, runtimeStatus, theme, onThemeToggle, navigate }: { active: Route | 'not-found'; runtime?: RuntimeSummary; runtimeStatus: RuntimeState['kind']; theme: string; onThemeToggle(): void; navigate: Navigate }) {
  if (active === 'overview') return <OverviewPage runtime={runtime} runtimeStatus={runtimeStatus} navigate={navigate} />
  if (active === 'settings') return <SettingsPage theme={theme} onThemeToggle={onThemeToggle} />
  if (active === 'not-found') return <EmptyState title="页面未找到" description="该控制台路由不存在。请从左侧导航继续。" action={<InternalLink className="button-primary" to="/app/overview" navigate={navigate}>返回概览</InternalLink>} />
  const content = {
    sql: ['SQL 控制台', '编辑、审核并执行 DM7 SQL', TerminalSquare, '完整编辑器与结果表格将在下一阶段接入。'],
    activity: ['实时执行', '跟踪每个执行阶段与取消状态', Activity, '实时事件通道已就绪，此处将展示任务时间线。'],
    release: ['发版日志', '检查每个会话的 DDL / DML 发版记录', FileClock, '版本预览与安全导出操作即将接入。'],
    connections: ['连接管理', '本地管理驱动、账号与默认连接', Cable, '连接信息只在本地加密保存，编辑工作流将在后续阶段提供。'],
  }[active]!
  const Icon = content[2]
  return <><PageHeader eyebrow="WORKSPACE" title={content[0] as string} description={content[1] as string} /><section className="placeholder-panel"><Icon size={30} strokeWidth={1.35} aria-hidden="true" /><div><h2>功能通道已预留</h2><p>{content[3] as string}</p></div><span className="placeholder-code">MODULE / {active.toUpperCase()}</span></section></>
}

function OverviewPage({ runtime, runtimeStatus, navigate }: { runtime?: RuntimeSummary; runtimeStatus: RuntimeState['kind']; navigate: Navigate }) {
  const metrics = useMemo(() => [
    ['会话标识', runtime?.sessionShortId ?? '等待同步', 'CODEX SESSION'],
    ['活动版本', runtime?.currentVersion ?? '—', 'RELEASE TRACK'],
    ['运行任务', runtime ? String(runtime.runningCount) : '—', 'ACTIVE JOBS'],
    ['已配置连接', runtime ? String(runtime.connections) : '—', 'LOCAL PROFILES'],
  ], [runtime])
  return <><PageHeader eyebrow="CONTROL OVERVIEW" title="数据库运行概览" description="当前 Codex 会话的 DM7 连接、执行和发版基线。" />
    <section className="metric-grid" aria-label="运行摘要">{metrics.map(([label, value, code]) => <article key={label}><div className="metric-index">{code}</div><p>{label}</p><strong>{value}</strong></article>)}</section>
    <section className="overview-grid"><article className="health-panel"><div className="section-heading"><span>RUNTIME HEALTH</span>{runtimeStatus === 'ready' ? <StatusBadge tone="success">本地服务已就绪</StatusBadge> : runtimeStatus === 'error' ? <StatusBadge tone="danger">服务状态未知</StatusBadge> : runtimeStatus === 'stale' || runtimeStatus === 'reconnecting' ? <StatusBadge tone="warning">数据已过期</StatusBadge> : <StatusBadge tone="warning">等待运行状态</StatusBadge>}</div><div className="health-lines"><p><Blocks size={18} /> <span><strong>DM7 Codex Plugin</strong><small>后端、MCP 与控制台同一进程</small></span></p><p><ShieldCheck size={18} /><span><strong>安全边界</strong><small>127.0.0.1 回环 · 同源会话验证</small></span></p></div></article>
      <article className="next-action"><span className="eyebrow">NEXT ACTION</span><PlaySquare size={28} /><h2>{runtime?.connections ? '打开 SQL 控制台' : '配置第一个连接'}</h2><p>{runtime?.connections ? '使用已保存的连接执行查询。' : '驱动路径和密码仅在本地处理。'}</p><InternalLink to={runtime?.connections ? '/app/sql' : '/app/connections'} navigate={navigate}>继续 <span aria-hidden="true">→</span></InternalLink></article></section>
  </>
}

function SettingsPage({ theme, onThemeToggle }: { theme: string; onThemeToggle(): void }) {
  return <><PageHeader eyebrow="SYSTEM PARAMETERS" title="设置" description="控制显示偏好，检查本地运行基线与数据边界。" />
    <div className="settings-grid"><section className="settings-section"><div className="section-heading"><span>APPEARANCE</span><StatusBadge>{theme === 'dark' ? '深色' : '浅色'}</StatusBadge></div><h2>界面主题</h2><p>主题是浏览器中唯一保存的偏好。首次打开时跟随操作系统。</p><button className="theme-control" onClick={onThemeToggle}>{theme === 'dark' ? <Sun size={18} /> : <Moon size={18} />}切换为{theme === 'dark' ? '浅色' : '深色'}主题</button></section>
      <section className="settings-section"><div className="section-heading"><span>RUNTIME</span><StatusBadge tone="success">已启用</StatusBadge></div><dl className="parameter-list"><div><dt>插件版本</dt><dd><code>0.1.0</code></dd></div><div><dt>Java 运行基线</dt><dd>17+</dd></div><div><dt>默认结果上限</dt><dd>1,000 行 / 10 MiB</dd></div><div><dt>请求体上限</dt><dd>1 MiB</dd></div></dl></section>
      <section className="settings-section wide"><div className="section-heading"><span>PROTECTED STORAGE</span><ShieldCheck size={18} /></div><h2>凭据加密存储</h2><p>连接密码由本机密钥加密保存。会话日志与导出物位于 Codex 插件数据目录（受保护）。本地回环隔离负责限制网络访问，不等同于存储加密。控制台不显示绝对路径，也不在浏览器持久化 SQL、连接或凭据。</p></section></div>
  </>
}
