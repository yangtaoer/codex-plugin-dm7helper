import type { EventRecord } from '../api/types'
import type { StreamStatus } from '../hooks/useEventStream'

const labels: Record<string,string>={queued:'QUEUED',connecting:'CONNECTING',parsing:'PARSING',executing:'EXECUTING',committing:'COMMITTING',logging:'LOGGING',completed:'COMPLETED',failed:'FAILED',cancelled:'CANCELLED',rejected:'REJECTED'}
const terminal=new Set(['completed','failed','cancelled','rejected'])
const millis=(value:string)=>{const parsed=Date.parse(value);return Number.isFinite(parsed)?parsed:0}
const format=(value:number)=>Math.max(0,Math.round(value)).toLocaleString('en-US')

export function ExecutionTimeline({ executionId, events, streamStatus }: { executionId?: string; events: EventRecord[]; streamStatus: StreamStatus }) {
  const seen=new Set<string>()
  const ordered=events.filter(event=>event.executionId===executionId).toSorted((a,b)=>Number(a.id)-Number(b.id)).filter(event=>{const key=event.id||`${event.timestamp}:${event.status}`;if(seen.has(key))return false;seen.add(key);return true})
  const terminalIndex=ordered.findIndex(event=>terminal.has(event.status)),selected=terminalIndex<0?ordered:ordered.slice(0,terminalIndex+1)
  const started=selected.length?millis(selected[0].timestamp):0
  const total=selected.length?Math.max(0,millis(selected.at(-1)!.timestamp)-started):0
  return <section className="execution-timeline" aria-label="实时过程">
    <div className="section-heading"><span>EXECUTION SIGNAL</span><strong>{streamStatus==='connected'?'实时通道已连接':streamStatus==='reconnecting'?'实时通道正在重连':streamStatus==='resyncing'?'正在重新同步':'实时通道连接中'}</strong></div>
    {!executionId?<p className="quiet-copy">执行后将在此显示十阶段状态。</p>:!selected.length?<p className="quiet-copy">等待执行事件…</p>:<><ol>{selected.map((event,index)=>{const at=millis(event.timestamp),previous=index?millis(selected[index-1].timestamp):at;return <li key={event.id||`${event.timestamp}-${event.status}`}><code>{labels[event.status]??event.status.toUpperCase()}</code><span className="event-sequence">SEQ {event.id}</span><time dateTime={event.timestamp}>{new Date(at).toLocaleString('zh-CN',{hour12:false})}</time><span>{event.detail}</span><small>+{format(at-previous)} ms · 总计 {format(at-started)} ms</small></li>})}</ol>{terminalIndex>=0&&<p className="timeline-total">总耗时 {format(total)} ms · {labels[selected.at(-1)!.status]}</p>}</>}
  </section>
}
