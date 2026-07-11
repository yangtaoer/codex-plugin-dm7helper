import { useEffect, useState } from 'react'
import type { EventRecord } from '../api/types'

type StreamStatus = 'connecting' | 'connected' | 'reconnecting'

export function useEventStream({ maximumEvents = 200 }: { maximumEvents?: number } = {}) {
  const [events, setEvents] = useState<EventRecord[]>([])
  const [status, setStatus] = useState<StreamStatus>('connecting')

  useEffect(() => {
    const source = new EventSource('/api/events', { withCredentials: true })
    source.onopen = () => setStatus('connected')
    source.onerror = () => setStatus('reconnecting')
    const receive = (event: Event) => {
      const message = event as MessageEvent<string>
      try {
        const parsed = JSON.parse(message.data) as Omit<EventRecord, 'id'>
        const record = { ...parsed, id: message.lastEventId }
        setEvents((current) => [...current, record].slice(-Math.max(1, maximumEvents)))
      } catch { /* A malformed event is ignored; REST refresh remains authoritative. */ }
    }
    source.onmessage = receive
    const eventNames = ['queued', 'connecting', 'parsing', 'executing', 'committing', 'logging', 'completed', 'failed', 'cancelled']
    eventNames.forEach((name) => source.addEventListener(name, receive))
    return () => { eventNames.forEach((name) => source.removeEventListener(name, receive)); source.close() }
  }, [maximumEvents])

  return { events, status }
}
