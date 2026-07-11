import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useEventStream } from './useEventStream'

class FakeEventSource {
  static instances: FakeEventSource[] = []
  onopen: ((event: Event) => void) | null = null
  onmessage: ((event: MessageEvent) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  closed = false
  listeners = new Map<string, (event: MessageEvent) => void>()
  constructor(public url: string, public options?: EventSourceInit) { FakeEventSource.instances.push(this) }
  close() { this.closed = true }
  addEventListener(type: string, listener: EventListenerOrEventListenerObject) { this.listeners.set(type, listener as (event: MessageEvent) => void) }
  removeEventListener(type: string) { this.listeners.delete(type) }
}

describe('useEventStream', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => { FakeEventSource.instances = []; vi.unstubAllGlobals(); vi.useRealTimers() })

  it('connects same-origin with credentials, bounds events, reports reconnecting, and cleans up', () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const { result, unmount } = renderHook(() => useEventStream({ maximumEvents: 2 }))
    const source = FakeEventSource.instances[0]
    expect(source.url).toBe('/api/events')
    expect(source.options).toEqual({ withCredentials: true })
    act(() => source.onopen?.(new Event('open')))
    expect(result.current.status).toBe('connected')
    act(() => {
      source.onmessage?.(new MessageEvent('message', { data: JSON.stringify({ executionId: '1', status: 'queued', timestamp: '2026-01-01T00:00:00Z', detail: '一' }), lastEventId: '1' }))
      source.onmessage?.(new MessageEvent('message', { data: JSON.stringify({ executionId: '2', status: 'executing', timestamp: '2026-01-01T00:00:01Z', detail: '二' }), lastEventId: '2' }))
      source.onmessage?.(new MessageEvent('message', { data: JSON.stringify({ executionId: '3', status: 'completed', timestamp: '2026-01-01T00:00:02Z', detail: '三' }), lastEventId: '3' }))
    })
    expect(result.current.events.map((event) => event.id)).toEqual(['2', '3'])
    act(() => source.onerror?.(new Event('error')))
    expect(result.current.status).toBe('reconnecting')
    unmount()
    expect(source.closed).toBe(true)
  })

  it('receives the named lifecycle events emitted by the Task 8 server', () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const { result } = renderHook(() => useEventStream())
    const source = FakeEventSource.instances[0]
    act(() => source.listeners.get('executing')?.(new MessageEvent('executing', { data: JSON.stringify({ executionId: 'dm7-1', status: 'executing', timestamp: '2026-01-01T00:00:00Z', detail: '正在执行' }), lastEventId: '8' })))
    expect(result.current.events[0]).toMatchObject({ id: '8', executionId: 'dm7-1', status: 'executing' })
  })

  it('receives rejected as the final named lifecycle event', () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const { result } = renderHook(() => useEventStream())
    const source = FakeEventSource.instances[0]
    act(() => source.listeners.get('rejected')?.(new MessageEvent('rejected', { data: JSON.stringify({ executionId: 'dm7-rejected', status: 'rejected', timestamp: '2026-01-01T00:00:00Z', detail: '队列已满' }), lastEventId: '10' })))
    expect(result.current.events[0]).toMatchObject({ id: '10', executionId: 'dm7-rejected', status: 'rejected' })
  })

  it('lets a transient native reconnect retain Last-Event-ID without replacing the source', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const resync = vi.fn().mockResolvedValue(undefined)
    const { result } = renderHook(() => useEventStream({ recoveryGraceMs: 1_000, resync }))
    const source = FakeEventSource.instances[0]
    act(() => source.onerror?.(new Event('error')))
    expect(result.current.status).toBe('reconnecting')
    await act(async () => { vi.advanceTimersByTime(500); source.onopen?.(new Event('open')); await Promise.resolve() })
    vi.advanceTimersByTime(2_000)
    expect(FakeEventSource.instances).toHaveLength(1)
    expect(source.closed).toBe(false)
    expect(resync).not.toHaveBeenCalled()
    expect(result.current.status).toBe('connected')
  })

  it('recovers a replay-missed equivalent persistent error with REST resync and a fresh stream', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const resync = vi.fn().mockResolvedValue(undefined)
    const { result } = renderHook(() => useEventStream({ recoveryGraceMs: 100, retryBaseMs: 50, maximumBackoffMs: 200, resync }))
    const first = FakeEventSource.instances[0]
    act(() => { first.onerror?.(new Event('error')); first.onerror?.(new Event('error')) })
    await act(async () => { await vi.advanceTimersByTimeAsync(100) })
    expect(first.closed).toBe(true)
    expect(resync).toHaveBeenCalledTimes(1)
    expect(result.current.status).toBe('resyncing')
    await act(async () => { await vi.advanceTimersByTimeAsync(50) })
    expect(FakeEventSource.instances).toHaveLength(2)
    expect(FakeEventSource.instances[1]).not.toBe(first)
  })

  it('deduplicates replayed events and leaves no timer or source after unmount', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const resync = vi.fn().mockResolvedValue(undefined)
    const { result, unmount } = renderHook(() => useEventStream({ recoveryGraceMs: 100, resync }))
    const source = FakeEventSource.instances[0]
    const replay = new MessageEvent('executing', { data: JSON.stringify({ executionId: 'same', status: 'executing', timestamp: '2026-01-01T00:00:00Z', detail: '重放' }), lastEventId: '9' })
    act(() => { source.listeners.get('executing')?.(replay); source.listeners.get('executing')?.(replay); source.onerror?.(new Event('error')) })
    expect(result.current.events).toHaveLength(1)
    unmount()
    await vi.runAllTimersAsync()
    expect(source.closed).toBe(true)
    expect(FakeEventSource.instances).toHaveLength(1)
    expect(resync).not.toHaveBeenCalled()
    expect(vi.getTimerCount()).toBe(0)
  })
})
