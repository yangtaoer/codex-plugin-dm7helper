import type { PropsWithChildren } from 'react'

export function StatusBadge({ tone = 'neutral', children }: PropsWithChildren<{ tone?: 'neutral' | 'success' | 'warning' | 'danger' }>) {
  return <span className="status-badge" data-tone={tone}><span aria-hidden="true" className="status-dot" />{children}</span>
}
