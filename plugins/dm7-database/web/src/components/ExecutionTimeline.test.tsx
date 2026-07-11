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
})
