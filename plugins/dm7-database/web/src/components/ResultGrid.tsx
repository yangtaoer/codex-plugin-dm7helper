import { flexRender, getCoreRowModel, getSortedRowModel, useReactTable, type SortingState } from '@tanstack/react-table'
import { useVirtualizer } from '@tanstack/react-virtual'
import { useMemo, useRef, useState } from 'react'
import type { QueryResult } from '../api/types'

const printable=(value:unknown)=>value===null?'NULL':typeof value==='string'?value:typeof value==='object'?JSON.stringify(value):String(value)
const csvCell=(value:unknown)=>{let text=printable(value);if(/^[=+\-@\t\r]/.test(text))text="'\t"+text;return /[",\r\n]/.test(text)?`"${text.replaceAll('"','""')}"`:text}
export const csvText=(result:QueryResult)=>'\uFEFF'+[result.columns.map(column=>csvCell(column.outputLabel)).join(','),...result.rows.map(row=>result.columns.map(column=>csvCell(row[column.outputLabel])).join(','))].join('\r\n')
export const jsonText=(result:QueryResult)=>JSON.stringify({columns:result.columns,rows:result.rows,truncated:result.truncated,returnedRows:result.returnedRows,bytes:result.bytes,databaseFingerprint:result.databaseFingerprint},null,2)
const save=(filename:string,text:string,type:string)=>{const url=URL.createObjectURL(new Blob([text],{type}));const link=document.createElement('a');link.href=url;link.download=filename;link.click();setTimeout(()=>URL.revokeObjectURL(url),0)}
const safeName=(kind:string)=>`dm7-result-${new Date().toISOString().replace(/[:.]/g,'-')}.${kind}`

export function ResultGrid({ result }: { result: QueryResult }) {
  const [sorting,setSorting]=useState<SortingState>([]),[copyStatus,setCopyStatus]=useState('')
  const columns=useMemo(()=>result.columns.map(column=>({id:column.outputLabel,accessorFn:(row:Record<string,unknown>)=>row[column.outputLabel],header:column.outputLabel,cell:(ctx:{getValue():unknown})=>printable(ctx.getValue())})),[result.columns])
  const table=useReactTable({data:result.rows,columns,state:{sorting},onSortingChange:setSorting,getCoreRowModel:getCoreRowModel(),getSortedRowModel:getSortedRowModel(),columnResizeMode:'onChange'})
  const parent=useRef<HTMLDivElement>(null),rows=table.getRowModel().rows
  const virtual=useVirtualizer({count:rows.length,getScrollElement:()=>parent.current,estimateSize:()=>38,overscan:8})
  const copy=async(value:string)=>{try{await navigator.clipboard.writeText(value);setCopyStatus('已复制')}catch{const area=document.createElement('textarea');area.value=value;area.setAttribute('readonly','');document.body.append(area);area.select();let copied=false;try{copied=document.execCommand?.('copy')??false}finally{area.remove()}setCopyStatus(copied?'已复制':'复制失败，请手动选择')}}
  const visible=rows.length>100?virtual.getVirtualItems().map(item=>({row:rows[item.index],position:item.start})):rows.map(row=>({row,position:0}))
  const nonce=document.querySelector<HTMLMetaElement>('meta[name="csp-nonce"]')?.content
  const dynamicCss=`.result-scroll{height:${Math.min(440,Math.max(120,rows.length*38+42))}px}`+
    table.getAllLeafColumns().map((column,index)=>`.result-scroll th[data-column="${index}"]{width:${column.getSize()}px}`).join('')+
    (rows.length>100?`.result-scroll tbody{height:${virtual.getTotalSize()}px;position:relative}`+visible.map(({row,position})=>`.result-scroll tr[data-row="${row.id}"]{position:absolute;transform:translateY(${position}px);width:100%;display:table}`).join(''):'')
  return <section className="result-panel">{nonce&&<style nonce={nonce}>{dynamicCss}</style>}<div className="result-summary"><span>{result.returnedRows} 行</span><span>{result.elapsedMillis} ms</span><span>{result.bytes} bytes</span><span title={result.databaseFingerprint}>DB {result.databaseFingerprint.slice(0,12)}</span>{result.truncated&&<strong>结果已截断</strong>}<button disabled={!result.success||!result.rows.length} onClick={()=>save(safeName('csv'),csvText(result),'text/csv;charset=utf-8')}>下载 CSV</button><button disabled={!result.success||!result.rows.length} onClick={()=>save(safeName('json'),jsonText(result),'application/json;charset=utf-8')}>下载 JSON</button></div>
    {result.error&&<div className="result-error" role="alert"><strong>{result.error.phase} · {result.error.message}</strong><span>关联 {result.error.correlationId}{result.error.sqlState?` · SQLState ${result.error.sqlState}`:''}{result.error.errorCode!==null?` · DB ${result.error.errorCode}`:''}{result.error.restartRequired?' · 需要重启':''}</span></div>}
    <div ref={parent} className="result-scroll"><table><thead>{table.getHeaderGroups().map(group=><tr key={group.id}>{group.headers.map((header,index)=><th key={header.id} data-column={index}><button onClick={header.column.getToggleSortingHandler()}>{flexRender(header.column.columnDef.header,header.getContext())}</button><span role="separator" aria-label={`调整 ${header.id} 列宽`} onMouseDown={header.getResizeHandler()} /></th>)}<th>操作</th></tr>)}</thead><tbody>{visible.map(({row})=><tr key={row.id} data-row={row.id}>{row.getVisibleCells().map(cell=><td key={cell.id} tabIndex={0} onDoubleClick={()=>void copy(printable(cell.getValue()))}>{flexRender(cell.column.columnDef.cell,cell.getContext())}<button className="cell-copy" aria-label={`复制第 ${row.index+1} 行${cell.column.id}单元格`} onClick={()=>void copy(printable(cell.getValue()))}>复制</button></td>)}<td><button className="row-copy" aria-label={`复制第 ${row.index+1} 行`} onClick={()=>void copy(result.columns.map(column=>printable(row.original[column.outputLabel])).join('\t'))}>复制行</button></td></tr>)}</tbody></table></div><div className="sr-only" aria-live="polite">{copyStatus}</div></section>
}
