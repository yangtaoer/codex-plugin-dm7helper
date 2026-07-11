import { useEffect, useRef, useState } from 'react'
import { EXECUTION_EVENT_NAMES, type EventRecord } from '../api/types'

export type StreamStatus = 'connecting' | 'connected' | 'reconnecting' | 'resyncing'

export type EventStreamOptions = {
  maximumEvents?: number
  recoveryGraceMs?: number
  retryBaseMs?: number
  maximumBackoffMs?: number
  resync?: () => Promise<void>
}

export function useEventStream({
  maximumEvents = 200,
  recoveryGraceMs = 4_000,
  retryBaseMs = 500,
  maximumBackoffMs = 10_000,
  resync,
}: EventStreamOptions = {}) {
  const [events, setEvents] = useState<EventRecord[]>([])
  const [status, setStatus] = useState<StreamStatus>('connecting')
  const resyncRef = useRef(resync)
  resyncRef.current = resync

  useEffect(() => {
    let disposed = false
    let source: EventSource | null = null
    let recoveryTimer: ReturnType<typeof setTimeout> | null = null
    let retryTimer: ReturnType<typeof setTimeout> | null = null
    let recoveryAttempt = 0
    let recoveryGeneration = 0

    const clearRecoveryTimer = () => {
      if (recoveryTimer !== null) clearTimeout(recoveryTimer)
      recoveryTimer = null
    }
    const clearRetryTimer = () => {
      if (retryTimer !== null) clearTimeout(retryTimer)
      retryTimer = null
    }
    const receive = (event: Event) => {
      const message = event as MessageEvent<string>
      try {
        const parsed = JSON.parse(message.data) as Omit<EventRecord, 'id'>
        const record = { ...parsed, id: message.lastEventId }
        setEvents((current) => {
          if (record.id && current.some((existing) => existing.id === record.id)) return current
          return [...current, record].slice(-Math.max(1, maximumEvents))
        })
      } catch { /* REST state remains authoritative when an individual event is malformed. */ }
    }
    const detachAndClose = () => {
      if (!source) return
      EXECUTION_EVENT_NAMES.forEach((name) => source?.removeEventListener(name, receive))
      source.onopen = null
      source.onerror = null
      source.onmessage = null
      source.close()
      source = null
    }
    const connectFresh = () => {
      if (disposed || source) return
      source = new EventSource('/api/events', { withCredentials: true })
      source.onopen = () => {
        if (disposed) return
        clearRecoveryTimer()
        recoveryAttempt = 0
        setStatus('connected')
      }
      source.onerror = () => {
        if (disposed) return
        setStatus('reconnecting')
        if (recoveryTimer === null) recoveryTimer = setTimeout(recover, Math.max(0, recoveryGraceMs))
      }
      source.onmessage = receive
      EXECUTION_EVENT_NAMES.forEach((name) => source?.addEventListener(name, receive))
    }
    const recover = async () => {
      recoveryTimer = null
      if (disposed) return
      detachAndClose()
      setStatus('resyncing')
      const generation = ++recoveryGeneration
      try { await resyncRef.current?.() } catch { /* The fresh stream still retries after a failed REST refresh. */ }
      if (disposed || generation !== recoveryGeneration) return
      const delay = Math.min(Math.max(0, retryBaseMs) * 2 ** recoveryAttempt, Math.max(0, maximumBackoffMs))
      recoveryAttempt += 1
      retryTimer = setTimeout(() => {
        retryTimer = null
        if (disposed) return
        setStatus('reconnecting')
        connectFresh()
      }, delay)
    }

    connectFresh()
    return () => {
      disposed = true
      recoveryGeneration += 1
      clearRecoveryTimer()
      clearRetryTimer()
      detachAndClose()
    }
  }, [maximumEvents, maximumBackoffMs, recoveryGraceMs, retryBaseMs])

  return { events, status }
}
