import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import type { ApiClient, QueryResult, SafeConnection } from '../api/types'
import { SqlConsolePage } from './SqlConsolePage'

const connection: SafeConnection = { id: 'c1', name: '主库', configured: true, connected: false, hasPassword: true, urlSummary: 'jdbc:dm7://db:5236', isDefault: true }
const result: QueryResult = { executionId: 'e1', success: true, columns: [{ outputLabel: '中文列', originalLabel: '中文列', originalName: 'NAME', jdbcType: 12, typeName: 'VARCHAR' }], rows: [{ 中文列: '达梦数据库' }], truncated: false, returnedRows: 1, bytes: 12, elapsedMillis: 8, databaseFingerprint: 'abcdef123456', error: null }
const client = (overrides: Partial<ApiClient> = {}) => ({
  listConnections: vi.fn().mockResolvedValue({ connections: [connection] }), classifySql: vi.fn(), query: vi.fn(), execute: vi.fn(), cancelExecution: vi.fn(), ...overrides,
} as unknown as ApiClient)

describe('SqlConsolePage', () => {
  it('classifies and immediately runs a query with a client-known UUID', async () => {
    const classifySql=vi.fn().mockResolvedValue({ statementCount: 1, kinds: ['QUERY'], queryOnly: true, requiresPurpose: false, atomicAllowed: false })
    const query=vi.fn().mockResolvedValue(result)
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
    expect(await screen.findByText('任务已结束，取消请求未生效。')).toBeTruthy()
    expect((screen.getByRole('button',{name:'取消执行'}) as HTMLButtonElement).disabled).toBe(false)
    resolveQuery(result)
  })
})
