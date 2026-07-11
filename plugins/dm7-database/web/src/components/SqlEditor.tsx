import { closeBrackets, closeBracketsKeymap } from '@codemirror/autocomplete'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { sql } from '@codemirror/lang-sql'
import { bracketMatching, indentOnInput } from '@codemirror/language'
import { Compartment, EditorState } from '@codemirror/state'
import { EditorView, highlightActiveLine, keymap, lineNumbers } from '@codemirror/view'
import { useEffect, useRef } from 'react'

const nonce = () => document.querySelector<HTMLMetaElement>('meta[name="csp-nonce"]')?.content ?? ''
const editorTheme=(theme:string)=>EditorView.theme({ '&':{height:'100%'},'.cm-scroller':{fontFamily:'ui-monospace, Cascadia Mono, monospace'},'&.cm-focused':{outline:'none'}},{dark:theme==='dark'})

export function SqlEditor({ value, theme, disabled=false, onChange, onRun, onSelectionChange }: { value: string; theme: string; disabled?:boolean; onChange(value: string): void; onRun(value: string): void; onSelectionChange?(value:string):void }) {
  const host=useRef<HTMLDivElement>(null),view=useRef<EditorView|null>(null)
  const run=useRef(onRun),change=useRef(onChange),selectionChange=useRef(onSelectionChange)
  const themeCompartment=useRef(new Compartment()),editableCompartment=useRef(new Compartment())
  run.current=onRun;change.current=onChange;selectionChange.current=onSelectionChange
  useEffect(() => {
    if(!host.current)return
    const csp=nonce(),themeSlot=themeCompartment.current,editableSlot=editableCompartment.current
    const instance=new EditorView({ parent:host.current, state:EditorState.create({ doc:value, extensions:[
      lineNumbers(),highlightActiveLine(),history(),sql(),bracketMatching(),closeBrackets(),indentOnInput(),
      keymap.of([{ key:'Ctrl-Enter', mac:'Cmd-Enter', run(editor){const range=editor.state.selection.main;run.current(range.empty?editor.state.doc.toString():editor.state.sliceDoc(range.from,range.to));return true} },...closeBracketsKeymap,...defaultKeymap,...historyKeymap]),
      EditorView.contentAttributes.of({ 'aria-label':'SQL 编辑器','aria-describedby':'sql-editor-help','spellcheck':'false' }),
      EditorView.updateListener.of((update)=>{if(update.docChanged)change.current(update.state.doc.toString());if(update.docChanged||update.selectionSet){const range=update.state.selection.main;selectionChange.current?.(update.state.sliceDoc(range.from,range.to))}}),
      themeSlot.of(editorTheme(theme)),editableSlot.of([EditorState.readOnly.of(disabled),EditorView.editable.of(!disabled)]),
      ...(csp?[EditorView.cspNonce.of(csp)]:[]),
    ]}) })
    view.current=instance;selectionChange.current?.('')
    return()=>{view.current=null;instance.destroy()}
  }, [])
  useEffect(()=>{view.current?.dispatch({effects:themeCompartment.current.reconfigure(editorTheme(theme))})},[theme])
  useEffect(()=>{view.current?.dispatch({effects:editableCompartment.current.reconfigure([EditorState.readOnly.of(disabled),EditorView.editable.of(!disabled)])})},[disabled])
  useEffect(()=>{const current=view.current;if(current&&current.state.doc.toString()!==value)current.dispatch({changes:{from:0,to:current.state.doc.length,insert:value}})},[value])
  return <div className="sql-editor" data-testid="sql-editor" ref={host} />
}
