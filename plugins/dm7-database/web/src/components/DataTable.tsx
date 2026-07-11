import type { ReactNode } from 'react'

type Column<Row> = { key: keyof Row & string; label: string; render?: (value: Row[keyof Row], row: Row) => ReactNode }
export function DataTable<Row extends Record<string, unknown>>({ caption, columns, rows }: { caption: string; columns: Column<Row>[]; rows: Row[] }) {
  return <div className="table-scroll"><table><caption>{caption}</caption><thead><tr>{columns.map((column) => <th key={column.key} scope="col">{column.label}</th>)}</tr></thead><tbody>{rows.map((row, index) => <tr key={index}>{columns.map((column) => <td key={column.key}>{column.render ? column.render(row[column.key], row) : String(row[column.key] ?? '')}</td>)}</tr>)}</tbody></table></div>
}
