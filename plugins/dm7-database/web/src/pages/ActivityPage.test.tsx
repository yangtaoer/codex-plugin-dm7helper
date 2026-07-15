import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import type { ApiClient, EventRecord, HistoryPage } from '../api/types'
import { ActivityPage } from './ActivityPage'

const item={executionId:'11111111-1111-4111-8111-111111111111',correlationId:'22222222-2222-4222-8222-222222222222',connectionFingerprint:'a'.repeat(64),source:'CONSOLE' as const,purpose:'MIGRATION' as const,status:'EXECUTING' as const,startedAt:'2026-07-11T08:00:00Z',completedAt:null,affectedRows:0,returnedRows:0,recorded:false,exclusionReason:null,sqlSummary:'UPDATE CUSTOMER SET NAME=?'}
const page:HistoryPage={items:[item],offset:0,limit:50,hasMore:false}

it('uses shared events, applies exact filters, cancels active work and opens safe detail',async()=>{
  const history=vi.fn().mockResolvedValue(page),cancelExecution=vi.fn().mockResolvedValue({executionId:item.executionId,cancelRequested:true})
  const getExecution=vi.fn().mockResolvedValue({summary:{...item,phase:'EXECUTING'},statements:[{index:0,kind:'DML',status:'RUNNING',sqlSummary:'UPDATE CUSTOMER SET NAME=?'}],events:[{sequence:1,status:'EXECUTING',timestamp:item.startedAt,detail:'statement 1'}]})
  const api={history,cancelExecution,getExecution} as unknown as ApiClient
  const events:EventRecord[]=[{id:'9',executionId:item.executionId,status:'executing',timestamp:item.startedAt,detail:'statement 1'}]
  render(<ActivityPage api={api} events={events} streamStatus="connected" />)
  expect((await screen.findAllByText('UPDATE CUSTOMER SET NAME=?')).length).toBeGreaterThan(0)
  expect(screen.getByLabelText('来源').closest('.filter-select-shell')?.querySelector('svg')).toBeTruthy()
  fireEvent.change(screen.getByLabelText('来源'),{target:{value:'CONSOLE'}});fireEvent.change(screen.getByLabelText('状态'),{target:{value:'EXECUTING'}})
  fireEvent.click(screen.getByRole('button',{name:'应用筛选'}));await waitFor(()=>expect(history).toHaveBeenLastCalledWith(expect.objectContaining({source:'CONSOLE',status:'EXECUTING',offset:0,limit:50}),expect.any(AbortSignal)))
  fireEvent.click(screen.getAllByRole('button',{name:'取消任务'})[0]);await waitFor(()=>expect(cancelExecution).toHaveBeenCalledWith(item.executionId,expect.any(AbortSignal)))
  fireEvent.click(screen.getAllByRole('button',{name:'查看执行详情'})[0]);expect(await screen.findByRole('dialog',{name:'执行详情'})).toBeTruthy()
  expect(document.body.textContent).not.toContain('secret')
})

it('aborts stale history requests and resets without broadening fields',async()=>{
 const calls:AbortSignal[]=[];const history=vi.fn((_q:unknown,signal?:AbortSignal)=>{calls.push(signal!);return Promise.resolve(page)})
 render(<ActivityPage api={{history} as unknown as ApiClient} events={[]} streamStatus="connected"/>);await screen.findByText('ACTIVE JOBS')
 fireEvent.change(screen.getByLabelText('来源'),{target:{value:'MCP'}});fireEvent.click(screen.getByRole('button',{name:'应用筛选'}));
 await waitFor(()=>expect(history).toHaveBeenCalledTimes(2));expect(calls[0].aborted).toBe(true);expect(history.mock.calls[1][0]).toEqual({source:'MCP',offset:0,limit:50})
 fireEvent.click(screen.getByRole('button',{name:'重置'}));await waitFor(()=>expect(history).toHaveBeenCalledTimes(3));expect(history.mock.calls[2][0]).toEqual({offset:0,limit:50})
})

it('paginates without duplicate executions and reports stale stream',async()=>{
 const second={...item,executionId:'33333333-3333-4333-8333-333333333333',sqlSummary:'DELETE FROM CUSTOMER WHERE ID=?'}
 const history=vi.fn().mockResolvedValueOnce({...page,hasMore:true}).mockResolvedValueOnce({items:[item,second],offset:50,limit:50,hasMore:false})
 render(<ActivityPage api={{history} as unknown as ApiClient} events={[]} streamStatus="reconnecting"/>);expect(screen.getByText(/实时通道已过期/)).toBeTruthy();await screen.findAllByText(item.sqlSummary)
 fireEvent.click(screen.getByRole('button',{name:'加载更多'}));expect((await screen.findAllByText(second.sqlSummary)).length).toBe(2);expect(screen.getAllByText(item.sqlSummary).length).toBe(2)
 expect(history).toHaveBeenLastCalledWith({offset:50,limit:50},expect.any(AbortSignal))
})

