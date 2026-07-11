import { Play, Square, Trash2 } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { ApiClient, EventRecord, ExecuteResult, ExecutionDetail, QueryResult, SafeConnection, SafeExecutionError, SqlClassification, SqlPurpose } from '../api/types'
import type { StreamStatus } from '../hooks/useEventStream'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { ExecutionTimeline } from '../components/ExecutionTimeline'
import { ResultGrid } from '../components/ResultGrid'
import { SqlEditor } from '../components/SqlEditor'
import { PageHeader } from '../components/PageHeader'

const activeStates=new Set(['queued','connecting','parsing','executing','committing','logging']),terminalStates=new Set(['completed','failed','cancelled','rejected'])
const purposes: {value:SqlPurpose;label:string}[]=[{value:'PRODUCTION_CHANGE',label:'生产变更'},{value:'MIGRATION',label:'迁移'},{value:'TEST',label:'测试'},{value:'MOCK',label:'Mock'},{value:'SEED',label:'种子数据'},{value:'SAMPLE',label:'样例'}]
const uuid=()=>{try{return globalThis.crypto?.randomUUID?.()}catch{return undefined}}
type Limits={maxRows:number;maxBytes:number;timeoutSeconds:number}
type ExecutionSnapshot={token:number;connectionId:string;connectionName:string;limits:Limits;executionId?:string}
type OutputTab='result'|'messages'|'timeline'
type TrackingState='idle'|'known'|'unknown'|'terminal'|'not-started'
const tabs:{id:OutputTab;label:string}[]=[{id:'result',label:'结果表格'},{id:'messages',label:'执行消息'},{id:'timeline',label:'实时过程'}]
const reconciliationDelays=[0,200,600,1400] as const
const detailStatus=(detail:ExecutionDetail)=>String(detail.summary.status??'').toUpperCase()
const detailElapsed=(detail:ExecutionDetail)=>{const started=Date.parse(String(detail.summary.startedAt??'')),completed=Date.parse(String(detail.summary.completedAt??''));return Number.isFinite(started)&&Number.isFinite(completed)?Math.max(0,completed-started):undefined}

