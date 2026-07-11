import { AlertTriangle, Check, Copy, Database, Eye, EyeOff, FileKey, Gauge, Pencil, Plus, RefreshCw, Star, Trash2, X } from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ApiClient, ConnectionInput, ConnectionTestResult, SafeConnection, UrlDiagnostics } from '../api/types'
import { ApiError } from '../api/client'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { EmptyState } from '../components/EmptyState'
import { LoadingSkeleton } from '../components/Feedback'
import { PageHeader } from '../components/PageHeader'
import { StatusBadge } from '../components/StatusBadge'

type Mode = 'create' | 'edit' | 'copy'
type Drawer = { mode: Mode; source?: SafeConnection }
type Form = {
  name: string; driverJar: string; driverClass: string; jdbcUrl: string; username: string; password: string
  schema: string; isDefault: boolean; clearPassword: boolean; connectTimeoutSeconds: string
  socketTimeoutSeconds: string; queryTimeoutSeconds: string; maxRows: string; maxBytesMiB: string
}
type PageError = { message: string; correlationId?: string }

const emptyForm = (): Form => ({ name: '', driverJar: '', driverClass: 'dm.jdbc.driver.DmDriver', jdbcUrl: '', username: '', password: '', schema: '', isDefault: false, clearPassword: false, connectTimeoutSeconds: '10', socketTimeoutSeconds: '30', queryTimeoutSeconds: '60', maxRows: '1000', maxBytesMiB: '10' })
const fromProfile = (profile: SafeConnection, mode: Mode): Form => ({
  name: mode === 'copy' ? `${profile.name} - 副本` : profile.name,
  driverJar: '', driverClass: profile.driverClass ?? 'dm.jdbc.driver.DmDriver', jdbcUrl: '', username: profile.username ?? '',
  password: '', schema: profile.schema ?? '', isDefault: mode === 'edit' && profile.isDefault, clearPassword: false,
  connectTimeoutSeconds: String(profile.connectTimeoutSeconds ?? 10), socketTimeoutSeconds: String(profile.socketTimeoutSeconds ?? 30),
  queryTimeoutSeconds: String(profile.queryTimeoutSeconds ?? 60), maxRows: String(profile.maxRows ?? 1000),
  maxBytesMiB: String(Math.round((profile.maxBytes ?? 10 * 1024 * 1024) / 1024 / 1024)),
})
const safeError = (error: unknown, fallback: string): PageError => error instanceof ApiError ? { message: error.message, correlationId: error.correlationId } : { message: fallback }