it('keeps cancellation pending non-terminal and surfaces safe failure',async()=>{
 let reject!:(e:Error)=>void;const cancelExecution=vi.fn(()=>new Promise((_r,j)=>{reject=j}));render(<ActivityPage api={{history:vi.fn().mockResolvedValue(page),cancelExecution} as unknown as ApiClient} events={[]} streamStatus="connected"/>);await screen.findAllByText(item.sqlSummary)
 fireEvent.click(screen.getAllByRole('button',{name:'取消任务'})[0]);expect(screen.getAllByText('取消请求中').length).toBeGreaterThan(0);reject(new Error('操作与当前状态冲突。'))
 expect(await screen.findByRole('alert')).toHaveProperty('textContent',expect.stringContaining('操作与当前状态冲突'))
})

it('coalesces ten phase events into one terminal refresh and aborts on unmount',async()=>{
 const signals:AbortSignal[]=[];const history=vi.fn((_q:unknown,signal?:AbortSignal)=>{signals.push(signal!);return Promise.resolve(page)});const {rerender,unmount}=render(<ActivityPage api={{history} as unknown as ApiClient} events={[]} streamStatus="connected"/>);await waitFor(()=>expect(history).toHaveBeenCalledTimes(1))
 const statuses=['queued','connecting','parsing','executing','committing','logging','executing','logging','committing'] as const;const phaseEvents=statuses.map((status,i)=>({id:String(i+1),executionId:item.executionId,status,timestamp:item.startedAt,detail:status}))
 rerender(<ActivityPage api={{history} as unknown as ApiClient} events={phaseEvents} streamStatus="connected"/>);await new Promise(r=>setTimeout(r,220));expect(history).toHaveBeenCalledTimes(1)
rerender(<ActivityPage api={{history} as unknown as ApiClient} events={[...phaseEvents,{id:'10',executionId:item.executionId,status:'completed',timestamp:item.startedAt,detail:'done'}]} streamStatus="connected"/>);await waitFor(()=>expect(history).toHaveBeenCalledTimes(2));unmount();expect(signals.at(-1)?.aborted).toBe(true)
})

it('keeps accepted cancellation pending until an authoritative terminal event',async()=>{const cancelExecution=vi.fn().mockResolvedValue({executionId:item.executionId,cancelRequested:true}),getExecution=vi.fn().mockResolvedValue({summary:{status:'EXECUTING'},statements:[],events:[]});const api={history:vi.fn().mockResolvedValue(page),cancelExecution,getExecution} as unknown as ApiClient;const {rerender}=render(<ActivityPage api={api} events={[]} streamStatus="connected"/>);await screen.findAllByText(item.sqlSummary);fireEvent.click(screen.getAllByRole('button',{name:'取消任务'})[0]);await waitFor(()=>expect(cancelExecution).toHaveBeenCalled());expect(screen.getAllByText('取消请求中').length).toBeGreaterThan(0);rerender(<ActivityPage api={api} events={[{id:'terminal',executionId:item.executionId,status:'cancelled',timestamp:item.startedAt,detail:'done'}]} streamStatus="connected"/>);await waitFor(()=>expect(screen.queryByText('取消请求中')).toBeNull())})

it('finishes accepted cancellation from GET terminal without SSE and passes a poll signal',async()=>{const history=vi.fn().mockResolvedValue(page),getExecution=vi.fn().mockResolvedValue({summary:{status:'CANCELLED'},statements:[],events:[]});render(<ActivityPage api={{history,cancelExecution:vi.fn().mockResolvedValue({executionId:item.executionId,cancelRequested:true}),getExecution} as unknown as ApiClient} events={[]} streamStatus="connected"/>);await screen.findAllByText(item.sqlSummary);fireEvent.click(screen.getAllByRole('button',{name:'取消任务'})[0]);await waitFor(()=>expect(getExecution).toHaveBeenCalledWith(item.executionId,expect.any(AbortSignal)));await waitFor(()=>expect(screen.queryByText('取消请求中')).toBeNull());expect(history.mock.calls.length).toBeGreaterThan(1)})

it('bounds unknown cancellation polling and offers a manual status retry',async()=>{const getExecution=vi.fn().mockRejectedValue(new Error('network'));render(<ActivityPage api={{history:vi.fn().mockResolvedValue(page),cancelExecution:vi.fn().mockResolvedValue({executionId:item.executionId,cancelRequested:true}),getExecution} as unknown as ApiClient} events={[]} streamStatus="connected"/>);await screen.findAllByText(item.sqlSummary);vi.useFakeTimers();try{fireEvent.click(screen.getAllByRole('button',{name:'取消任务'})[0]);await vi.advanceTimersByTimeAsync(0);await vi.advanceTimersByTimeAsync(5000);await vi.advanceTimersByTimeAsync(0);expect(getExecution).toHaveBeenCalledTimes(5);expect(screen.getByText(/取消状态未知/)).toBeTruthy();getExecution.mockResolvedValueOnce({summary:{status:'CANCELLED'},statements:[],events:[]});fireEvent.click(screen.getAllByRole('button',{name:'重新查询状态'})[0]);await vi.advanceTimersByTimeAsync(0);expect(getExecution).toHaveBeenCalledTimes(6);expect(screen.queryByText(/取消状态未知/)).toBeNull()}finally{vi.useRealTimers()}})

