import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ApiClient, SafeConnection } from '../api/types'
import { ConnectionsPage } from './ConnectionsPage'
import { ApiError } from '../api/client'

const profile: SafeConnection = {
  id: 'c1', name: '生产主库', driverFileName: 'DmJdbcDriver.jar', driverSha256: 'a'.repeat(64),
  configured: true, connected: false, hasPassword: true, driverClass: 'dm.jdbc.driver.DmDriver',
  jdbcUrl: 'jdbc:dm7://db.internal:5236?dbname=PROD', urlSummary: 'jdbc:dm7://db.internal:5236?dbname=PROD',
  username: 'operator', schema: '业务模式', connectTimeoutSeconds: 10, socketTimeoutSeconds: 30,
  queryTimeoutSeconds: 60, maxRows: 1000, maxBytes: 10485760, isDefault: true,
}

const client = (overrides: Partial<ApiClient> = {}) => ({
  listConnections: vi.fn().mockResolvedValue({ connections: [] }),
  createConnection: vi.fn(), updateConnection: vi.fn(), removeConnection: vi.fn().mockResolvedValue({ deleted: true }),
  setDefaultConnection: vi.fn(), testConnection: vi.fn(), diagnoseUrl: vi.fn().mockResolvedValue({ urlSummary: '', warnings: [] }),
  ...overrides,
} as unknown as ApiClient)

afterEach(() => vi.useRealTimers())

