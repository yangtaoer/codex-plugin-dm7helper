import { closeBrackets, closeBracketsKeymap } from '@codemirror/autocomplete'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { sql } from '@codemirror/lang-sql'
import { bracketMatching, indentOnInput } from '@codemirror/language'
import { EditorState } from '@codemirror/state'
import { EditorView, highlightActiveLine, keymap, lineNumbers } from '@codemirror/view'
import { useEffect, useRef } from 'react'

const nonce = () => document.querySelector<HTMLMetaElement>('meta[name="csp-nonce"]')?.content ?? ''

export function SqlEditor({ value, theme, onChange, onRun, onSelectionChange }: { value: string; theme: string; onChange(value: string): void; onRun(value: string): void; onSelectionChange?(value:string):void }) {
  const host=useRef<HTMLDivElement>(null),view=useRef<EditorView|null>(null),run=useRef(onRun)
  run.current=onRun
  useEffect(() => {
    if(!host.current)return
    const csp=nonce()
    const instance=new EditorView({ parent:host.current, state:EditorState.create({ doc:value, extensions:[
      lineNumbers(),highlightActiveLine(),history(),sql(),bracketMatching(),closeBrackets(),indentOnInput(),keymap.of([...closeBracketsKeymap,...defaultKeymap,...historyKeymap,{ key:'Ctrl-Enter', mac:'Cmd-Enter', run(editor){const range=editor.state.selection.main;const selected=editor.state.sliceDoc(range.from,range.to);run.current(selected.trim()?selected:editor.state.doc.toString());return true} }]),
      EditorView.contentAttributes.of({ 'aria-label':'SQL 编辑器','aria-describedby':'sql-editor-help','spellcheck':'false' }),
      EditorView.updateListener.of((update)=>{if(update.docChanged)onChange(update.state.doc.toString());if(update.docChanged||update.selectionSet){const range=update.state.selection.main;onSelectionChange?.(update.state.sliceDoc(range.from,range.to))}}),
      EditorView.theme({ '&':{height:'100%'},'.cm-scroller':{fontFamily:'ui-monospace, Cascadia Mono, monospace'},'&.cm-focused':{outline:'none'}},{dark:theme==='dark'}),
      ...(csp?[EditorView.cspNonce.of(csp)]:[]),
    ]}) })
    view.current=instance;return()=>{view.current=null;instance.destroy()}
  }, [theme])
  useEffect(()=>{const current=view.current;if(current&&current.state.doc.toString()!==value)current.dispatch({changes:{from:0,to:current.state.doc.length,insert:value}})},[value])
  return <div className="sql-editor" data-testid="sql-editor" ref={host} />
}