it('aborts an in-flight cancellation poll on unmount without loading history',async()=>{let resolve!:(value:unknown)=>void;const signals:AbortSignal[]=[],history=vi.fn().mockResolvedValue(page),getExecution=vi.fn((_id:string,signal?:AbortSignal)=>{signals.push(signal!);return new Promise(done=>{resolve=done})});const {unmount}=render(<ActivityPage api={{history,cancelExecution:vi.fn().mockResolvedValue({executionId:item.executionId,cancelRequested:true}),getExecution} as unknown as ApiClient} events={[]} streamStatus="connected"/>);await screen.findAllByText(item.sqlSummary);fireEvent.click(screen.getAllByRole('button',{name:'取消任务'})[0]);await waitFor(()=>expect(getExecution).toHaveBeenCalled());const before=history.mock.calls.length;unmount();expect(signals[0].aborted).toBe(true);resolve({summary:{status:'CANCELLED'},statements:[],events:[]});await Promise.resolve();expect(history).toHaveBeenCalledTimes(before)})

it('lets SSE terminal win a cancellation poll race and ignores the stale response',async()=>{let resolve!:(value:unknown)=>void;const signals:AbortSignal[]=[],getExecution=vi.fn((_id:string,signal?:AbortSignal)=>{signals.push(signal!);return new Promise(done=>{resolve=done})});const api={history:vi.fn().mockResolvedValue(page),cancelExecution:vi.fn().mockResolvedValue({executionId:item.executionId,cancelRequested:true}),getExecution} as unknown as ApiClient;const {rerender}=render(<ActivityPage api={api} events={[]} streamStatus="connected"/>);await screen.findAllByText(item.sqlSummary);fireEvent.click(screen.getAllByRole('button',{name:'取消任务'})[0]);await waitFor(()=>expect(getExecution).toHaveBeenCalled());rerender(<ActivityPage api={api} events={[{id:'terminal-race',executionId:item.executionId,status:'cancelled',timestamp:item.startedAt,detail:'done'}]} streamStatus="connected"/>);await waitFor(()=>expect(signals[0].aborted).toBe(true));resolve({summary:{status:'EXECUTING'},statements:[],events:[]});await Promise.resolve();expect(screen.queryByText('取消请求中')).toBeNull();expect(screen.queryByText('取消状态未知')).toBeNull()})

it('clears false cancellation with a safe explanation',async()=>{render(<ActivityPage api={{history:vi.fn().mockResolvedValue(page),cancelExecution:vi.fn().mockResolvedValue({executionId:item.executionId,cancelRequested:false})} as unknown as ApiClient} events={[]} streamStatus="connected"/>);await screen.findAllByText(item.sqlSummary);fireEvent.click(screen.getAllByRole('button',{name:'取消任务'})[0]);expect(await screen.findByRole('alert')).toHaveProperty('textContent',expect.stringContaining('已终止或当前无法取消'));expect(screen.queryByText('取消请求中')).toBeNull()})

it('keeps the detail drawer during loading, traps focus, closes with Escape and aborts',async()=>{let resolve!:(value:any)=>void;const getExecution=vi.fn((_id:string,_signal?:AbortSignal)=>new Promise(done=>{resolve=done}));const {unmount}=render(<ActivityPage api={{history:vi.fn().mockResolvedValue(page),getExecution} as unknown as ApiClient} events={[]} streamStatus="connected"/>);await screen.findAllByText(item.sqlSummary);const opener=screen.getAllByRole('button',{name:'查看执行详情'})[0];fireEvent.click(opener);const dialog=screen.getByRole('dialog',{name:'执行详情'});expect(screen.getByText('正在读取执行详情')).toBeTruthy();expect(screen.getByRole('button',{name:'关闭执行详情'})).toBe(document.activeElement);fireEvent.keyDown(dialog,{key:'Tab'});expect(screen.getByRole('button',{name:'关闭执行详情'})).toBe(document.activeElement);resolve({summary:{sqlSummary:'UPDATE T SET C=?',phase:'FAILED',status:'FAILED',correlationId:'corr',connectionFingerprint:'abc',error:{message:'Database operation failed',phase:'EXECUTING',sqlState:'42000',errorCode:9,restartRequired:false}},statements:[],events:[]});expect(await screen.findByText('Database operation failed')).toBeTruthy();fireEvent.keyDown(dialog,{key:'Escape'});await waitFor(()=>expect(screen.queryByRole('dialog',{name:'执行详情'})).toBeNull());expect(opener).toBe(document.activeElement);fireEvent.click(opener);const signal=getExecution.mock.calls.at(-1)![1]!;unmount();expect(signal.aborted).toBe(true)})
