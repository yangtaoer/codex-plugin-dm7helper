import { render } from '@testing-library/react'
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
})
