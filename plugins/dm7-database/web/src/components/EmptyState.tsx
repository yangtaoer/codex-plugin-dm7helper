import type { ReactNode } from 'react'

export function EmptyState({ title, description, action }: { title: string; description?: string; action?: ReactNode }) {
  return <section className="empty-state"><span className="empty-mark" aria-hidden="true">DM</span><h2>{title}</h2>{description && <p>{description}</p>}{action}</section>
}
