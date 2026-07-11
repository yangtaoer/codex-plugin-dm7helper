import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
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
  afterEach(() => { FakeEventSource.instances = []; vi.unstubAllGlobals() })

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
})