export function SqlConsolePage({api,events,streamStatus,theme,initialSql=''}:{api:ApiClient;events:EventRecord[];streamStatus:StreamStatus;theme:string;initialSql?:string}) {
  const [sql,setSql]=useState(initialSql),[selection,setSelection]=useState(''),[connections,setConnections]=useState<SafeConnection[]>([]),[connectionId,setConnectionId]=useState('')
  const [limits,setLimits]=useState<Limits>({maxRows:1000,maxBytes:10485760,timeoutSeconds:60}),[busy,setBusy]=useState(false),[error,setError]=useState<{message:string;correlationId?:string}|null>(null)
  const [classification,setClassification]=useState<SqlClassification|null>(null),[pendingSql,setPendingSql]=useState(''),[confirmOpen,setConfirmOpen]=useState(false),[clearOpen,setClearOpen]=useState(false)
  const [purpose,setPurpose]=useState<SqlPurpose|''>(''),[ack,setAck]=useState(false),[atomic,setAtomic]=useState(false),[continueOnError,setContinueOnError]=useState(false)
  const [executionId,setExecutionId]=useState<string>(),[snapshot,setSnapshot]=useState<ExecutionSnapshot>(),[queryResult,setQueryResult]=useState<QueryResult>(),[executeResult,setExecuteResult]=useState<ExecuteResult>()
  const [tab,setTab]=useState<OutputTab>('result'),[cancelPending,setCancelPending]=useState(false),[httpTerminal,setHttpTerminal]=useState(false)
  const [tracking,setTracking]=useState<TrackingState>('idle'),[authoritativeElapsed,setAuthoritativeElapsed]=useState<number>()
  const classifyRef=useRef<AbortController|null>(null),generation=useRef(0),activeExecution=useRef<string|undefined>(undefined),tabRefs=useRef<Record<OutputTab,HTMLButtonElement|null>>({result:null,messages:null,timeline:null})
  const reconcileAbort=useRef<AbortController|null>(null),reconcileTimer=useRef<ReturnType<typeof setTimeout>|null>(null),terminalExecution=useRef<string|undefined>(undefined)
  useEffect(()=>{setSql(initialSql)},[initialSql])
  useEffect(()=>{const controller=new AbortController();api.listConnections(controller.signal).then(({connections})=>{setConnections(connections);setConnectionId(connections.find(item=>item.isDefault)?.id??'')}).catch((e:unknown)=>setError({message:(e as Error).message||'无法读取连接。'}));return()=>controller.abort()},[api])
  useEffect(()=>()=>{generation.current+=1;classifyRef.current?.abort();stopReconciliation()},[])
  const currentEvents=useMemo(()=>events.filter(event=>event.executionId===executionId).toSorted((a,b)=>Number(a.id)-Number(b.id)),[events,executionId])
  const eventTerminal=currentEvents.find(event=>terminalStates.has(event.status)),latest=eventTerminal??currentEvents.at(-1)
  useEffect(()=>{if(eventTerminal&&executionId){settleKnownTerminal(generation.current,executionId,eventTerminal.status.toUpperCase())}},[eventTerminal,executionId])
  const cancellable=Boolean(executionId&&!cancelPending&&!httpTerminal&&(tracking==='unknown'||Boolean(latest&&activeStates.has(latest.status))))
  const controlsLocked=busy||confirmOpen
  const invalidLimits=limits.maxRows<1||limits.maxRows>10000||limits.maxBytes<1024||limits.maxBytes>52428800||limits.timeoutSeconds<1||limits.timeoutSeconds>3600

  function stopReconciliation(){if(reconcileTimer.current!==null){clearTimeout(reconcileTimer.current);reconcileTimer.current=null}reconcileAbort.current?.abort();reconcileAbort.current=null}
  function settleKnownTerminal(token:number,id:string,status:string,elapsed?:number){if(!isCurrent(token,id))return;terminalExecution.current=id;stopReconciliation();setTracking('terminal');setCancelPending(false);setHttpTerminal(true);setBusy(false);setError(null);if(elapsed!==undefined)setAuthoritativeElapsed(elapsed);setTab(status==='COMPLETED'&&queryResult?'result':status==='COMPLETED'&&executeResult?'messages':'timeline')}
  function scheduleReconciliation(token:number,id:string,attempt:number,consecutiveNotFound:number){if(!isCurrent(token,id)||attempt>=reconciliationDelays.length)return;reconcileTimer.current=setTimeout(()=>{reconcileTimer.current=null;void pollExecution(token,id,attempt,consecutiveNotFound)},reconciliationDelays[attempt])}
  async function pollExecution(token:number,id:string,attempt:number,consecutiveNotFound=0){
    if(!isCurrent(token,id))return;reconcileAbort.current?.abort();const controller=new AbortController();reconcileAbort.current=controller
    try{const detail=await api.getExecution(id,controller.signal);if(controller.signal.aborted||!isCurrent(token,id))return
      const status=detailStatus(detail);if(terminalStates.has(status.toLowerCase())){settleKnownTerminal(token,id,status,detailElapsed(detail));return}
      setTracking('unknown');if(attempt+1<reconciliationDelays.length)scheduleReconciliation(token,id,attempt+1,0)
    }catch(problem:unknown){if(controller.signal.aborted||!isCurrent(token,id))return;const status=Number((problem as {status?:number}).status)
      const nextNotFound=status===404?consecutiveNotFound+1:0
      if(nextNotFound>=3){stopReconciliation();setTracking('not-started');setBusy(false);setHttpTerminal(true);setCancelPending(false);setError({message:'多次查询均未找到执行记录，已判定任务未启动。'});return}
      if(attempt+1<reconciliationDelays.length){scheduleReconciliation(token,id,attempt+1,nextNotFound);return}
      setTracking('unknown');setError({message:'执行状态仍无法确认。可手动重新查询状态或请求取消。',correlationId:(problem as {correlationId?:string}).correlationId})
    }finally{if(reconcileAbort.current===controller)reconcileAbort.current=null}
  }
  function beginUnknownTracking(token:number,id:string,message:string,correlationId?:string){if(!isCurrent(token,id)||terminalExecution.current===id)return;stopReconciliation();setTracking('unknown');setBusy(true);setHttpTerminal(false);setCancelPending(false);setError({message:`执行状态待确认：${message}`,correlationId});setTab('timeline');void pollExecution(token,id,0)}
  function retryTracking(){const id=executionId;if(!id||tracking!=='unknown')return;stopReconciliation();setError({message:'正在重新查询执行状态…'});void pollExecution(generation.current,id,0)}

  const captureSnapshot=(token:number):ExecutionSnapshot=>({token,connectionId,connectionName:connections.find(item=>item.id===connectionId)?.name??'未知连接',limits:{...limits}})
  const isCurrent=(token:number,id?:string)=>generation.current===token&&(!id||activeExecution.current===id)
  const startExecution=(id:string,current:ExecutionSnapshot)=>{activeExecution.current=id;terminalExecution.current=undefined;setExecutionId(id);setSnapshot({...current,executionId:id});setTracking('known');setTab('timeline')}
  const finishHttp=(token:number,id:string,elapsed:number)=>{if(!isCurrent(token,id))return false;terminalExecution.current=id;stopReconciliation();setTracking('terminal');setAuthoritativeElapsed(Math.max(0,elapsed));setCancelPending(false);setHttpTerminal(true);setBusy(false);return true}

  const run=async(candidate:string)=>{
    if(!candidate.trim()||busy||confirmOpen)return
    if(!connectionId){setError({message:'请选择一个数据库连接。'});return}
    stopReconciliation();classifyRef.current?.abort();const controller=new AbortController();classifyRef.current=controller
    const token=++generation.current,current=captureSnapshot(token);activeExecution.current=undefined;terminalExecution.current=undefined
    setSnapshot(current);setBusy(true);setError(null);setQueryResult(undefined);setExecuteResult(undefined);setExecutionId(undefined);setCancelPending(false);setHttpTerminal(false);setTracking('idle');setAuthoritativeElapsed(undefined)
    try{
      const classified=await api.classifySql(candidate.trim(),controller.signal);if(!isCurrent(token)||controller.signal.aborted)return
      setClassification(classified)
      if(classified.queryOnly){const id=uuid();if(!id)throw new Error('浏览器无法生成安全执行标识，已阻止执行。');startExecution(id,current)
        const result=await api.query({connectionId:current.connectionId,executionId:id,sql:candidate.trim(),...current.limits})
        if(!isCurrent(token,id))return;if(result.executionId!==id){beginUnknownTracking(token,id,'执行响应标识不匹配，结果已丢弃。');return}if(!finishHttp(token,id,result.elapsedMillis))return;setQueryResult(result);setTab('result')
      }else{setPendingSql(candidate.trim());setAtomic(classified.atomicAllowed);setContinueOnError(false);setPurpose('');setAck(false);setConfirmOpen(true);setBusy(false)}
    }catch(e:unknown){if(isCurrent(token)&&!(e instanceof DOMException&&e.name==='AbortError')){const id=activeExecution.current;if(id)beginUnknownTracking(token,id,(e as Error).message||'执行传输失败。',(e as {correlationId?:string}).correlationId);else{setError({message:(e as Error).message||'SQL 分类失败。',correlationId:(e as {correlationId?:string}).correlationId});setTracking('idle');setCancelPending(false);setHttpTerminal(false);setBusy(false)}}}
  }
  const confirmMutation=async()=>{
    const current=snapshot;if(!purpose||!ack||!classification||!current||current.token!==generation.current)return
    const id=uuid();if(!id){setError({message:'浏览器无法生成安全执行标识，已阻止执行。'});return}
    setConfirmOpen(false);setBusy(true);startExecution(id,current)
    try{const result=await api.execute({connectionId:current.connectionId,executionId:id,sql:pendingSql,purpose,atomic:classification.atomicAllowed&&atomic,continueOnError:!atomic&&continueOnError,timeoutSeconds:current.limits.timeoutSeconds})
      if(!isCurrent(current.token,id))return;if(result.executionId!==id){beginUnknownTracking(current.token,id,'执行响应标识不匹配，结果已丢弃。');return}if(!finishHttp(current.token,id,result.elapsedMillis))return;setExecuteResult(result);setTab('messages')
    }catch(e:unknown){if(isCurrent(current.token,id))beginUnknownTracking(current.token,id,(e as Error).message||'执行传输失败。',(e as {correlationId?:string}).correlationId)}finally{if(isCurrent(current.token,id))setPendingSql('')}
  }
  const cancel=async()=>{const id=executionId,token=generation.current;if(!id||!cancellable)return;setCancelPending(true);try{const result=await api.cancelExecution(id);if(!isCurrent(token,id))return;if(!result.cancelRequested){setError({message:'任务可能已结束，正在重新查询状态。'});setCancelPending(false);stopReconciliation();void pollExecution(token,id,0)}}catch(e:unknown){if(isCurrent(token,id)){setError({message:(e as Error).message||'无法请求取消。',correlationId:(e as {correlationId?:string}).correlationId});setCancelPending(false)}}}
  const selectTab=(next:OutputTab)=>{setTab(next);tabRefs.current[next]?.focus()}
  const tabKey=(event:React.KeyboardEvent<HTMLButtonElement>,current:OutputTab)=>{const index=tabs.findIndex(item=>item.id===current);let next:number|undefined;if(event.key==='ArrowRight')next=(index+1)%tabs.length;else if(event.key==='ArrowLeft')next=(index-1+tabs.length)%tabs.length;else if(event.key==='Home')next=0;else if(event.key==='End')next=tabs.length-1;if(next!==undefined){event.preventDefault();selectTab(tabs[next].id)}}

  return <><PageHeader eyebrow="MANUAL SQL" title="SQL 执行控制台" description="先由 DM 语法解析器分类，再通过已知执行标识跟踪全过程。" />
    <section className="sql-workbench"><div className="sql-toolbar"><label>连接<select aria-label="连接" disabled={controlsLocked} value={connectionId} onChange={e=>setConnectionId(e.target.value)}><option value="">选择连接</option>{connections.map(item=><option key={item.id} value={item.id}>{item.name}{item.isDefault?'（默认）':''}</option>)}</select></label><label>最大行数<input aria-label="最大行数" disabled={controlsLocked} type="number" value={limits.maxRows} onChange={e=>setLimits({...limits,maxRows:Number(e.target.value)})}/></label><label>超时（秒）<input aria-label="超时（秒）" disabled={controlsLocked} type="number" value={limits.timeoutSeconds} onChange={e=>setLimits({...limits,timeoutSeconds:Number(e.target.value)})}/></label><label>最大字节<input aria-label="最大字节" disabled={controlsLocked} type="number" value={limits.maxBytes} onChange={e=>setLimits({...limits,maxBytes:Number(e.target.value)})}/></label></div>
      {snapshot&&<p className="execution-snapshot">执行快照 · {snapshot.connectionName} · {snapshot.limits.maxRows.toLocaleString('en-US')} 行 · {snapshot.limits.maxBytes.toLocaleString('en-US')} bytes · {snapshot.limits.timeoutSeconds} 秒{snapshot.executionId?` · ${snapshot.executionId}`:''}</p>}
      {tracking==='unknown'&&<div className="tracking-unknown" role="status"><span>执行状态待确认；已锁定执行快照，正在通过已知 UUID 对账。</span><button className="button-secondary" onClick={retryTracking}>重新查询状态</button></div>}
      {invalidLimits&&<p className="field-error" role="alert">限制范围：1–10,000 行，1 KiB–50 MiB，1–3,600 秒。</p>}
      <p id="sql-editor-help" className="editor-help">Ctrl/Cmd + Enter 执行选区；无选区时执行全文。SQL 仅保存在当前页面内存。</p><SqlEditor value={sql} theme={theme} disabled={controlsLocked} onChange={setSql} onSelectionChange={setSelection} onRun={run}/>
      <div className="sql-actions"><button className="button-primary" disabled={controlsLocked||invalidLimits||!selection.trim()} onClick={()=>void run(selection)}><Play size={15}/>执行选中</button><button className="button-primary" disabled={controlsLocked||invalidLimits||!sql.trim()} onClick={()=>void run(sql)}><Play size={15}/>执行全部</button><button className="button-secondary" disabled={!cancellable} onClick={()=>void cancel()}><Square size={14}/>{cancelPending?'正在请求取消':'取消执行'}</button><button className="button-secondary" aria-label="清空编辑器" disabled={controlsLocked||!sql.trim()} onClick={()=>setClearOpen(true)}><Trash2 size={14}/>清空</button></div>
    </section>
    {error&&<div className="sql-safe-error" role="alert"><strong>{error.message}</strong>{error.correlationId&&<span>关联 ID：{error.correlationId}</span>}</div>}
    <section className="sql-output"><div className="output-tabs" role="tablist" aria-label="SQL 输出">{tabs.map(item=><button key={item.id} ref={element=>{tabRefs.current[item.id]=element}} id={`sql-tab-${item.id}`} role="tab" tabIndex={tab===item.id?0:-1} aria-selected={tab===item.id} aria-controls={`sql-panel-${item.id}`} onClick={()=>selectTab(item.id)} onKeyDown={event=>tabKey(event,item.id)}>{item.label}</button>)}</div>
      <div id="sql-panel-result" role="tabpanel" aria-labelledby="sql-tab-result" hidden={tab!=='result'}>{queryResult?<ResultGrid result={queryResult}/>:<p className="quiet-copy">查询结果将在此显示。</p>}</div>
      <div id="sql-panel-messages" role="tabpanel" aria-labelledby="sql-tab-messages" hidden={tab!=='messages'}>{executeResult?<MutationMessages result={executeResult}/>:<p className="quiet-copy">修改语句的逐条提交与记录状态将在此显示。</p>}</div>
      <div id="sql-panel-timeline" role="tabpanel" aria-labelledby="sql-tab-timeline" hidden={tab!=='timeline'}><ExecutionTimeline executionId={executionId} events={events} streamStatus={streamStatus} authoritativeElapsedMillis={authoritativeElapsed}/></div>
    </section>
    <ConfirmDialog open={confirmOpen} title="确认修改操作" confirmLabel="确认并执行" confirmDisabled={!purpose||!ack} onClose={()=>{setConfirmOpen(false);setPendingSql('')}} onConfirm={()=>void confirmMutation()}><div className="mutation-confirm"><label>用途<select aria-label="用途" required value={purpose} onChange={e=>setPurpose(e.target.value as SqlPurpose)}><option value="">请选择</option>{purposes.map(item=><option key={item.value} value={item.value}>{item.label}</option>)}</select></label><p>PRODUCTION_CHANGE、MIGRATION 会写入发版日志；TEST、MOCK、SEED、SAMPLE 会明确排除。</p><label><input aria-label="原子执行" type="checkbox" checked={atomic} disabled={!classification?.atomicAllowed} onChange={e=>{setAtomic(e.target.checked);if(e.target.checked)setContinueOnError(false)}}/> 原子执行（仅纯 DML）</label><label><input type="checkbox" checked={continueOnError} disabled={atomic} onChange={e=>setContinueOnError(e.target.checked)}/> 失败后继续</label><label><input type="checkbox" checked={ack} onChange={e=>setAck(e.target.checked)}/> 我已核对 SQL 与目标连接</label></div></ConfirmDialog>
    <ConfirmDialog open={clearOpen} title="清空 SQL" confirmLabel="确认清空" onClose={()=>setClearOpen(false)} onConfirm={()=>{setSql('');setClearOpen(false)}}><p>当前编辑内容将从内存中清除，且无法恢复。</p></ConfirmDialog>
  </>
}

const SafeError=({error}:{error:SafeExecutionError})=><div className="result-error" role="alert"><strong>{error.phase} · {error.message}</strong><span>关联 {error.correlationId}{error.sqlState?` · SQLState ${error.sqlState}`:''}{error.errorCode!==null?` · DB ${error.errorCode}`:''}{error.restartRequired?' · 需要重启':''}</span></div>
export function MutationMessages({result}:{result:ExecuteResult}){return <div className="mutation-messages"><div className="result-summary"><span>{result.success?'执行完成':'部分或全部失败'}</span><span>{result.elapsedMillis} ms</span><span>DB {result.databaseFingerprint.slice(0,12)}</span></div>{result.error&&<SafeError error={result.error}/>}<ol>{result.statements.map(item=><li key={item.index}><strong>第 {item.index+1} 条 · {item.kind}</strong><span>{item.success?'成功':'失败'} · {item.committed?'已提交':'未提交'}（{item.commitBehavior}）· {item.rowCount} 行 · {item.elapsedMillis} ms · {item.recorded?'已记录':item.exclusionReason??'未记录'}</span>{item.error&&<SafeError error={item.error}/>}</li>)}</ol></div>}
