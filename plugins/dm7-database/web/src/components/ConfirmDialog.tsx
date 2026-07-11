import { useEffect, useRef, type PropsWithChildren } from 'react'

export function ConfirmDialog({ open, title, children, confirmLabel, onConfirm, onClose }: PropsWithChildren<{ open: boolean; title: string; confirmLabel: string; onConfirm(): void; onClose(): void }>) {
  const dialog = useRef<HTMLDivElement>(null)
  const returnFocus = useRef<HTMLElement | null>(null)
  useEffect(() => {
    if (!open) return
    returnFocus.current = document.activeElement as HTMLElement
    const first = dialog.current?.querySelector<HTMLElement>('button')
    first?.focus()
    return () => returnFocus.current?.focus()
  }, [open])
  if (!open) return null
  const close = () => { onClose(); returnFocus.current?.focus() }
  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'Escape') { event.preventDefault(); close(); return }
    if (event.key !== 'Tab' || !dialog.current) return
    const focusable = [...dialog.current.querySelectorAll<HTMLElement>('button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled])')]
    if (!focusable.length) return
    const first = focusable[0], last = focusable.at(-1)!
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
  }
  return <div className="dialog-backdrop"><div ref={dialog} role="dialog" aria-modal="true" aria-labelledby="dialog-title" className="dialog" onKeyDown={onKeyDown}><div className="dialog-rule" /><h2 id="dialog-title">{title}</h2><div>{children}</div><div className="dialog-actions"><button className="button-secondary" onClick={close}>取消</button><button className="button-primary" onClick={onConfirm}>{confirmLabel}</button></div></div></div>
}
