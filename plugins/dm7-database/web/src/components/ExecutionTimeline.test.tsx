import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ExecutionTimeline } from './ExecutionTimeline'

describe('ExecutionTimeline', () => {
  it('filters, orders and deduplicates progress from the shared stream', () => {
    render(<ExecutionTimeline executionId="run" streamStatus="reconnecting" events={[
      { id: '2', executionId: 'run', status: 'executing', timestamp: '2026-01-01T00:00:02Z', detail: '执行中' },
      { id: '1', executionId: 'other', status: 'queued', timestamp: '2026-01-01T00:00:00Z', detail: '别的任务' },
      { id: '2', executionId: 'run', status: 'executing', timestamp: '2026-01-01T00:00:02Z', detail: '重复' },
      { id: '1', executionId: 'run', status: 'queued', timestamp: '2026-01-01T00:00:01Z', detail: '已排队' },
    ]} />)
    expect(screen.getByText('实时通道正在重连')).toBeTruthy()
    expect(screen.queryByText('别的任务')).toBeNull()
    expect(screen.getAllByRole('listitem')).toHaveLength(2)
    expect(screen.getAllByRole('listitem')[0].textContent).toContain('QUEUED')
  })
  it('shows stable sequence, timestamp, stage and total elapsed through the first terminal event', () => {
    render(<ExecutionTimeline executionId="run" streamStatus="connected" events={[
      {id:'4',executionId:'run',status:'executing',timestamp:'2026-01-01T00:00:00.500Z',detail:'迟到活动'},
      {id:'3',executionId:'run',status:'completed',timestamp:'2026-01-01T00:00:03.000Z',detail:'完成'},
      {id:'1',executionId:'run',status:'queued',timestamp:'2026-01-01T00:00:01.000Z',detail:'排队'},
      {id:'2',executionId:'run',status:'executing',timestamp:'2026-01-01T00:00:02.250Z',detail:'执行'},
    ]}/>)
    expect(screen.getAllByRole('listitem')).toHaveLength(3)
    expect(screen.getByText(/SEQ 1/)).toBeTruthy()
    expect(screen.getByText(/\+1,250 ms · 总计 1,250 ms/)).toBeTruthy()
    expect(screen.getByText(/总耗时 2,000 ms/)).toBeTruthy()
    expect(screen.queryByText('迟到活动')).toBeNull()
  })
})
