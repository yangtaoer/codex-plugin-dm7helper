import type { EventRecord } from '../api/types'
import type { StreamStatus } from '../hooks/useEventStream'

const labels: Record<string,string>={queued:'QUEUED',connecting:'CONNECTING',parsing:'PARSING',executing:'EXECUTING',committing:'COMMITTING',logging:'LOGGING',completed:'COMPLETED',failed:'FAILED',cancelled:'CANCELLED',rejected:'REJECTED'}

export function ExecutionTimeline({ executionId, events, streamStatus }: { executionId?: string; events: EventRecord[]; streamStatus: StreamStatus }) {
  const seen=new Set<string>()
  const selected=events.filter(event=>event.executionId===executionId).sort((a,b)=>Number(a.id)-Number(b.id)).filter(event=>{const key=event.id||`${event.timestamp}:${event.status}`;if(seen.has(key))return false;seen.add(key);return true})
  return <section className="execution-timeline" aria-label="实时过程">
    <div className="section-heading"><span>EXECUTION SIGNAL</span><strong>{streamStatus==='connected'?'实时通道已连接':streamStatus==='reconnecting'?'实时通道正在重连':streamStatus==='resyncing'?'正在重新同步':'实时通道连接中'}</strong></div>
    {!executionId?<p className="quiet-copy">执行后将在此显示十阶段状态。</p>:!selected.length?<p className="quiet-copy">等待执行事件…</p>:<ol>{selected.map(event=><li key={event.id||`${event.timestamp}-${event.status}`}><code>{labels[event.status]??event.status.toUpperCase()}</code><time dateTime={event.timestamp}>{new Date(event.timestamp).toLocaleTimeString('zh-CN',{hour12:false})}</time><span>{event.detail}</span></li>)}</ol>}
  </section>
}
