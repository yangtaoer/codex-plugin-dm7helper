import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { ApiClient, ExecuteResult, ExecutionDetail, QueryResult, SafeConnection } from '../api/types'
import { MutationMessages, SqlConsolePage } from './SqlConsolePage'
import { ApiError } from '../api/client'

const connection: SafeConnection = { id: 'c1', name: '主库', configured: true, connected: false, hasPassword: true, urlSummary: 'jdbc:dm7://db:5236', isDefault: true }
const result: QueryResult = { executionId: 'e1', success: true, columns: [{ outputLabel: '中文列', originalLabel: '中文列', originalName: 'NAME', jdbcType: 12, typeName: 'VARCHAR' }], rows: [{ 中文列: '达梦数据库' }], truncated: false, returnedRows: 1, bytes: 12, elapsedMillis: 8, databaseFingerprint: 'abcdef123456', error: null }
const client = (overrides: Partial<ApiClient> = {}) => ({
  listConnections: vi.fn().mockResolvedValue({ connections: [connection] }), classifySql: vi.fn(), query: vi.fn(), execute: vi.fn(), cancelExecution: vi.fn(), ...overrides,
} as unknown as ApiClient)

describe('SqlConsolePage', () => {
  it('classifies and immediately runs a query with a client-known UUID', async () => {
    const classifySql=vi.fn().mockResolvedValue({ statementCount: 1, kinds: ['QUERY'], queryOnly: true, requiresPurpose: false, atomicAllowed: false })
    const query=vi.fn().mockImplementation((input)=>Promise.resolve({...result,executionId:input.executionId}))
    render(<SqlConsolePage api={client({ classifySql, query })} events={[]} streamStatus="connected" theme="light" initialSql={'SELECT NAME AS "中文列" FROM T'} />)
    await screen.findByRole('option', { name: /主库/ })
    fireEvent.click(screen.getByRole('button', { name: '执行全部' }))
    await waitFor(() => expect(query).toHaveBeenCalledTimes(1))
    expect(query.mock.calls[0][0].executionId).toMatch(/^[0-9a-f-]{36}$/)
    expect(await screen.findByText('达梦数据库')).toBeTruthy()
  })

  it('requires purpose and acknowledgement for mutations and disables atomic DDL', async () => {
    const execute=vi.fn()
    render(<SqlConsolePage api={client({ classifySql: vi.fn().mockResolvedValue({ statementCount: 1, kinds: ['DDL'], queryOnly: false, requiresPurpose: true, atomicAllowed: false }), execute })} events={[]} streamStatus="connected" theme="dark" initialSql="CREATE TABLE T(ID INT)" />)
    await screen.findByRole('option', { name: /主库/ })
    fireEvent.click(screen.getByRole('button', { name: '执行全部' }))
    expect(await screen.findByRole('dialog', { name: '确认修改操作' })).toBeTruthy()
    expect((screen.getByLabelText('原子执行') as HTMLInputElement).disabled).toBe(true)
    expect(screen.getByText(/TEST、MOCK、SEED、SAMPLE/)).toBeTruthy()
    expect((screen.getByRole('button', { name: '确认并执行' }) as HTMLButtonElement).disabled).toBe(true)
    fireEvent.change(screen.getByLabelText('用途'), { target: { value: 'MIGRATION' } })
    fireEvent.click(screen.getByLabelText('我已核对 SQL 与目标连接'))
    fireEvent.click(screen.getByRole('button', { name: '确认并执行' }))
    await waitFor(() => expect(execute).toHaveBeenCalledTimes(1))
  })

  it('blocks blank SQL and confirms clearing nonempty text', async () => {
    const api=client()
    const { rerender }=render(<SqlConsolePage api={api} events={[]} streamStatus="connected" theme="light" initialSql=" " />)
    expect((screen.getByRole('button', { name: '执行全部' }) as HTMLButtonElement).disabled).toBe(true)
    rerender(<SqlConsolePage api={api} events={[]} streamStatus="connected" theme="light" initialSql="select 1" />)
    await waitFor(()=>expect((screen.getByRole('button', { name: '清空编辑器' }) as HTMLButtonElement).disabled).toBe(false))
    fireEvent.click(screen.getByRole('button', { name: '清空编辑器' }))
    expect(await screen.findByRole('dialog', { name: '清空 SQL' })).toBeTruthy()
  })

  it('cancels only a known active execution and clears pending on a completion race', async () => {
    let resolveQuery!: (value:QueryResult)=>void
    const query=vi.fn().mockReturnValue(new Promise<QueryResult>(resolve=>{resolveQuery=resolve}))
    const cancelExecution=vi.fn().mockResolvedValue({executionId:'x',cancelRequested:false})
    const api=client({classifySql:vi.fn().mockResolvedValue({statementCount:1,kinds:['QUERY'],queryOnly:true,requiresPurpose:false,atomicAllowed:false}),query,cancelExecution})
    const {rerender}=render(<SqlConsolePage api={api} events={[]} streamStatus="connected" theme="light" initialSql="SELECT 1" />)
    await screen.findByRole('option',{name:/主库/});fireEvent.click(screen.getByRole('button',{name:'执行全部'}))
    await waitFor(()=>expect(query).toHaveBeenCalledTimes(1));const id=query.mock.calls[0][0].executionId
    rerender(<SqlConsolePage api={api} events={[{id:'1',executionId:id,status:'executing',timestamp:new Date().toISOString(),detail:'执行中'}]} streamStatus="connected" theme="light" initialSql="SELECT 1" />)
    await waitFor(()=>expect((screen.getByRole('button',{name:'取消执行'}) as HTMLButtonElement).disabled).toBe(false))
    fireEvent.click(screen.getByRole('button',{name:'取消执行'}));await waitFor(()=>expect(cancelExecution).toHaveBeenCalledWith(id))
    expect(await screen.findByText('任务可能已结束，正在重新查询状态。')).toBeTruthy()
    expect((screen.getByRole('button',{name:'取消执行'}) as HTMLButtonElement).disabled).toBe(false)
    resolveQuery(result)
  })
  it('freezes target, limits and editor against the visible execution snapshot', async () => {
    let resolveClassify!:(value:unknown)=>void
    const classifySql=vi.fn().mockReturnValue(new Promise(resolve=>{resolveClassify=resolve}))
    const query=vi.fn().mockImplementation((input)=>Promise.resolve({...result,executionId:input.executionId}))
    render(<SqlConsolePage api={client({classifySql,query})} events={[]} streamStatus="connected" theme="light" initialSql="SELECT 1"/>)
    await screen.findByRole('option',{name:/主库/});fireEvent.click(screen.getByRole('button',{name:'执行全部'}))
    await waitFor(()=>expect(classifySql).toHaveBeenCalled())
    expect((screen.getByLabelText('连接') as HTMLSelectElement).disabled).toBe(true)
    expect((screen.getByLabelText('最大行数') as HTMLInputElement).disabled).toBe(true)
    expect(screen.getByText(/执行快照.*主库.*1,000 行.*10,485,760 bytes.*60 秒/).textContent).toBeTruthy()
    expect(document.querySelector('.cm-content')?.getAttribute('contenteditable')).toBe('false')
    resolveClassify({statementCount:1,kinds:['QUERY'],queryOnly:true,requiresPurpose:false,atomicAllowed:false})
    expect(await screen.findByText('达梦数据库')).toBeTruthy()
    expect((screen.getByLabelText('连接') as HTMLSelectElement).disabled).toBe(false)
  })
  it('uses complete keyboard-operated ARIA tabs', async () => {
    render(<SqlConsolePage api={client()} events={[]} streamStatus="connected" theme="light" />)
    const tabs=screen.getAllByRole('tab'),first=tabs[0],second=tabs[1]
    expect(first.id).toBeTruthy();expect(first.getAttribute('aria-controls')).toBeTruthy();expect(first.tabIndex).toBe(0);expect(second.tabIndex).toBe(-1)
    first.focus();fireEvent.keyDown(first,{key:'ArrowRight'})
    expect(document.activeElement).toBe(second);expect(second.getAttribute('aria-selected')).toBe('true')
    const panel=document.getElementById(second.getAttribute('aria-controls')!)!
    expect(panel.getAttribute('role')).toBe('tabpanel');expect(panel.getAttribute('aria-labelledby')).toBe(second.id)
    fireEvent.keyDown(second,{key:'End'});expect(document.activeElement).toBe(tabs.at(-1))
    fireEvent.keyDown(tabs.at(-1)!,{key:'Home'});expect(document.activeElement).toBe(first)
  })
  it('clears cancel pending from the authoritative HTTP terminal response while SSE is disconnected', async () => {
    let resolveQuery!:(value:QueryResult)=>void
    const query=vi.fn().mockReturnValue(new Promise<QueryResult>(resolve=>{resolveQuery=resolve}))
    const cancelExecution=vi.fn().mockResolvedValue({executionId:'x',cancelRequested:true})
    const api=client({classifySql:vi.fn().mockResolvedValue({statementCount:1,kinds:['QUERY'],queryOnly:true,requiresPurpose:false,atomicAllowed:false}),query,cancelExecution})
    const rendered=render(<SqlConsolePage api={api} events={[]} streamStatus="reconnecting" theme="light" initialSql="SELECT 1"/>)
    await screen.findByRole('option',{name:/主库/});fireEvent.click(screen.getByRole('button',{name:'执行全部'}));await waitFor(()=>expect(query).toHaveBeenCalled())
    const id=query.mock.calls[0][0].executionId
    rendered.rerender(<SqlConsolePage api={api} events={[{id:'1',executionId:id,status:'executing',timestamp:new Date().toISOString(),detail:'执行中'}]} streamStatus="reconnecting" theme="light" initialSql="SELECT 1"/>)
    await waitFor(()=>expect((screen.getByRole('button',{name:'取消执行'}) as HTMLButtonElement).disabled).toBe(false));fireEvent.click(screen.getByRole('button',{name:'取消执行'}))
    expect(await screen.findByRole('button',{name:'正在请求取消'})).toBeTruthy()
    resolveQuery({...result,executionId:id})
    await screen.findByText('达梦数据库')
    expect((screen.getByRole('button',{name:'取消执行'}) as HTMLButtonElement).disabled).toBe(true)
  })
  it('renders an overall mutation failure once even with no statement results', () => {
    const error={correlationId:'overall-corr',phase:'CONNECTING',message:'连接失败',sqlState:'08001',errorCode:6002,restartRequired:true}
    const failed:ExecuteResult={executionId:'x',success:false,status:'FAILED',statements:[],elapsedMillis:7,databaseFingerprint:'unknown',error}
    const rendered=render(<MutationMessages result={failed}/>)
    const alert=screen.getByRole('alert');expect(alert.textContent).toContain('CONNECTING · 连接失败');expect(alert.textContent).toContain('08001');expect(alert.textContent).toContain('需要重启')
    expect(screen.getAllByText(/连接失败/)).toHaveLength(1)
    const statementError={...error,message:'第 1 条语句失败',sqlState:'42000',errorCode:7001}
    rendered.rerender(<MutationMessages result={{...failed,statements:[{index:0,kind:'DML',success:false,committed:false,rowCount:0,recorded:false,exclusionReason:null,commitBehavior:'rolled_back',elapsedMillis:7,error:statementError}]}}/>)
    expect(screen.getAllByRole('alert')).toHaveLength(2);expect(screen.getByText(/第 1 条语句失败/)).toBeTruthy();expect(screen.getByText(/SQLState 42000/)).toBeTruthy();expect(screen.getByText(/DB 7001/)).toBeTruthy()
  })
  it('starts a new execution after an SSE terminal and ignores the old blocking response', async () => {
    const pending:{resolve(value:QueryResult):void}[]=[]
    const query=vi.fn().mockImplementation(()=>new Promise<QueryResult>(resolve=>pending.push({resolve})))
    const api=client({classifySql:vi.fn().mockResolvedValue({statementCount:1,kinds:['QUERY'],queryOnly:true,requiresPurpose:false,atomicAllowed:false}),query})
    const rendered=render(<SqlConsolePage api={api} events={[]} streamStatus="reconnecting" theme="light" initialSql="SELECT 1"/>)
    await screen.findByRole('option',{name:/主库/});fireEvent.click(screen.getByRole('button',{name:'执行全部'}));await waitFor(()=>expect(query).toHaveBeenCalledTimes(1))
    const oldId=query.mock.calls[0][0].executionId
    rendered.rerender(<SqlConsolePage api={api} events={[{id:'2',executionId:oldId,status:'cancelled',timestamp:new Date().toISOString(),detail:'已取消'}]} streamStatus="reconnecting" theme="light" initialSql="SELECT 1"/>)
    await waitFor(()=>expect((screen.getByRole('button',{name:'执行全部'}) as HTMLButtonElement).disabled).toBe(false));fireEvent.click(screen.getByRole('button',{name:'执行全部'}));await waitFor(()=>expect(query).toHaveBeenCalledTimes(2))
    const newId=query.mock.calls[1][0].executionId
    pending[0].resolve({...result,executionId:oldId,rows:[{中文列:'旧结果'}]});await Promise.resolve()
    expect(screen.queryByText('旧结果')).toBeNull();expect(document.querySelector('.execution-snapshot')?.textContent).toContain(newId)
    pending[1].resolve({...result,executionId:newId,rows:[{中文列:'新结果'}]})
    expect(await screen.findByText('新结果')).toBeTruthy()
  })
  it('keeps a known execution locked and cancellable when query transport becomes uncertain', async () => {
    const getExecution=vi.fn().mockReturnValue(new Promise(()=>undefined)),cancelExecution=vi.fn().mockResolvedValue({executionId:'x',cancelRequested:true})
    const api=client({classifySql:vi.fn().mockResolvedValue({statementCount:1,kinds:['QUERY'],queryOnly:true,requiresPurpose:false,atomicAllowed:false}),query:vi.fn().mockRejectedValue(new ApiError(0,'NETWORK_ERROR','网络中断')),getExecution,cancelExecution})
    const rendered=render(<SqlConsolePage api={api} events={[]} streamStatus="reconnecting" theme="light" initialSql="SELECT 1"/>)
    await screen.findByRole('option',{name:/主库/});fireEvent.click(screen.getByRole('button',{name:'执行全部'}));await screen.findByRole('status')
    const id=(api.query as ReturnType<typeof vi.fn>).mock.calls[0][0].executionId
    rendered.rerender(<SqlConsolePage api={api} events={[{id:'1',executionId:id,status:'executing',timestamp:new Date().toISOString(),detail:'执行中'}]} streamStatus="reconnecting" theme="light" initialSql="SELECT 1"/>)
    expect((screen.getByRole('button',{name:'执行全部'}) as HTMLButtonElement).disabled).toBe(true)
    expect((screen.getByRole('button',{name:'取消执行'}) as HTMLButtonElement).disabled).toBe(false)
    fireEvent.click(screen.getByRole('button',{name:'取消执行'}));await waitFor(()=>expect(cancelExecution).toHaveBeenCalledWith(id))
  })
  it('polls an uncertain execution to a terminal state and restores controls', async () => {
    const getExecution=vi.fn().mockResolvedValue({summary:{status:'COMPLETED',startedAt:'2026-01-01T00:00:00Z',completedAt:'2026-01-01T00:00:01.500Z'},statements:[],events:[]})
    const api=client({classifySql:vi.fn().mockResolvedValue({statementCount:1,kinds:['QUERY'],queryOnly:true,requiresPurpose:false,atomicAllowed:false}),query:vi.fn().mockRejectedValue(new ApiError(0,'TIMEOUT','请求超时')),getExecution})
    render(<SqlConsolePage api={api} events={[]} streamStatus="reconnecting" theme="light" initialSql="SELECT 1"/>)
    await screen.findByRole('option',{name:/主库/});fireEvent.click(screen.getByRole('button',{name:'执行全部'}))
    await waitFor(()=>expect(getExecution).toHaveBeenCalled());await waitFor(()=>expect((screen.getByRole('button',{name:'执行全部'}) as HTMLButtonElement).disabled).toBe(false))
    expect(screen.getByText(/执行总耗时 1,500 ms/)).toBeTruthy()
  })
  it('treats a mismatched response id as uncertain before marking HTTP terminal', async () => {
    const getExecution=vi.fn().mockReturnValue(new Promise(()=>undefined))
    const query=vi.fn().mockResolvedValue({...result,executionId:'wrong-id'})
    const api=client({classifySql:vi.fn().mockResolvedValue({statementCount:1,kinds:['QUERY'],queryOnly:true,requiresPurpose:false,atomicAllowed:false}),query,getExecution})
    render(<SqlConsolePage api={api} events={[]} streamStatus="connected" theme="light" initialSql="SELECT 1"/>)
    await screen.findByRole('option',{name:/主库/});fireEvent.click(screen.getByRole('button',{name:'执行全部'}))
    expect(await screen.findByRole('status')).toBeTruthy();expect(screen.queryByText('达梦数据库')).toBeNull()
    expect((screen.getByRole('button',{name:'执行全部'}) as HTMLButtonElement).disabled).toBe(true)
  })
  it('rejects a mismatched mutation response id before rendering it as terminal', async () => {
    const getExecution=vi.fn().mockReturnValue(new Promise<ExecutionDetail>(()=>undefined))
    const execute=vi.fn().mockResolvedValue({executionId:'wrong-id',success:true,status:'COMPLETED',statements:[],elapsedMillis:4,databaseFingerprint:'abcdef',error:null})
    const api=client({classifySql:vi.fn().mockResolvedValue({statementCount:1,kinds:['DML'],queryOnly:false,requiresPurpose:true,atomicAllowed:true}),execute,getExecution})
    render(<SqlConsolePage api={api} events={[]} streamStatus="connected" theme="light" initialSql="UPDATE T SET C=1"/>)
    await screen.findByRole('option',{name:/主库/});fireEvent.click(screen.getByRole('button',{name:'执行全部'}));await screen.findByRole('dialog',{name:'确认修改操作'})
    fireEvent.change(screen.getByLabelText('用途'),{target:{value:'MIGRATION'}});fireEvent.click(screen.getByLabelText('我已核对 SQL 与目标连接'));fireEvent.click(screen.getByRole('button',{name:'确认并执行'}))
    expect(await screen.findByRole('status')).toBeTruthy();expect(getExecution).toHaveBeenCalledTimes(1)
    expect((screen.getByRole('button',{name:'执行全部'}) as HTMLButtonElement).disabled).toBe(true);expect(screen.queryByText('执行完成')).toBeNull()
  })
  it('aborts bounded status reconciliation on unmount', async () => {
    let pollSignal:AbortSignal|undefined
    const getExecution=vi.fn((_id:string,signal?:AbortSignal)=>{pollSignal=signal;return new Promise<ExecutionDetail>(()=>undefined)})
    const api=client({classifySql:vi.fn().mockResolvedValue({statementCount:1,kinds:['QUERY'],queryOnly:true,requiresPurpose:false,atomicAllowed:false}),query:vi.fn().mockRejectedValue(new ApiError(0,'NETWORK_ERROR','网络中断')),getExecution})
    const rendered=render(<SqlConsolePage api={api} events={[]} streamStatus="reconnecting" theme="light" initialSql="SELECT 1"/>)
    await screen.findByRole('option',{name:/主库/});fireEvent.click(screen.getByRole('button',{name:'执行全部'}));await waitFor(()=>expect(getExecution).toHaveBeenCalled())
    rendered.unmount();expect(pollSignal?.aborted).toBe(true)
  })
  it('does not let a late transport failure overwrite an SSE terminal state', async () => {
    let rejectQuery!:(reason:unknown)=>void
    const query=vi.fn().mockReturnValue(new Promise<QueryResult>((_resolve,reject)=>{rejectQuery=reject}))
    const getExecution=vi.fn()
    const api=client({classifySql:vi.fn().mockResolvedValue({statementCount:1,kinds:['QUERY'],queryOnly:true,requiresPurpose:false,atomicAllowed:false}),query,getExecution})
    const rendered=render(<SqlConsolePage api={api} events={[]} streamStatus="connected" theme="light" initialSql="SELECT 1"/>)
    await screen.findByRole('option',{name:/主库/});fireEvent.click(screen.getByRole('button',{name:'执行全部'}));await waitFor(()=>expect(query).toHaveBeenCalled())
    const id=query.mock.calls[0][0].executionId
    rendered.rerender(<SqlConsolePage api={api} events={[{id:'9',executionId:id,status:'completed',timestamp:new Date().toISOString(),detail:'已完成'}]} streamStatus="connected" theme="light" initialSql="SELECT 1"/>)
    await waitFor(()=>expect((screen.getByRole('button',{name:'执行全部'}) as HTMLButtonElement).disabled).toBe(false))
    rejectQuery(new ApiError(0,'NETWORK_ERROR','连接稍后才报告中断'));await Promise.resolve()
    expect(screen.queryByRole('status')).toBeNull();expect(getExecution).not.toHaveBeenCalled()
    expect((screen.getByRole('button',{name:'执行全部'}) as HTMLButtonElement).disabled).toBe(false)
  })
  it('retries a missing execution three times before declaring it not started', async () => {
    const getExecution=vi.fn().mockRejectedValue(new ApiError(404,'NOT_FOUND','未找到'))
    const api=client({classifySql:vi.fn().mockResolvedValue({statementCount:1,kinds:['QUERY'],queryOnly:true,requiresPurpose:false,atomicAllowed:false}),query:vi.fn().mockRejectedValue(new ApiError(0,'NETWORK_ERROR','网络中断')),getExecution})
    render(<SqlConsolePage api={api} events={[]} streamStatus="reconnecting" theme="light" initialSql="SELECT 1"/>)
    await screen.findByRole('option',{name:/主库/});fireEvent.click(screen.getByRole('button',{name:'执行全部'}))
    expect(await screen.findByText('多次查询均未找到执行记录，已判定任务未启动。',{}, {timeout:2000})).toBeTruthy()
    expect(getExecution).toHaveBeenCalledTimes(3)
    expect((screen.getByRole('button',{name:'执行全部'}) as HTMLButtonElement).disabled).toBe(false)
    await new Promise(resolve=>setTimeout(resolve,1500));expect(getExecution).toHaveBeenCalledTimes(3)
  })
  it('does not count non-404 reconciliation failures toward the three-miss decision', async () => {
    const getExecution=vi.fn()
      .mockRejectedValueOnce(new ApiError(0,'NETWORK_ERROR','网络中断'))
      .mockRejectedValueOnce(new ApiError(404,'NOT_FOUND','未找到'))
      .mockRejectedValueOnce(new ApiError(404,'NOT_FOUND','仍未找到'))
      .mockRejectedValueOnce(new ApiError(0,'NETWORK_ERROR','网络再次中断'))
    const api=client({classifySql:vi.fn().mockResolvedValue({statementCount:1,kinds:['QUERY'],queryOnly:true,requiresPurpose:false,atomicAllowed:false}),query:vi.fn().mockRejectedValue(new ApiError(0,'NETWORK_ERROR','请求中断')),getExecution})
    render(<SqlConsolePage api={api} events={[]} streamStatus="reconnecting" theme="light" initialSql="SELECT 1"/>)
    await screen.findByRole('option',{name:/主库/});fireEvent.click(screen.getByRole('button',{name:'执行全部'}))
    expect(await screen.findByText('执行状态仍无法确认。可手动重新查询状态或请求取消。',{}, {timeout:3500})).toBeTruthy()
    expect(getExecution).toHaveBeenCalledTimes(4);expect(screen.getByRole('button',{name:'重新查询状态'})).toBeTruthy()
    expect((screen.getByRole('button',{name:'执行全部'}) as HTMLButtonElement).disabled).toBe(true)
  })
  it('ignores an old reconciliation response after a terminal event and a new run', async () => {
    let resolveOldPoll!:(detail:ExecutionDetail)=>void
    const getExecution=vi.fn().mockReturnValue(new Promise<ExecutionDetail>(resolve=>{resolveOldPoll=resolve}))
    const query=vi.fn().mockRejectedValueOnce(new ApiError(0,'NETWORK_ERROR','网络中断')).mockImplementationOnce(input=>Promise.resolve({...result,executionId:input.executionId,rows:[{中文列:'新执行结果'}]}))
    const api=client({classifySql:vi.fn().mockResolvedValue({statementCount:1,kinds:['QUERY'],queryOnly:true,requiresPurpose:false,atomicAllowed:false}),query,getExecution})
    const rendered=render(<SqlConsolePage api={api} events={[]} streamStatus="reconnecting" theme="light" initialSql="SELECT 1"/>)
    await screen.findByRole('option',{name:/主库/});fireEvent.click(screen.getByRole('button',{name:'执行全部'}));await waitFor(()=>expect(getExecution).toHaveBeenCalled())
    const oldId=query.mock.calls[0][0].executionId
    rendered.rerender(<SqlConsolePage api={api} events={[{id:'10',executionId:oldId,status:'cancelled',timestamp:new Date().toISOString(),detail:'已取消'}]} streamStatus="connected" theme="light" initialSql="SELECT 1"/>)
    await waitFor(()=>expect((screen.getByRole('button',{name:'执行全部'}) as HTMLButtonElement).disabled).toBe(false));fireEvent.click(screen.getByRole('button',{name:'执行全部'}))
    expect(await screen.findByText('新执行结果')).toBeTruthy();const newId=query.mock.calls[1][0].executionId
    resolveOldPoll({summary:{status:'FAILED'},statements:[],events:[]} as unknown as ExecutionDetail);await Promise.resolve()
    expect(screen.getByText('新执行结果')).toBeTruthy();expect(document.querySelector('.execution-snapshot')?.textContent).toContain(newId)
  })
})
