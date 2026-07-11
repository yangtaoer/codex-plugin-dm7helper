import { Play, Square, Trash2 } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { ApiClient, EventRecord, ExecuteResult, QueryResult, SafeConnection, SqlClassification, SqlPurpose } from '../api/types'
import type { StreamStatus } from '../hooks/useEventStream'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { ExecutionTimeline } from '../components/ExecutionTimeline'
import { ResultGrid } from '../components/ResultGrid'
import { SqlEditor } from '../components/SqlEditor'
import { PageHeader } from '../components/PageHeader'

const activeStates=new Set(['queued','connecting','parsing','executing','committing','logging'])
const purposes: {value:SqlPurpose;label:string}[]=[{value:'PRODUCTION_CHANGE',label:'生产变更'},{value:'MIGRATION',label:'迁移'},{value:'TEST',label:'测试'},{value:'MOCK',label:'Mock'},{value:'SEED',label:'种子数据'},{value:'SAMPLE',label:'样例'}]
const uuid=()=>{try{return globalThis.crypto?.randomUUID?.()}catch{return undefined}}

export function SqlConsolePage({api,events,streamStatus,theme,initialSql=''}:{api:ApiClient;events:EventRecord[];streamStatus:StreamStatus;theme:string;initialSql?:string}) {
  const [sql,setSql]=useState(initialSql),[selection,setSelection]=useState(''),[connections,setConnections]=useState<SafeConnection[]>([]),[connectionId,setConnectionId]=useState('')
  const [limits,setLimits]=useState({maxRows:1000,maxBytes:10485760,timeoutSeconds:60}),[busy,setBusy]=useState(false),[error,setError]=useState<{message:string;correlationId?:string}|null>(null)
  const [classification,setClassification]=useState<SqlClassification|null>(null),[pendingSql,setPendingSql]=useState(''),[confirmOpen,setConfirmOpen]=useState(false),[clearOpen,setClearOpen]=useState(false)
  const [purpose,setPurpose]=useState<SqlPurpose|''>(''),[ack,setAck]=useState(false),[atomic,setAtomic]=useState(false),[continueOnError,setContinueOnError]=useState(false)
  const [executionId,setExecutionId]=useState<string>(),[queryResult,setQueryResult]=useState<QueryResult>(),[executeResult,setExecuteResult]=useState<ExecuteResult>(),[tab,setTab]=useState<'result'|'messages'|'timeline'>('result'),[cancelPending,setCancelPending]=useState(false)
  const classifyRef=useRef<AbortController|null>(null),generation=useRef(0)
  useEffect(()=>{setSql(initialSql)},[initialSql])
  useEffect(()=>{const controller=new AbortController();api.listConnections(controller.signal).then(({connections})=>{setConnections(connections);setConnectionId(connections.find(item=>item.isDefault)?.id??'')}).catch((e:unknown)=>setError({message:(e as Error).message||'无法读取连接。'}));return()=>controller.abort()},[api])
  useEffect(()=>()=>classifyRef.current?.abort(),[])
  const currentEvents=useMemo(()=>events.filter(event=>event.executionId===executionId).toSorted((a,b)=>Number(a.id)-Number(b.id)),[events,executionId]),latest=currentEvents.at(-1)
  const cancellable=Boolean(executionId&&latest&&activeStates.has(latest.status)&&!cancelPending)

  const run=async(candidate:string)=>{
    const command=candidate.trim();if(!command||busy)return
    if(!connectionId){setError({message:'请选择一个数据库连接。'});return}
    classifyRef.current?.abort();const controller=new AbortController();classifyRef.current=controller;const request=++generation.current
    setBusy(true);setError(null);setQueryResult(undefined);setExecuteResult(undefined);setExecutionId(undefined)
    try{const classified=await api.classifySql(command,controller.signal);if(request!==generation.current||controller.signal.aborted)return
      setClassification(classified);if(classified.queryOnly)await executeQuery(command,request);else{setPendingSql(command);setAtomic(classified.atomicAllowed);setContinueOnError(false);setPurpose('');setAck(false);setConfirmOpen(true);setBusy(false)}
    }catch(e:unknown){if(request===generation.current&&!(e instanceof DOMException&&e.name==='AbortError'))setError({message:(e as Error).message||'SQL 分类失败。',correlationId:(e as {correlationId?:string}).correlationId})}finally{if(request===generation.current&&!confirmOpen)setBusy(false)}
  }
  const executeQuery=async(command:string,request:number)=>{const id=uuid();if(!id)throw new Error('浏览器无法生成安全执行标识，已阻止执行。');setExecutionId(id);setTab('timeline');const result=await api.query({connectionId,executionId:id,sql:command,...limits});if(request!==generation.current)return;setQueryResult(result);setTab('result')}
  const confirmMutation=async()=>{if(!purpose||!ack||!classification)return;const id=uuid();if(!id){setError({message:'浏览器无法生成安全执行标识，已阻止执行。'});return}setConfirmOpen(false);setBusy(true);setExecutionId(id);setTab('timeline');try{const result=await api.execute({connectionId,executionId:id,sql:pendingSql,purpose,atomic:classification.atomicAllowed&&atomic,continueOnError:!atomic&&continueOnError,timeoutSeconds:limits.timeoutSeconds});setExecuteResult(result);setTab('messages')}catch(e:unknown){setError({message:(e as Error).message||'执行失败。',correlationId:(e as {correlationId?:string}).correlationId})}finally{setBusy(false);setPendingSql('') }}
  const cancel=async()=>{if(!executionId||!cancellable)return;setCancelPending(true);try{const result=await api.cancelExecution(executionId);if(!result.cancelRequested){setError({message:'任务已结束，取消请求未生效。'});setCancelPending(false)}}catch(e:unknown){setError({message:(e as Error).message||'无法请求取消。',correlationId:(e as {correlationId?:string}).correlationId});setCancelPending(false)}}
  useEffect(()=>{if(latest&&['completed','failed','cancelled','rejected'].includes(latest.status))setCancelPending(false)},[latest])
  const invalidLimits=limits.maxRows<1||limits.maxRows>10000||limits.maxBytes<1024||limits.maxBytes>52428800||limits.timeoutSeconds<1||limits.timeoutSeconds>3600
  return <><PageHeader eyebrow="MANUAL SQL" title="SQL 执行控制台" description="先由 DM 语法解析器分类，再通过已知执行标识跟踪全过程。" />
    <section className="sql-workbench"><div className="sql-toolbar"><label>连接<select aria-label="连接" value={connectionId} onChange={e=>setConnectionId(e.target.value)}><option value="">选择连接</option>{connections.map(item=><option key={item.id} value={item.id}>{item.name}{item.isDefault?'（默认）':''}</option>)}</select></label><label>最大行数<input aria-label="最大行数" type="number" value={limits.maxRows} onChange={e=>setLimits({...limits,maxRows:Number(e.target.value)})}/></label><label>超时（秒）<input aria-label="超时（秒）" type="number" value={limits.timeoutSeconds} onChange={e=>setLimits({...limits,timeoutSeconds:Number(e.target.value)})}/></label><label>最大字节<input aria-label="最大字节" type="number" value={limits.maxBytes} onChange={e=>setLimits({...limits,maxBytes:Number(e.target.value)})}/></label></div>
      {invalidLimits&&<p className="field-error" role="alert">限制范围：1–10,000 行，1 KiB–50 MiB，1–3,600 秒。</p>}
      <p id="sql-editor-help" className="editor-help">Ctrl/Cmd + Enter 执行选区；无选区时执行全文。SQL 仅保存在当前页面内存。</p><SqlEditor value={sql} theme={theme} onChange={setSql} onSelectionChange={setSelection} onRun={run}/>
      <div className="sql-actions"><button className="button-primary" disabled={busy||invalidLimits||!selection.trim()} onClick={()=>void run(selection)}><Play size={15}/>执行选中</button><button className="button-primary" disabled={busy||invalidLimits||!sql.trim()} onClick={()=>void run(sql)}><Play size={15}/>执行全部</button><button className="button-secondary" disabled={!cancellable} onClick={()=>void cancel()}><Square size={14}/>{cancelPending?'正在请求取消':'取消执行'}</button><button className="button-secondary" aria-label="清空编辑器" disabled={!sql.trim()} onClick={()=>setClearOpen(true)}><Trash2 size={14}/>清空</button></div>
    </section>
    {error&&<div className="sql-safe-error" role="alert"><strong>{error.message}</strong>{error.correlationId&&<span>关联 ID：{error.correlationId}</span>}</div>}
    <section className="sql-output"><div className="output-tabs" role="tablist"><button role="tab" aria-selected={tab==='result'} onClick={()=>setTab('result')}>结果表格</button><button role="tab" aria-selected={tab==='messages'} onClick={()=>setTab('messages')}>执行消息</button><button role="tab" aria-selected={tab==='timeline'} onClick={()=>setTab('timeline')}>实时过程</button></div>
      {tab==='result'&&(queryResult?<ResultGrid result={queryResult}/>:<p className="quiet-copy">查询结果将在此显示。</p>)}
      {tab==='messages'&&(executeResult?<MutationMessages result={executeResult}/>:<p className="quiet-copy">修改语句的逐条提交与记录状态将在此显示。</p>)}
      {tab==='timeline'&&<ExecutionTimeline executionId={executionId} events={events} streamStatus={streamStatus}/>}</section>
    <ConfirmDialog open={confirmOpen} title="确认修改操作" confirmLabel="确认并执行" confirmDisabled={!purpose||!ack} onClose={()=>{setConfirmOpen(false);setPendingSql('')}} onConfirm={()=>void confirmMutation()}><div className="mutation-confirm"><label>用途<select aria-label="用途" required value={purpose} onChange={e=>setPurpose(e.target.value as SqlPurpose)}><option value="">请选择</option>{purposes.map(item=><option key={item.value} value={item.value}>{item.label}</option>)}</select></label><p>PRODUCTION_CHANGE、MIGRATION 会写入发版日志；TEST、MOCK、SEED、SAMPLE 会明确排除。</p><label><input aria-label="原子执行" type="checkbox" checked={atomic} disabled={!classification?.atomicAllowed} onChange={e=>{setAtomic(e.target.checked);if(e.target.checked)setContinueOnError(false)}}/> 原子执行（仅纯 DML）</label><label><input type="checkbox" checked={continueOnError} disabled={atomic} onChange={e=>setContinueOnError(e.target.checked)}/> 失败后继续</label><label><input type="checkbox" checked={ack} onChange={e=>setAck(e.target.checked)}/> 我已核对 SQL 与目标连接</label></div></ConfirmDialog>
    <ConfirmDialog open={clearOpen} title="清空 SQL" confirmLabel="确认清空" onClose={()=>setClearOpen(false)} onConfirm={()=>{setSql('');setClearOpen(false)}}><p>当前编辑内容将从内存中清除，且无法恢复。</p></ConfirmDialog>
  </>
}

function MutationMessages({result}:{result:ExecuteResult}){return <div className="mutation-messages"><div className="result-summary"><span>{result.success?'执行完成':'部分或全部失败'}</span><span>{result.elapsedMillis} ms</span><span>DB {result.databaseFingerprint.slice(0,12)}</span></div><ol>{result.statements.map(item=><li key={item.index}><strong>#{item.index+1} · {item.kind}</strong><span>{item.success?'成功':'失败'} · {item.committed?'已提交':'未提交'}（{item.commitBehavior}）· {item.rowCount} 行 · {item.elapsedMillis} ms · {item.recorded?'已记录':item.exclusionReason??'未记录'}</span>{item.error&&<small>{item.error.phase} · {item.error.message} · 关联 {item.error.correlationId}{item.error.sqlState?` · SQLState ${item.error.sqlState}`:''}{item.error.errorCode!==null?` · DB ${item.error.errorCode}`:''}{item.error.restartRequired?' · 需要重启':''}</small>}</li>)}</ol></div>}
