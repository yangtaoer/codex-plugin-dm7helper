import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { App } from './App'
import type { ApiClient, RuntimeSummary } from './api/types'
import './styles.css'

const runtime: RuntimeSummary = { sessionShortId: '019f4a71中文', currentVersion: 'v001', runningCount: 2, connections: 1 }
const api = (overrides: Partial<ApiClient> = {}): ApiClient => ({ runtime: vi.fn().mockResolvedValue(runtime), ...overrides } as ApiClient)

describe('DM7 console shell', () => {
  afterEach(() => vi.restoreAllMocks())

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
})