export function ConnectionsPage({ api }: { api: ApiClient }) {
  const [connections, setConnections] = useState<SafeConnection[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<PageError>()
  const [drawer, setDrawer] = useState<Drawer>()
  const [form, setForm] = useState<Form>(emptyForm)
  const [initialForm, setInitialForm] = useState<Form>(emptyForm)
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  const [cardBusy, setCardBusy] = useState<Record<string, string>>({})
  const [cardErrors, setCardErrors] = useState<Record<string, string>>({})
  const [tests, setTests] = useState<Record<string, ConnectionTestResult>>({})
  const [diagnostics, setDiagnostics] = useState<UrlDiagnostics>()
  const [deleteTarget, setDeleteTarget] = useState<SafeConnection>()
  const [deleteDisposition, setDeleteDisposition] = useState('')
  const [unsavedOpen, setUnsavedOpen] = useState(false)
  const [drawerError, setDrawerError] = useState<PageError>()
  const [advanced, setAdvanced] = useState(false)
  const [showPassword, setShowPassword] = useState(false)
  const drawerRef = useRef<HTMLElement>(null)
  const openerRef = useRef<HTMLElement | null>(null)
  const firstInvalid = useRef<string | null>(null)
  const dirtyRef = useRef(false)
  const unsavedOpenRef = useRef(false)
  const drawerErrorRef = useRef<HTMLDivElement>(null)

  const refresh = useCallback(async (showLoading = true) => {
    if (showLoading) setLoading(true)
    setError(undefined)
    try { setConnections((await api.listConnections()).connections) }
    catch (cause) { setError(safeError(cause, '无法读取连接配置。')) }
    finally { if (showLoading) setLoading(false) }
  }, [api])
  useEffect(() => { void refresh(); }, [refresh])

  const dirty = drawer ? JSON.stringify(form) !== JSON.stringify(initialForm) : false
  dirtyRef.current = dirty
  unsavedOpenRef.current = unsavedOpen
  const openDrawer = (mode: Mode, source?: SafeConnection) => {
    openerRef.current = document.activeElement as HTMLElement
    const next = source ? fromProfile(source, mode) : emptyForm()
    if (mode === 'copy' && source) {
      const names = new Set(connections.map((connection) => connection.name.toLocaleLowerCase()))
      let candidate = next.name, suffix = 2
      while (names.has(candidate.toLocaleLowerCase())) candidate = `${source.name} - 副本 ${suffix++}`
      next.name = candidate
    }
    setForm(next); setInitialForm(next); setDrawer({ mode, source }); setFieldErrors({}); setDiagnostics(undefined); setDrawerError(undefined)
    setAdvanced(false); setShowPassword(false)
  }
  const closeNow = () => {
    setForm(emptyForm()); setInitialForm(emptyForm()); setDrawer(undefined); setDiagnostics(undefined); setFieldErrors({}); setDrawerError(undefined)
    setShowPassword(false); setUnsavedOpen(false); queueMicrotask(() => openerRef.current?.focus())
  }
  const requestClose = () => dirty ? setUnsavedOpen(true) : closeNow()

  useEffect(() => {
    if (!drawer) return
    const root = drawerRef.current
    root?.querySelector<HTMLElement>('input, button')?.focus()
    const onKey = (event: KeyboardEvent) => {
      if (unsavedOpenRef.current) return
      if (event.key === 'Escape') { event.preventDefault(); dirtyRef.current ? setUnsavedOpen(true) : closeNow(); return }
      if (event.key !== 'Tab' || !root) return
      const focusable = [...root.querySelectorAll<HTMLElement>('button:not([disabled]), input:not([disabled]), select:not([disabled]), summary')]
      if (!focusable.length) return
      const first = focusable[0], last = focusable.at(-1)!
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
    }
    document.addEventListener('keydown', onKey)
    return () => { document.removeEventListener('keydown', onKey); setForm(emptyForm()) }
  }, [drawer])

  useEffect(() => {
    if (!drawer || !form.jdbcUrl.trim()) { setDiagnostics(undefined); return }
    const controller = new AbortController()
    const timer = window.setTimeout(async () => {
      try { const value = await api.diagnoseUrl(form.jdbcUrl, controller.signal); if (!controller.signal.aborted) setDiagnostics(value) }
      catch (cause) { if (!(cause instanceof ApiError && cause.category === 'ABORTED')) setDiagnostics(undefined) }
    }, 400)
    return () => { window.clearTimeout(timer); controller.abort() }
  }, [api, drawer, form.jdbcUrl])

  useEffect(() => { if (firstInvalid.current) { document.getElementById(firstInvalid.current)?.focus(); firstInvalid.current = null } }, [fieldErrors])
  useEffect(() => { drawerErrorRef.current?.focus() }, [drawerError])

  const update = (key: keyof Form, value: string | boolean) => setForm((current) => ({ ...current, [key]: value }))
  const validate = () => {
    const issues: Record<string, string> = {}
    const required = (key: 'name' | 'driverJar' | 'jdbcUrl' | 'username', label: string, condition = true) => { if (condition && !form[key].trim()) issues[key] = `请输入${label}` }
    required('name', '连接名称'); required('driverJar', '驱动 JAR 本地路径', drawer?.mode !== 'edit'); required('jdbcUrl', 'JDBC URL', drawer?.mode !== 'edit'); required('username', '用户名')
    if(drawer?.mode==='copy'&&drawer.source?.hasPassword&&!form.password.trim())issues.password='请输入新密码'
    const bounded = (key: keyof Form, label: string, min: number, max: number) => { const value = Number(form[key]); if (!Number.isInteger(value) || value < min || value > max) issues[key] = `${label}应在 ${min}–${max} 之间` }
    bounded('connectTimeoutSeconds', '连接超时', 1, 300); bounded('socketTimeoutSeconds', '网络超时', 1, 600); bounded('queryTimeoutSeconds', '查询超时', 1, 3600); bounded('maxRows', '最大行数', 1, 10000); bounded('maxBytesMiB', '最大结果', 1, 50)
    const first=Object.keys(issues)[0]??null
    if(first&&['connectTimeoutSeconds','socketTimeoutSeconds','queryTimeoutSeconds','maxRows','maxBytesMiB'].includes(first))setAdvanced(true)
    setFieldErrors(issues); firstInvalid.current = first
    return Object.keys(issues).length === 0
  }
  const payload = (): Partial<ConnectionInput> => {
    const value: Partial<ConnectionInput> = { name: form.name.trim(), driverClass: form.driverClass.trim(), username: form.username.trim(), schema: form.schema.trim() || null, isDefault: form.isDefault, connectTimeoutSeconds: Number(form.connectTimeoutSeconds), socketTimeoutSeconds: Number(form.socketTimeoutSeconds), queryTimeoutSeconds: Number(form.queryTimeoutSeconds), maxRows: Number(form.maxRows), maxBytes: Number(form.maxBytesMiB) * 1024 * 1024 }
    if (form.driverJar.trim()) value.driverJar = form.driverJar.trim()
    if (form.jdbcUrl.trim()) value.jdbcUrl = form.jdbcUrl.trim()
    if (form.password) value.password = form.password
    if (form.clearPassword) value.clearPassword = true
    return value
  }
  const submit = async (testAfter = false) => {
    if (!drawer || saving || !validate()) return
    setSaving(true); setDrawerError(undefined)
    try {
      const value = payload()
      const saved = drawer.mode === 'edit' ? await api.updateConnection(drawer.source!.id, value) : await api.createConnection(value as ConnectionInput)
      setForm((current) => ({ ...current, password: '' })); closeNow(); await refresh(false)
      if (testAfter) await runTest(saved)
    } catch (cause) { setDrawerError(safeError(cause, '连接保存失败。')); setForm((current) => ({ ...current, password: '' })) }
    finally { setSaving(false) }
  }
  const runTest = async (connection: SafeConnection) => {
    if (cardBusy[connection.id]) return
    setCardBusy((current) => ({ ...current, [connection.id]: 'test' })); setCardErrors((current) => ({ ...current, [connection.id]: '' }))
    try { const result = await api.testConnection(connection.id); setTests((current) => ({ ...current, [connection.id]: result })) }
    catch (cause) { setCardErrors((current) => ({ ...current, [connection.id]: safeError(cause, '连接测试失败。').message })) }
    finally { setCardBusy((current) => { const next = { ...current }; delete next[connection.id]; return next }) }
  }
  const makeDefault = async (connection: SafeConnection) => {
    if (cardBusy[connection.id]) return
    setCardBusy((current) => ({ ...current, [connection.id]: 'default' }))
    try { await api.setDefaultConnection(connection.id); await refresh(false) }
    catch (cause) { setCardErrors((current) => ({ ...current, [connection.id]: safeError(cause, '无法设置默认连接。').message })) }
    finally { setCardBusy((current) => { const next = { ...current }; delete next[connection.id]; return next }) }
  }
  const remove = async () => {
    if (!deleteTarget || cardBusy[deleteTarget.id]) return
    const id = deleteTarget.id; setCardBusy((current) => ({ ...current, [id]: 'delete' }))
    const others=connections.filter((connection)=>connection.id!==id)
    const disposition=deleteTarget.isDefault&&others.length>0
      ?deleteDisposition==='__none__'?{leaveWithoutDefault:true as const}:{replacementDefaultId:deleteDisposition}
      :{}
    try { await api.removeConnection(id,disposition); setDeleteTarget(undefined); setDeleteDisposition(''); await refresh(false) }
    catch (cause) { setDeleteTarget(undefined); setCardErrors((current) => ({ ...current, [id]: safeError(cause, '删除连接失败。').message })) }
    finally { setCardBusy((current) => { const next = { ...current }; delete next[id]; return next }) }
  }
  const requestDelete=(connection:SafeConnection)=>{setDeleteDisposition('');setDeleteTarget(connection)}

  return <>
    <PageHeader eyebrow="CONNECTION REGISTRY" title="连接管理" description="本地管理 DM7 驱动、凭据、默认目标与连接诊断。" actions={<button className="button-primary" onClick={() => openDrawer('create')}><Plus size={16} />新增连接</button>} />
    {error && <div className="connection-page-error" role="alert"><div><strong>{error.message}</strong>{error.correlationId && <details><summary>查看关联信息</summary><code>{error.correlationId}</code></details>}</div><button className="button-secondary" onClick={() => void refresh()}>重试</button></div>}
    {loading ? <LoadingSkeleton label="正在读取连接配置" /> : !error && connections.length === 0 ? <EmptyState title="还没有数据库连接" description="先登记合法的达梦 JDBC 驱动路径和连接信息。驱动与凭据不会进入分享包。" action={<button className="button-primary" onClick={() => openDrawer('create')}><Plus size={16} />新增连接</button>} /> : <section className="connection-grid" aria-label="已保存连接">{connections.map((connection, index) => <ConnectionCard key={connection.id} connection={connection} index={index} result={tests[connection.id]} error={cardErrors[connection.id]} busy={cardBusy[connection.id]} onEdit={() => openDrawer('edit', connection)} onCopy={() => openDrawer('copy', connection)} onTest={() => void runTest(connection)} onDefault={() => void makeDefault(connection)} onDelete={() => requestDelete(connection)} />)}</section>}
    {drawer && <aside ref={drawerRef} className="connection-drawer" role="dialog" aria-modal="true" aria-hidden={unsavedOpen || undefined} aria-labelledby="connection-editor-title"><div className="drawer-heading"><div><span className="eyebrow">{drawer.mode.toUpperCase()} PROFILE</span><h2 id="connection-editor-title">{drawer.mode === 'create' ? '新增连接' : drawer.mode === 'copy' ? '复制连接' : '编辑连接'}</h2></div><button className="icon-button" aria-label="关闭连接编辑器" onClick={requestClose}><X size={19} /></button></div><form onSubmit={(event) => { event.preventDefault(); void submit(false) }} noValidate>
      <div className="drawer-scroll">{drawerError&&<div ref={drawerErrorRef} className="drawer-error" role="alert" aria-live="assertive" tabIndex={-1}><strong>{drawerError.message}</strong>{drawerError.correlationId&&<details><summary>查看关联信息</summary><code>{drawerError.correlationId}</code></details>}</div>}<Field id="name" label="连接名称" value={form.name} error={fieldErrors.name} onChange={(v) => update('name', v)} required />
      <Field id="driverJar" label="驱动 JAR 本地路径" value={form.driverJar} error={fieldErrors.driverJar} onChange={(v) => update('driverJar', v)} required={drawer.mode !== 'edit'} placeholder={drawer.mode === 'edit' ? '留空保留当前驱动' : '例如 C:\\drivers\\DmJdbcDriver.jar'} hint={drawer.mode === 'edit' ? `当前驱动：${drawer.source?.driverFileName ?? '已配置'}` : drawer.mode === 'copy' ? '为安全起见，复制时必须重新输入本地路径。' : '请输入当前机器上的合法驱动绝对路径。'} />
      <Field id="driverClass" label="驱动类" value={form.driverClass} error={fieldErrors.driverClass} onChange={(v) => update('driverClass', v)} />
      <Field id="jdbcUrl" label="JDBC URL" value={form.jdbcUrl} error={fieldErrors.jdbcUrl} onChange={(v) => update('jdbcUrl', v)} required={drawer.mode !== 'edit'} placeholder={drawer.mode === 'edit' ? '留空保留当前 URL' : 'jdbc:dm7://host:port?dbname=DATABASE'} hint={drawer.mode === 'edit' ? `安全摘要：${drawer.source?.urlSummary}；留空将保留当前 URL。` : drawer.mode === 'copy' ? '复制不恢复原始 URL，请重新输入。' : undefined} />
      {diagnostics && <div className="url-diagnostics" role="status"><span>URL 诊断</span>{diagnostics.warnings.length ? diagnostics.warnings.map((warning) => <p key={warning}><AlertTriangle size={14} />{warning}</p>) : <p><Check size={14} />未发现已知格式风险</p>}</div>}
      <div className="field-row"><Field id="username" label="用户名" value={form.username} error={fieldErrors.username} onChange={(v) => update('username', v)} required /><Field id="schema" label="Schema" value={form.schema} error={fieldErrors.schema} onChange={(v) => update('schema', v)} /></div>
      <div className="form-field"><label htmlFor="password">密码</label>{drawer.mode==='copy'?<small>凭据不会复制。{drawer.source?.hasPassword?'请输入新密码后保存。':'可按需设置新密码。'}</small>:drawer.source&&<small>{drawer.source.hasPassword ? '已配置；留空保留' : '未配置'}</small>}<div className="password-input"><input id="password" type={showPassword ? 'text' : 'password'} autoComplete="new-password" value={form.password} disabled={form.clearPassword} aria-invalid={Boolean(fieldErrors.password)} aria-describedby={fieldErrors.password?'password-error':undefined} onChange={(event) => update('password', event.target.value)} /><button type="button" aria-label={showPassword ? '隐藏密码' : '显示密码'} aria-pressed={showPassword} onClick={() => setShowPassword((v) => !v)}>{showPassword ? <EyeOff size={17} /> : <Eye size={17} />}</button></div>{fieldErrors.password&&<small className="field-error" id="password-error">{fieldErrors.password}</small>}{drawer.mode === 'edit' && drawer.source?.hasPassword && <label className="check-line"><input type="checkbox" checked={form.clearPassword} onChange={(event) => { update('clearPassword', event.target.checked); if (event.target.checked) update('password', '') }} />清除已保存密码</label>}</div>
      <label className="check-line default-check"><input type="checkbox" checked={form.isDefault} onChange={(event) => update('isDefault', event.target.checked)} />设为默认连接</label>
      <details className="advanced-fields" open={advanced} onToggle={(event) => setAdvanced(event.currentTarget.open)}><summary>高级参数</summary><div className="numeric-grid"><Field id="connectTimeoutSeconds" label="连接超时（秒）" type="number" value={form.connectTimeoutSeconds} error={fieldErrors.connectTimeoutSeconds} onChange={(v) => update('connectTimeoutSeconds', v)} /><Field id="socketTimeoutSeconds" label="网络超时（秒）" type="number" value={form.socketTimeoutSeconds} error={fieldErrors.socketTimeoutSeconds} onChange={(v) => update('socketTimeoutSeconds', v)} /><Field id="queryTimeoutSeconds" label="查询超时（秒）" type="number" value={form.queryTimeoutSeconds} error={fieldErrors.queryTimeoutSeconds} onChange={(v) => update('queryTimeoutSeconds', v)} /><Field id="maxRows" label="最大行数" type="number" value={form.maxRows} error={fieldErrors.maxRows} onChange={(v) => update('maxRows', v)} /><Field id="maxBytesMiB" label="最大结果（MiB）" type="number" value={form.maxBytesMiB} error={fieldErrors.maxBytesMiB} onChange={(v) => update('maxBytesMiB', v)} /></div></details>
      </div><div className="drawer-actions"><button type="button" className="button-secondary" onClick={requestClose}>取消</button><button type="button" className="button-secondary" disabled={saving} onClick={() => void submit(true)}>保存并测试</button><button type="submit" className="button-primary" disabled={saving}>{saving ? '正在保存…' : drawer.mode === 'edit' ? '保存更改' : '保存连接'}</button></div></form></aside>}
    <ConfirmDialog open={Boolean(deleteTarget)} title="删除数据库连接" confirmLabel="确认删除" confirmDisabled={Boolean(deleteTarget?.isDefault&&connections.length>1&&!deleteDisposition)} onConfirm={() => void remove()} onClose={() => {setDeleteTarget(undefined);setDeleteDisposition('')}}><p>将删除“{deleteTarget?.name}”及其已保存凭据。</p>{deleteTarget?.isDefault && <><p className="danger-copy">这是默认连接。请明确选择删除后的默认状态。</p>{connections.length>1&&<label className="delete-default-choice">删除后的默认连接<select aria-label="删除后的默认连接" value={deleteDisposition} onChange={(event)=>setDeleteDisposition(event.target.value)}><option value="">请选择</option>{connections.filter((connection)=>connection.id!==deleteTarget.id).map((connection)=><option key={connection.id} value={connection.id}>{connection.name}</option>)}<option value="__none__">暂不设置默认连接</option></select></label>}</>}</ConfirmDialog>
    <ConfirmDialog open={unsavedOpen} title="放弃未保存的更改？" confirmLabel="放弃更改" onConfirm={closeNow} onClose={() => setUnsavedOpen(false)}><p>编辑器中的修改尚未保存。</p></ConfirmDialog>
  </>
}

function Field({ id, label, value, onChange, error, required, hint, placeholder, type = 'text' }: { id: string; label: string; value: string; onChange(value: string): void; error?: string; required?: boolean; hint?: string; placeholder?: string; type?: string }) {
  return <div className="form-field"><label htmlFor={id}>{label}{required && <span aria-hidden="true"> *</span>}</label><input id={id} type={type} value={value} required={required} aria-invalid={Boolean(error)} aria-describedby={error ? `${id}-error` : hint ? `${id}-hint` : undefined} placeholder={placeholder} onChange={(event) => onChange(event.target.value)} />{hint && <small id={`${id}-hint`}>{hint}</small>}{error && <small className="field-error" id={`${id}-error`}>{error}</small>}</div>
}

function ConnectionCard({ connection, index, result, error, busy, onEdit, onCopy, onTest, onDefault, onDelete }: { connection: SafeConnection; index: number; result?: ConnectionTestResult; error?: string; busy?: string; onEdit(): void; onCopy(): void; onTest(): void; onDefault(): void; onDelete(): void }) {
  const hash = connection.driverSha256 ? `${connection.driverSha256.slice(0, 10)}…${connection.driverSha256.slice(-6)}` : '未提供'
  const versions=result?[result.driverVersion,result.serverVersion].filter(Boolean).join(' / '):''
  const identity=result?[result.actualUser,result.actualSchema].filter(Boolean).join(' · '):''
  return <article className="connection-card"><div className="connection-card-head"><span className="connection-index">DM7 / {String(index + 1).padStart(2, '0')}</span>{connection.isDefault && <StatusBadge tone="success">默认连接</StatusBadge>}</div><div className="connection-title"><Database size={22} /><div><h2>{connection.name}</h2><p>{connection.urlSummary}</p></div></div><dl className="connection-facts"><div><dt>SCHEMA</dt><dd>{connection.schema || '默认'}</dd></div><div><dt>DRIVER</dt><dd>{connection.driverFileName || '已配置'}</dd></div><div><dt>HASH</dt><dd><code>{hash}</code></dd></div><div><dt>CREDENTIAL</dt><dd>{connection.hasPassword ? '凭据已配置' : '未配置凭据'}</dd></div></dl><div className="connection-test"><div><Gauge size={16} /><span>{result ? result.success ? `成功 · ${result.latencyMs} ms` : '检测失败' : '未检测'}</span>{result&&<StatusBadge tone={result.chineseRoundTrip?'success':'danger'}>{result.chineseRoundTrip?'中文往返正常':'中文往返未通过'}</StatusBadge>}</div>{result && <>{(versions||identity||result.restartRequired)&&<dl>{versions&&<div><dt>驱动 / 服务</dt><dd>{versions}</dd></div>}{identity&&<div><dt>实际身份</dt><dd>{identity}</dd></div>}{result.restartRequired && <div><dt>运行状态</dt><dd>需要重启插件</dd></div>}</dl>}{result.warnings.length>0&&<ul className="test-warnings">{result.warnings.map((warning)=><li key={warning}>{warning}</li>)}</ul>}</>}{error && <p className="card-error" role="alert">{error}</p>}</div><div className="card-actions"><button aria-label={`编辑${connection.name}`} onClick={onEdit}><Pencil size={15} />编辑</button><button aria-label={`复制${connection.name}`} onClick={onCopy}><Copy size={15} />复制</button><button aria-label={`测试${connection.name}`} disabled={Boolean(busy)} onClick={onTest}><RefreshCw size={15} />{busy === 'test' ? '测试中' : '测试'}</button>{!connection.isDefault && <button aria-label={`设${connection.name}为默认`} disabled={Boolean(busy)} onClick={onDefault}><Star size={15} />设为默认</button>}<button className="danger-action" aria-label={`删除${connection.name}`} disabled={Boolean(busy)} onClick={onDelete}><Trash2 size={15} />删除</button></div></article>
}