describe('ConnectionsPage', () => {
  it('renders loading, empty onboarding and retryable list failure truthfully', async () => {
    const api = client()
    render(<ConnectionsPage api={api} />)
    expect(screen.getByText('正在读取连接配置')).toBeTruthy()
    expect(await screen.findByText('还没有数据库连接')).toBeTruthy()
    expect(screen.getAllByRole('button', { name: '新增连接' }).length).toBeGreaterThan(0)
  })

  it('shows safe retryable API failures with correlation details', async () => {
    const listConnections = vi.fn().mockRejectedValueOnce(new ApiError(422, 'VALIDATION', '连接配置无效。', 'corr-safe')).mockResolvedValue({ connections: [] })
    render(<ConnectionsPage api={client({ listConnections })} />)
    expect(await screen.findByRole('alert')).toHaveProperty('textContent', expect.stringContaining('连接配置无效'))
    fireEvent.click(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByText('还没有数据库连接')).toBeTruthy()
    expect(listConnections).toHaveBeenCalledTimes(2)
  })

  it('shows safe card facts and treats untested connected=false as 未检测', async () => {
    render(<ConnectionsPage api={client({ listConnections: vi.fn().mockResolvedValue({ connections: [profile] }) })} />)
    expect(await screen.findByText('生产主库')).toBeTruthy()
    expect(screen.getByText('默认连接')).toBeTruthy()
    expect(screen.getByText('凭据已配置')).toBeTruthy()
    expect(screen.getByText('未检测')).toBeTruthy()
    expect(document.body.textContent).not.toContain('driverJar')
  })

  it('validates create, focuses first invalid field, and clears password from the DOM after save', async () => {
    const createConnection = vi.fn().mockResolvedValue({ ...profile, id: 'created' })
    const api = client({ createConnection, listConnections: vi.fn().mockResolvedValue({ connections: [] }) })
    render(<ConnectionsPage api={api} />)
    await screen.findByText('还没有数据库连接')
    fireEvent.click(screen.getAllByRole('button', { name: '新增连接' })[0])
    fireEvent.click(screen.getByRole('button', { name: '保存连接' }))
    expect(screen.getByLabelText(/^连接名称/)).toBe(document.activeElement)
    fireEvent.change(screen.getByLabelText(/^连接名称/), { target: { value: '新连接' } })
    fireEvent.change(screen.getByLabelText(/^驱动 JAR 本地路径/), { target: { value: 'C:\\drivers\\dm.jar' } })
    fireEvent.change(screen.getByLabelText(/^JDBC URL/), { target: { value: 'jdbc:dm7://localhost:5236?dbname=LOCAL' } })
    fireEvent.change(screen.getByLabelText(/^用户名/), { target: { value: 'local-user' } })
    fireEvent.change(screen.getByLabelText('密码'), { target: { value: 'temporary-password' } })
    fireEvent.click(screen.getByRole('button', { name: '保存连接' }))
    await waitFor(() => expect(createConnection).toHaveBeenCalledTimes(1))
    expect(createConnection.mock.calls[0][0]).toMatchObject({ name: '新连接', password: 'temporary-password' })
    expect(screen.queryByDisplayValue('temporary-password')).toBeNull()
  })

  it('preserves hidden fields on edit and sends explicit password clear', async () => {
    const updateConnection = vi.fn().mockResolvedValue({ ...profile, hasPassword: false })
    render(<ConnectionsPage api={client({ listConnections: vi.fn().mockResolvedValue({ connections: [profile] }), updateConnection })} />)
    await screen.findByText('生产主库')
    fireEvent.click(screen.getByRole('button', { name: '编辑生产主库' }))
    expect(screen.getByText('当前驱动：DmJdbcDriver.jar')).toBeTruthy()
    expect(screen.getByText(/留空将保留当前 URL/)).toBeTruthy()
    fireEvent.click(screen.getByLabelText('清除已保存密码'))
    fireEvent.click(screen.getByRole('button', { name: '保存更改' }))
    await waitFor(() => expect(updateConnection).toHaveBeenCalled())
    expect(updateConnection.mock.calls[0][1]).toMatchObject({ clearPassword: true })
    expect(updateConnection.mock.calls[0][1]).not.toHaveProperty('driverJar')
    expect(updateConnection.mock.calls[0][1]).not.toHaveProperty('jdbcUrl')
    expect(updateConnection.mock.calls[0][1]).not.toHaveProperty('password')
  })

  it('copy requires safe fields to be re-entered and diagnostics are debounced without rewriting input', async () => {
    vi.useFakeTimers()
    const diagnoseUrl = vi.fn().mockResolvedValue({ urlSummary: 'safe', warnings: ['路径形式可能被旧版驱动忽略，请使用 dbname= 或单独 schema=。'] })
    render(<ConnectionsPage api={client({ listConnections: vi.fn().mockResolvedValue({ connections: [profile] }), diagnoseUrl })} />)
    await act(async () => { await Promise.resolve() })
    fireEvent.click(screen.getByRole('button', { name: '复制生产主库' }))
    expect(screen.getByLabelText(/^连接名称/)).toHaveProperty('value', '生产主库 - 副本')
    expect(screen.getByLabelText(/^驱动 JAR 本地路径/)).toHaveProperty('value', '')
    expect(screen.getByLabelText(/^JDBC URL/)).toHaveProperty('value', '')
    expect(screen.getByText(/凭据不会复制/)).toBeTruthy()
    fireEvent.change(screen.getByLabelText(/^JDBC URL/), { target: { value: 'jdbc:dm7://host:5236/SYSTEM' } })
    await act(async () => { vi.advanceTimersByTime(450); await Promise.resolve() })
    expect(diagnoseUrl).toHaveBeenCalledTimes(1)
    expect(screen.getByLabelText(/^JDBC URL/)).toHaveProperty('value', 'jdbc:dm7://host:5236/SYSTEM')
    expect(screen.getByText(/路径形式可能被旧版驱动忽略/)).toBeTruthy()
    vi.useRealTimers()
  })

  it('requires a fresh password when copying a source with saved credentials', async () => {
    const createConnection=vi.fn()
    render(<ConnectionsPage api={client({ listConnections:vi.fn().mockResolvedValue({connections:[profile]}),createConnection })}/>)
    await screen.findByText('生产主库');fireEvent.click(screen.getByRole('button',{name:'复制生产主库'}))
    fireEvent.change(screen.getByLabelText(/^驱动 JAR/),{target:{value:'C:\\driver.jar'}})
    fireEvent.change(screen.getByLabelText(/^JDBC URL/),{target:{value:'jdbc:dm7://copy'}})
    fireEvent.click(screen.getByRole('button',{name:'保存连接'}))
    expect(createConnection).not.toHaveBeenCalled();expect(screen.getByLabelText('密码')).toBe(document.activeElement)
    expect(screen.getByText('请输入新密码')).toBeTruthy()
  })

  it('allows passwordless copy when the source has no saved credential',async()=>{
    const source={...profile,hasPassword:false};const createConnection=vi.fn().mockResolvedValue({...source,id:'copy'})
    render(<ConnectionsPage api={client({listConnections:vi.fn().mockResolvedValue({connections:[source]}),createConnection})}/>)
    await screen.findByText('生产主库');fireEvent.click(screen.getByRole('button',{name:'复制生产主库'}));fireEvent.change(screen.getByLabelText(/^驱动 JAR/),{target:{value:'C:\\driver.jar'}});fireEvent.change(screen.getByLabelText(/^JDBC URL/),{target:{value:'jdbc:dm7://copy'}});fireEvent.click(screen.getByRole('button',{name:'保存连接'}))
    await waitFor(()=>expect(createConnection).toHaveBeenCalled());expect(createConnection.mock.calls[0][0]).not.toHaveProperty('password')
  })

  it('tests, changes default and confirms destructive deletion', async () => {
    const secondary = { ...profile, id: 'c2', name: '报表库', isDefault: false, hasPassword: false }
    const testConnection = vi.fn().mockResolvedValue({ success: true, latencyMs: 18, driverVersion: '7', serverVersion: 'DM 7', actualUser: 'OP', actualSchema: '业务模式', chineseRoundTrip: true, restartRequired: false, warnings: [] })
    const setDefaultConnection = vi.fn().mockResolvedValue({ ...secondary, isDefault: true })
    const removeConnection = vi.fn().mockResolvedValue({ deleted: true, defaultConnectionId: 'c2' })
    const listConnections = vi.fn().mockResolvedValueOnce({ connections: [profile, secondary] }).mockResolvedValue({ connections: [{ ...secondary, isDefault: true }] })
    render(<ConnectionsPage api={client({ listConnections, testConnection, setDefaultConnection, removeConnection })} />)
    await screen.findByText('报表库')
    fireEvent.click(screen.getByRole('button', { name: '测试生产主库' }))
    expect(await screen.findByText('中文往返正常')).toBeTruthy()
    expect(screen.getByText(/18 ms/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '设报表库为默认' }))
    await waitFor(() => expect(setDefaultConnection).toHaveBeenCalledWith('c2'))
    fireEvent.click(screen.getByRole('button', { name: '删除报表库' }))
    expect(screen.getByRole('dialog').textContent).toContain('报表库')
    fireEvent.click(screen.getByRole('button', { name: '确认删除' }))
    await waitFor(() => expect(removeConnection).toHaveBeenCalledWith('c2', {}))
  })

  it('renders complete safe test diagnostics for failure and warnings', async()=>{
    const testConnection=vi.fn().mockResolvedValue({success:false,latencyMs:0,driverVersion:'',serverVersion:'',actualUser:'',actualSchema:'',chineseRoundTrip:false,restartRequired:true,warnings:['DM7 路径形式警告：请使用 dbname=','驱动需要重新加载']})
    render(<ConnectionsPage api={client({listConnections:vi.fn().mockResolvedValue({connections:[profile]}),testConnection})}/>)
    await screen.findByText('生产主库');fireEvent.click(screen.getByRole('button',{name:'测试生产主库'}))
    expect(await screen.findByText('中文往返未通过')).toBeTruthy();expect(screen.getByText(/DM7 路径形式警告/)).toBeTruthy();expect(screen.getByText('驱动需要重新加载')).toBeTruthy()
    expect(screen.getByText('需要重启插件')).toBeTruthy();expect(document.body.textContent).not.toContain(' /  / ');expect(document.body.textContent).not.toContain(' ·  ')
  })

  it('keeps mutation errors inside the drawer with focus and correlation disclosure',async()=>{
    const updateConnection=vi.fn().mockRejectedValue(new ApiError(409,'CONFLICT','连接名称已存在。','corr-drawer'))
    render(<ConnectionsPage api={client({listConnections:vi.fn().mockResolvedValue({connections:[profile]}),updateConnection})}/>)
    await screen.findByText('生产主库');fireEvent.click(screen.getByRole('button',{name:'编辑生产主库'}));fireEvent.change(screen.getByLabelText(/^连接名称/),{target:{value:'仍保留的名称'}})
    fireEvent.click(screen.getByRole('button',{name:'保存更改'}))
    const alert=await screen.findByRole('alert');expect(alert.closest('[role="dialog"]')).toBeTruthy();expect(alert).toBe(document.activeElement)
    expect(alert.textContent).toContain('连接名称已存在');expect(alert.textContent).toContain('corr-drawer');expect(screen.getByLabelText(/^连接名称/)).toHaveProperty('value','仍保留的名称')
  })

  it('Escape closes only the unsaved confirmation and returns focus without reopening',async()=>{
    render(<ConnectionsPage api={client({listConnections:vi.fn().mockResolvedValue({connections:[profile]})})}/>)
    await screen.findByText('生产主库');fireEvent.click(screen.getByRole('button',{name:'编辑生产主库'}));const name=screen.getByLabelText(/^连接名称/);fireEvent.change(name,{target:{value:'修改'}});name.focus()
    fireEvent.keyDown(document,{key:'Escape'});expect(screen.getByRole('dialog',{name:'放弃未保存的更改？'})).toBeTruthy()
    fireEvent.keyDown(screen.getByRole('dialog',{name:'放弃未保存的更改？'}),{key:'Escape'})
    await waitFor(()=>expect(screen.queryByRole('dialog',{name:'放弃未保存的更改？'})).toBeNull());expect(screen.getByRole('dialog',{name:'编辑连接'})).toBeTruthy();expect(name).toBe(document.activeElement)
  })

  it('opens advanced disclosure before focusing an invalid numeric field',async()=>{
    render(<ConnectionsPage api={client()}/>);await screen.findByText('还没有数据库连接');fireEvent.click(screen.getAllByRole('button',{name:'新增连接'})[0])
    fireEvent.change(screen.getByLabelText(/^连接名称/),{target:{value:'边界'}});fireEvent.change(screen.getByLabelText(/^驱动 JAR/),{target:{value:'C:\\driver.jar'}});fireEvent.change(screen.getByLabelText(/^JDBC URL/),{target:{value:'jdbc:dm7://x'}});fireEvent.change(screen.getByLabelText(/^用户名/),{target:{value:'u'}})
    const rows=screen.getByLabelText('最大行数');fireEvent.change(rows,{target:{value:'0'}});fireEvent.click(screen.getByRole('button',{name:'保存连接'}))
    expect(screen.getByText('高级参数').closest('details')).toHaveProperty('open',true);expect(rows).toBe(document.activeElement)
  })

  it('requires an explicit replacement or no-default choice when deleting the default',async()=>{
    const secondary={...profile,id:'c2',name:'报表库',isDefault:false,hasPassword:false};const removeConnection=vi.fn().mockResolvedValue({deleted:true,defaultConnectionId:null})
    render(<ConnectionsPage api={client({listConnections:vi.fn().mockResolvedValue({connections:[profile,secondary]}),removeConnection})}/>)
    await screen.findByText('报表库');fireEvent.click(screen.getByRole('button',{name:'删除生产主库'}));const confirm=screen.getByRole('button',{name:'确认删除'});expect((confirm as HTMLButtonElement).disabled).toBe(true)
    expect(screen.getByLabelText('删除后的默认连接')).toBeTruthy();fireEvent.change(screen.getByLabelText('删除后的默认连接'),{target:{value:'c2'}});fireEvent.click(confirm)
    await waitFor(()=>expect(removeConnection).toHaveBeenCalledWith('c1',{replacementDefaultId:'c2'}))
  })

  it('sends the explicit leave-without-default deletion choice',async()=>{
    const secondary={...profile,id:'c2',name:'报表库',isDefault:false};const removeConnection=vi.fn().mockResolvedValue({deleted:true,defaultConnectionId:null})
    render(<ConnectionsPage api={client({listConnections:vi.fn().mockResolvedValue({connections:[profile,secondary]}),removeConnection})}/>);await screen.findByText('报表库');fireEvent.click(screen.getByRole('button',{name:'删除生产主库'}));fireEvent.change(screen.getByLabelText('删除后的默认连接'),{target:{value:'__none__'}});fireEvent.click(screen.getByRole('button',{name:'确认删除'}));await waitFor(()=>expect(removeConnection).toHaveBeenCalledWith('c1',{leaveWithoutDefault:true}))
  })

  it('prevents double submit and guards unsaved Escape close', async () => {
    let resolve!: (value: SafeConnection) => void
    const createConnection = vi.fn().mockReturnValue(new Promise<SafeConnection>((done) => { resolve = done }))
    render(<ConnectionsPage api={client({ createConnection })} />)
    await screen.findByText('还没有数据库连接')
    fireEvent.click(screen.getAllByRole('button', { name: '新增连接' })[0])
    fireEvent.change(screen.getByLabelText(/^连接名称/), { target: { value: '待保存' } })
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.getByRole('dialog', { name: '放弃未保存的更改？' })).toBeTruthy()
    fireEvent.click(screen.getAllByRole('button', { name: '取消' }).at(-1)!)
    fireEvent.change(screen.getByLabelText(/^驱动 JAR 本地路径/), { target: { value: 'C:\\driver.jar' } })
    fireEvent.change(screen.getByLabelText(/^JDBC URL/), { target: { value: 'jdbc:dm7://localhost' } })
    fireEvent.change(screen.getByLabelText(/^用户名/), { target: { value: 'user' } })
    const save = screen.getByRole('button', { name: '保存连接' })
    fireEvent.click(save); fireEvent.click(save)
    expect(createConnection).toHaveBeenCalledTimes(1)
    resolve({ ...profile, id: 'new' })
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '新增连接' })).toBeNull())
  })
})
