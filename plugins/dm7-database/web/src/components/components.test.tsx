import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ConfirmDialog } from './ConfirmDialog'
import { DataTable } from './DataTable'
import { EmptyState } from './EmptyState'
import { StatusBadge } from './StatusBadge'

describe('accessible foundations', () => {
  it('renders a semantic table with accessible column headings', () => {
    render(<DataTable caption="执行列表" columns={[{ key: 'name', label: '名称' }]} rows={[{ name: '达梦数据库' }]} />)
    expect(screen.getByRole('table', { name: '执行列表' })).toBeTruthy()
    expect(screen.getByRole('columnheader', { name: '名称' })).toBeTruthy()
  })

  it('exposes status as text instead of color alone and renders empty actions', () => {
    render(<><StatusBadge tone="success">已连接</StatusBadge><EmptyState title="暂无数据" action={<button>新建</button>} /></>)
    expect(screen.getByText('已连接').getAttribute('data-tone')).toBe('success')
    expect(screen.getByRole('button', { name: '新建' })).toBeTruthy()
  })

  it('traps focus, closes on Escape, and returns focus', () => {
    const close = vi.fn()
    render(<><button data-testid="opener">打开</button><ConfirmDialog open title="确认操作" onClose={close} confirmLabel="确认" onConfirm={vi.fn()}><p>内容</p></ConfirmDialog></>)
    const opener = screen.getByTestId('opener')
    opener.focus()
    const dialog = screen.getByRole('dialog', { name: '确认操作' })
    const cancel = screen.getByRole('button', { name: '取消' })
    const confirm = screen.getByRole('button', { name: '确认' })
    confirm.focus()
    fireEvent.keyDown(dialog, { key: 'Tab' })
    expect(document.activeElement).toBe(cancel)
    fireEvent.keyDown(dialog, { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(confirm)
    opener.focus()
    fireEvent.keyDown(dialog, { key: 'Escape' })
    expect(close).toHaveBeenCalled()
    expect(document.activeElement).toBe(opener)
  })
})
