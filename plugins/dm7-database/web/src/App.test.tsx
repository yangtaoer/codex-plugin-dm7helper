import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'
import type { ApiClient, RuntimeSummary } from './api/types'
import './styles.css'

const stream = vi.hoisted(() => ({ status: 'connected', options: undefined as undefined | { resync?: () => Promise<void> } }))
vi.mock('./hooks/useEventStream', () => ({
  useEventStream: (options: { resync?: () => Promise<void> }) => { stream.options = options; return { status: stream.status, events: [] } },
}))

const runtime: RuntimeSummary = { sessionShortId: '019f4a71中文', currentVersion: 'v001', runningCount: 2, connections: 1 }
const api = (overrides: Partial<ApiClient> = {}): ApiClient => ({ runtime: vi.fn().mockResolvedValue(runtime), listConnections: vi.fn().mockResolvedValue({ connections: [] }), ...overrides } as ApiClient)

describe('DM7 console shell', () => {
  afterEach(() => { vi.restoreAllMocks(); stream.status = 'connected'; stream.options = undefined })

  it('renders exactly six destinations and the active runtime summary', async () => {
    render(<App api={api()} />)
    for (const name of ['概览', 'SQL 控制台', '实时执行', '发版日志', '连接管理', '设置']) {
      expect(screen.getByRole('link', { name })).toBeTruthy()
    }
    expect(await screen.findByText('019f4a71中文')).toBeTruthy()
    expect(screen.getByText('v001')).toBeTruthy()
    expect(screen.getByText('2 个任务')).toBeTruthy()
  })

  it('navigates without full reload and marks the active direct route', async () => {
    history.replaceState(null, '', '/app/settings')
    render(<App api={api()} />)
    expect(screen.getByRole('link', { name: '设置' }).getAttribute('aria-current')).toBe('page')
    const sql = screen.getByRole('link', { name: 'SQL 控制台' })
    fireEvent.click(sql)
    expect(location.pathname).toBe('/app/sql')
    expect(sql.getAttribute('aria-current')).toBe('page')
  })

  it('responds to browser back/forward and preserves the shell for unknown routes', async () => {
    history.replaceState(null, '', '/app/overview')
    render(<App api={api()} />)
    fireEvent.click(screen.getByRole('link', { name: '设置' }))
    history.back()
    window.dispatchEvent(new PopStateEvent('popstate'))
    await waitFor(() => expect(screen.getByRole('link', { name: '概览' }).getAttribute('aria-current')).toBe('page'))
    history.pushState(null, '', '/app/not-a-route')
    window.dispatchEvent(new PopStateEvent('popstate'))
    expect(await screen.findByRole('heading', { name: '页面未找到' })).toBeTruthy()
    expect(screen.getByRole('navigation', { name: '主导航' })).toBeTruthy()
  })

  it('routes overview and not-found CTAs through History without consuming modifier clicks', async () => {
    render(<App api={api()} />)
    const next = await screen.findByRole('link', { name: /继续/ })
    next.setAttribute('target', '_blank')
    const modified = new MouseEvent('click', { bubbles: true, cancelable: true, ctrlKey: true })
    next.dispatchEvent(modified)
    expect(modified.defaultPrevented).toBe(false)
    expect(location.pathname).toBe('/app/')
    next.removeAttribute('target')
    fireEvent.click(next)
    expect(location.pathname).toBe('/app/sql')

    history.pushState(null, '', '/app/missing')
    window.dispatchEvent(new PopStateEvent('popstate'))
    const back = await screen.findByRole('link', { name: '返回概览' })
    fireEvent.click(back)
    expect(location.pathname).toBe('/app/overview')
  })

  it('shows loading, no-connection, failure and retry states truthfully', async () => {
    let resolve!: (value: RuntimeSummary) => void
    const pending = new Promise<RuntimeSummary>((done) => { resolve = done })
    const runtimeCall = vi.fn().mockReturnValueOnce(pending).mockRejectedValueOnce(Object.assign(new Error('安全错误'), { correlationId: 'corr-123' })).mockResolvedValue({ ...runtime, connections: 0 })
    render(<App api={api({ runtime: runtimeCall })} />)
    expect(screen.getByText('正在读取运行状态')).toBeTruthy()
    resolve(runtime)
    await screen.findAllByText('v001')
    fireEvent.click(screen.getByRole('button', { name: '刷新运行状态' }))
    expect(await screen.findByText('安全错误')).toBeTruthy()
    expect(screen.getByText(/corr-123/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByText('尚未配置连接')).toBeTruthy()
  })

  it('does not claim runtime health before the runtime request succeeds', () => {
    render(<App api={api({ runtime: vi.fn(() => new Promise<RuntimeSummary>(() => undefined)) })} />)
    expect(screen.queryByText('本地服务已就绪')).toBeNull()
    expect(screen.getByText('等待运行状态')).toBeTruthy()
  })

  it('distinguishes configured profiles from a selected default connection', async () => {
    render(<App api={api({ listConnections: vi.fn().mockResolvedValue({ connections: [{ id: '1', name: '备用库', configured: true, connected: false, urlSummary: 'jdbc:dm7://…', isDefault: false }] }) })} />)
    expect(await screen.findByText('未选择默认连接')).toBeTruthy()
  })

  it('uses OS theme once, persists only theme, and toggles accessibly', () => {
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
    render(<App api={api()} />)
    expect(document.documentElement.dataset.theme).toBe('dark')
    fireEvent.click(screen.getByRole('button', { name: '切换为浅色主题' }))
    expect(document.documentElement.dataset.theme).toBe('light')
    expect(document.documentElement.getAttribute('style')).toBeNull()
    expect(Object.keys(localStorage)).toEqual(['dm7-console-theme'])
    expect(localStorage.getItem('dm7-console-theme')).toBe('light')
  })

  it('prefers a saved theme over OS preference without persisting runtime data', () => {
    localStorage.setItem('dm7-console-theme', 'light')
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
    render(<App api={api()} />)
    expect(document.documentElement.dataset.theme).toBe('light')
    expect(Object.keys(localStorage)).toEqual(['dm7-console-theme'])
  })

  it('keeps the document clipped at narrow widths while component tables own scrolling', () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 390 })
    render(<App api={api({ runtime: vi.fn(() => new Promise<RuntimeSummary>(() => undefined)) })} />)
    expect(document.documentElement.scrollWidth).toBeLessThanOrEqual(window.innerWidth)
    expect(screen.getByRole('main')).toBeTruthy()
  })

  it('provides a keyboard skip link and semantic main landmark', () => {
    render(<App api={api()} />)
    expect(screen.getByRole('link', { name: '跳到主要内容' }).getAttribute('href')).toBe('#main-content')
    expect(screen.getByRole('main').id).toBe('main-content')
  })

  it('describes loopback isolation separately from encrypted credential storage', async () => {
    render(<App api={api()} />)
    expect(screen.getByText('本地回环隔离')).toBeTruthy()
    fireEvent.click(screen.getByRole('link', { name: '设置' }))
    expect(await screen.findByText('凭据加密存储')).toBeTruthy()
  })

  it('marks runtime state stale during SSE loss and authoritatively refreshes it during recovery', async () => {
    const runtimeCall = vi.fn()
      .mockResolvedValueOnce(runtime)
      .mockResolvedValueOnce({ ...runtime, currentVersion: 'v002', runningCount: 0 })
    const listConnections = vi.fn().mockResolvedValue({ connections: [] })
    const client = api({ runtime: runtimeCall, listConnections })
    const { rerender } = render(<App api={client} />)
    await screen.findAllByText('v001')

    stream.status = 'reconnecting'
    rerender(<App api={client} />)
    expect(await screen.findByText('实时状态可能已过期')).toBeTruthy()
    expect(screen.queryByText('状态已同步')).toBeNull()

    stream.status = 'resyncing'
    rerender(<App api={client} />)
    expect(await screen.findByText('正在重新同步')).toBeTruthy()
    await act(async () => { await stream.options?.resync?.() })
    expect(await screen.findAllByText('v002')).toHaveLength(2)
    expect(screen.getByText('0 个任务')).toBeTruthy()
    expect(screen.queryByText('状态已同步')).toBeNull()
    expect(screen.getByText('正在重新同步')).toBeTruthy()
    stream.status = 'connected'
    rerender(<App api={client} />)
    expect(await screen.findByText('状态已同步')).toBeTruthy()
  })

  it('does not claim synchronization before the initial live stream opens', async () => {
    stream.status = 'connecting'
    render(<App api={api()} />)
    await screen.findAllByText('v001')
    expect(screen.getByText('实时通道连接中')).toBeTruthy()
    expect(screen.queryByText('状态已同步')).toBeNull()
  })

  it('never renders successful health after authoritative recovery failures and returns to ready only after success', async () => {
    const runtimeCall = vi.fn()
      .mockResolvedValueOnce(runtime)
      .mockRejectedValueOnce(new Error('重新同步失败'))
      .mockRejectedValueOnce(new Error('实时流恢复后刷新失败'))
      .mockResolvedValueOnce({ ...runtime, currentVersion: 'v003', runningCount: 0 })
    const client = api({ runtime: runtimeCall })
    const { rerender } = render(<App api={client} />)
    expect(await screen.findByText('本地服务已就绪')).toBeTruthy()

    stream.status = 'reconnecting'
    rerender(<App api={client} />)
    expect(await screen.findByText('数据已过期')).toBeTruthy()

    stream.status = 'resyncing'
    rerender(<App api={client} />)
    await act(async () => { await stream.options?.resync?.() })
    expect(await screen.findByText('服务状态未知')).toBeTruthy()
    expect(screen.queryByText('本地服务已就绪')).toBeNull()
    expect(screen.getByText('状态异常')).toBeTruthy()

    stream.status = 'reconnecting'
    rerender(<App api={client} />)
    stream.status = 'connected'
    rerender(<App api={client} />)
    expect(await screen.findByText('实时流恢复后刷新失败')).toBeTruthy()
    expect(screen.getByText('服务状态未知')).toBeTruthy()
    expect(screen.queryByText('本地服务已就绪')).toBeNull()

    stream.status = 'reconnecting'
    rerender(<App api={client} />)
    stream.status = 'connected'
    rerender(<App api={client} />)
    expect(await screen.findAllByText('v003')).toHaveLength(2)
    expect(screen.getByText('本地服务已就绪')).toBeTruthy()
    expect(screen.queryByText('服务状态未知')).toBeNull()
  })
})
