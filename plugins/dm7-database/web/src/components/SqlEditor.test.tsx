import { render } from '@testing-library/react'
import { EditorView, runScopeHandlers } from '@codemirror/view'
import { describe, expect, it, vi } from 'vitest'
import { SqlEditor } from './SqlEditor'

describe('SqlEditor', () => {
  it('places the per-response CSP nonce on generated CodeMirror styles and destroys cleanly', () => {
    const meta=document.createElement('meta');meta.name='csp-nonce';meta.content='safeNonce_123456789012345678901234567890';document.head.append(meta)
    const {unmount}=render(<SqlEditor value="select 1" theme="light" onChange={vi.fn()} onRun={vi.fn()}/>)
    expect(document.head.querySelector('style[nonce="safeNonce_123456789012345678901234567890"]')).toBeTruthy()
    unmount();meta.remove()
    expect(document.querySelector('.cm-editor')).toBeNull()
  })
  it('never falls back to dangerous full SQL for a nonempty whitespace selection', () => {
    const onRun=vi.fn(),sql='DELETE FROM PROD;\n   '
    render(<SqlEditor value={sql} theme="light" onChange={vi.fn()} onRun={onRun}/>)
    const view=EditorView.findFromDOM(document.querySelector('.cm-editor')!)!
    const from=sql.indexOf('   ');view.dispatch({selection:{anchor:from,head:from+3}});view.focus()
    runScopeHandlers(view,new KeyboardEvent('keydown',{key:'Enter',ctrlKey:true}),'editor')
    expect(onRun).toHaveBeenCalledWith('   ')
    expect(onRun).not.toHaveBeenCalledWith(sql)
  })
  it('reconfigures theme without losing document selection or focus', () => {
    const onRun=vi.fn(),onSelectionChange=vi.fn(),sql='SELECT SAFE; DELETE DANGER'
    const rendered=render(<SqlEditor value={sql} theme="light" onChange={vi.fn()} onRun={onRun} onSelectionChange={onSelectionChange}/>)
    const view=EditorView.findFromDOM(document.querySelector('.cm-editor')!)!
    view.dispatch({selection:{anchor:0,head:11}});view.focus()
    rendered.rerender(<SqlEditor value={sql} theme="dark" onChange={vi.fn()} onRun={onRun} onSelectionChange={onSelectionChange}/>)
    const after=EditorView.findFromDOM(document.querySelector('.cm-editor')!)!
    expect(after).toBe(view);expect(after.state.doc.toString()).toBe(sql);expect(after.state.sliceDoc(0,11)).toBe('SELECT SAFE')
    expect(after.hasFocus).toBe(true)
    runScopeHandlers(after,new KeyboardEvent('keydown',{key:'Enter',ctrlKey:true}),'editor')
    expect(onRun).toHaveBeenLastCalledWith('SELECT SAFE')
    after.dispatch({selection:{anchor:0}})
    expect(onSelectionChange).toHaveBeenLastCalledWith('')
  })
})
