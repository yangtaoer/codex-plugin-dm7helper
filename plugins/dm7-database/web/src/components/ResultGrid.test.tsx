import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { QueryResult } from '../api/types'
import { ResultGrid, csvText, jsonText } from './ResultGrid'

const value: QueryResult = { executionId: 'x', success: true, columns: [
  { outputLabel: '名称', originalLabel: '名称', originalName: 'N', jdbcType: 12, typeName: 'VARCHAR' },
  { outputLabel: '空值', originalLabel: '空值', originalName: 'Z', jdbcType: 12, typeName: 'VARCHAR' },
], rows: [{ 名称: '达梦\n数据库', 空值: null }, { 名称: '=2+2', 空值: 'base64:AA==' }], truncated: true, returnedRows: 2, bytes: 30, elapsedMillis: 9, databaseFingerprint: 'abcdef', error: null }

describe('ResultGrid', () => {
  it('renders Chinese, null and truncation without HTML interpretation', () => {
    const rendered=render(<ResultGrid result={value} />)
    expect(screen.getByText(/达梦/)).toBeTruthy()
    expect(screen.getByText('NULL')).toBeTruthy()
    expect(screen.getByText(/结果已截断/)).toBeTruthy()
    expect(screen.getByRole('button',{name:'复制第 1 行'})).toBeTruthy()
    expect(screen.getByRole('button',{name:'复制第 1 行名称单元格'})).toBeTruthy()
    expect(rendered.container.querySelector('.result-table')?.getAttribute('data-virtualized')).toBe('false')
    expect(rendered.container.querySelectorAll('thead th')).toHaveLength(rendered.container.querySelectorAll('tbody tr:first-child td').length)
  })
  it('exports formula-safe BOM CSV and metadata-bearing no-BOM JSON', () => {
    const csv=csvText(value)
    expect(csv.charCodeAt(0)).toBe(0xfeff)
    expect(csv).toContain("'\t=2+2")
    expect(csv).toContain('"达梦\n数据库"')
    const json=jsonText(value)
    expect(json.charCodeAt(0)).not.toBe(0xfeff)
    const parsed=JSON.parse(json)
    expect(parsed.columns[0].outputLabel).toBe('名称')
    expect(parsed.truncated).toBe(true)
  })
  it('renders only the backend safe error fields', () => {
    render(<ResultGrid result={{...value,success:false,error:{correlationId:'corr-safe',phase:'EXECUTING',message:'查询失败',sqlState:'HY000',errorCode:6001,restartRequired:true}}}/>)
    expect(screen.getByRole('alert').textContent).toContain('EXECUTING · 查询失败')
    expect(screen.getByRole('alert').textContent).toContain('HY000')
    expect(screen.getByRole('alert').textContent).toContain('需要重启')
    expect((screen.getByRole('button',{name:'下载 CSV'}) as HTMLButtonElement).disabled).toBe(true)
  })
})
