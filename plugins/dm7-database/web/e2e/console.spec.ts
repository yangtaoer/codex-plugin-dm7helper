import { emitExecutionEvent, test, expect } from './fixtures'

test('six routes, browser history, theme and Chinese SQL journey', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 })
  await page.goto('/app/overview')
  for (const name of ['概览', 'SQL 控制台', '实时执行', '发版日志', '连接管理', '设置']) await expect(page.getByRole('link', { name })).toBeVisible()
  await page.getByRole('link', { name: 'SQL 控制台' }).click()
  await expect(page).toHaveURL(/\/app\/sql$/)
  await page.getByTestId('sql-editor').locator('.cm-content').fill(`SELECT '达梦数据库' AS "中文列"`)
  await page.getByRole('button', { name: '执行全部' }).click()
  await expect(page.getByText('达梦数据库 · 中文结果已验证')).toBeVisible()
  const csvDownload = page.waitForEvent('download')
  await page.getByRole('button', { name: '下载 CSV' }).click()
  const csv = await csvDownload
  const csvBytes = await readDownload(csv)
  expect([...csvBytes.subarray(0, 3)]).toEqual([0xef, 0xbb, 0xbf])
  expect(csvBytes.toString('utf8')).toContain('达梦数据库')
  const jsonDownload = page.waitForEvent('download')
  await page.getByRole('button', { name: '下载 JSON' }).click()
  const jsonBytes = await readDownload(await jsonDownload)
  expect([...jsonBytes.subarray(0, 3)]).not.toEqual([0xef, 0xbb, 0xbf])
  expect(jsonBytes.toString('utf8')).toContain('达梦数据库')
  await page.goBack(); await expect(page).toHaveURL(/\/app\/overview$/)
  await page.goForward(); await expect(page).toHaveURL(/\/app\/sql$/)
  await page.reload(); await expect(page.getByRole('main')).toContainText('SQL 执行控制台')
  await page.getByRole('button', { name: '切换为深色主题' }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

async function readDownload(download: import('@playwright/test').Download) {
  const stream = await download.createReadStream()
  const chunks: Buffer[] = []
  for await (const chunk of stream) chunks.push(Buffer.from(chunk))
  return Buffer.concat(chunks)
}

test('mutation asks for purpose and supports keyboard execution', async ({ page }) => {
  await page.goto('/app/sql')
  const editor = page.getByTestId('sql-editor').locator('.cm-content')
  await editor.fill(`UPDATE CUSTOMER_PROFILE SET DISPLAY_NAME='中文演示' WHERE ID=42`)
  await editor.press('Control+Enter')
  const dialog = page.getByRole('dialog', { name: '确认修改操作' })
  await expect(dialog).toBeVisible()
  await expect(dialog.getByLabel('用途')).toHaveAttribute('required', '')
  await dialog.getByLabel('用途').selectOption('PRODUCTION_CHANGE')
  await dialog.getByRole('checkbox', { name: '我已核对 SQL 与目标连接' }).check()
  await dialog.getByRole('button', { name: '确认并执行' }).click()
  await expect(page.getByText('COMMITTED')).toBeVisible()
})

test('all routes stay bounded across four viewports', async ({ page }) => {
  const routes = { overview: '数据库运行概览', sql: 'SQL 执行控制台', activity: '实时执行', release: '发版日志', connections: '连接管理', settings: '设置' }
  for (const viewport of [{ width: 390, height: 844 }, { width: 768, height: 900 }, { width: 1280, height: 800 }, { width: 1440, height: 900 }]) {
    await page.setViewportSize(viewport)
    for (const [route, heading] of Object.entries(routes)) {
      await page.goto(`/app/${route}`)
      await expect(page.locator('main')).toBeVisible()
      await expect(page.getByRole('heading', { name: heading, exact: true })).toBeVisible()
      await expect(page.getByRole('link', { name: route === 'sql' ? 'SQL 控制台' : heading === '数据库运行概览' ? '概览' : heading })).toHaveAttribute('aria-current', 'page')
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `${route} ${viewport.width}`).toBe(true)
    }
  }
})

test('no-default and expired-session states remain explicit', async ({ page, fixtureState }) => {
  fixtureState.connections = []
  await page.goto('/app/sql')
  await expect(page.getByText('尚未配置连接')).toBeVisible()
  await expect(page.getByRole('button', { name: '执行全部' })).toBeDisabled()
  fixtureState.runtimeMode = 'expired'
  fixtureState.expectedHttpStatuses.add(401)
  await page.reload()
  await expect(page.getByRole('alert')).toContainText('控制台会话已失效')
})

test('selection, long SQL/result virtualization and named live phases remain usable', async ({ page, fixtureState }) => {
  await page.goto('/app/sql')
  const editor = page.getByTestId('sql-editor').locator('.cm-content')
  await editor.fill(`SELECT 'LONG_RESULT ${'中文长文本'.repeat(400)}' AS "中文列"`)
  await editor.press('Control+A')
  await page.getByRole('button', { name: '执行选中' }).click()
  await expect(page.getByText('结果已截断')).toBeVisible()
  await expect(page.getByText('250 行')).toBeVisible()
  await emitExecutionEvent(page, fixtureState, 'queued')
  await emitExecutionEvent(page, fixtureState, 'executing')
  await emitExecutionEvent(page, fixtureState, 'completed')
  await page.getByRole('tab', { name: '实时过程' }).click()
  await expect(page.getByText('QUEUED', { exact: true })).toBeVisible()
  await expect(page.getByText('EXECUTING', { exact: true })).toBeVisible()
  await expect(page.getByText('COMPLETED', { exact: true })).toBeVisible()
})

test('SQL workbench survives sidebar navigation and wide virtualized result columns stay aligned', async ({ page }) => {
  await page.goto('/app/sql')
  const editor = page.getByTestId('sql-editor').locator('.cm-content')
  await editor.fill(`SELECT 'LONG_RESULT WIDE_RESULT' AS "业务名称"`)
  await page.getByRole('button', { name: '执行全部' }).click()
  await expect(page.getByText('250 行')).toBeVisible()
  const geometry = await page.locator('.result-table').evaluate((table) => {
    const headers = [...table.querySelectorAll('thead th')].map((cell) => cell.getBoundingClientRect())
    const cells = [...table.querySelectorAll('tbody tr:first-child td')].map((cell) => cell.getBoundingClientRect())
    return headers.map((header, index) => ({ left: Math.abs(header.left - cells[index].left), width: Math.abs(header.width - cells[index].width) }))
  })
  expect(geometry.length).toBe(5)
  expect(geometry.every((item) => item.left < 1 && item.width < 1)).toBe(true)
  await page.getByRole('link', { name: '实时执行' }).click()
  await expect(page.getByRole('heading', { name: '实时执行', exact: true })).toBeVisible()
  await page.getByRole('link', { name: 'SQL 控制台' }).click()
  await expect(editor).toContainText('LONG_RESULT WIDE_RESULT')
  await expect(page.getByText('250 行')).toBeVisible()
})

test('cancel remains pending until a named terminal event wins the race', async ({ page, fixtureState }) => {
  fixtureState.queryMode = 'pending'
  await page.goto('/app/sql')
  await page.getByTestId('sql-editor').locator('.cm-content').fill(`SELECT '慢查询' AS "中文列"`)
  await page.getByRole('button', { name: '执行全部' }).click()
  await emitExecutionEvent(page, fixtureState, 'queued')
  await emitExecutionEvent(page, fixtureState, 'executing')
  await expect(page.getByRole('button', { name: '取消执行' })).toBeEnabled()
  await page.getByRole('button', { name: '取消执行' }).click()
  expect(fixtureState.cancelRequested.has('22222222-2222-4222-8222-222222222222')).toBe(true)
  await expect(page.getByRole('button', { name: '正在请求取消' })).toBeDisabled()
  await emitExecutionEvent(page, fixtureState, 'cancelled')
  await page.getByRole('tab', { name: '实时过程' }).click()
  await expect(page.getByText('CANCELLED', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '取消执行' })).toBeDisabled()
})

test('loading, runtime error and empty states stay truthful', async ({ page, fixtureState }) => {
  fixtureState.runtimeMode = 'loading'
  await page.goto('/app/overview')
  await expect(page.getByText('正在读取运行状态')).toBeVisible()
  fixtureState.runtimeMode = 'error'; fixtureState.expectedHttpStatuses.add(503)
  await page.reload()
  await expect(page.getByRole('alert')).toContainText('本地运行状态暂时不可用')
  fixtureState.runtimeMode = 'ready'; fixtureState.connections = []
  await page.goto('/app/connections')
  await expect(page.getByRole('heading', { name: '还没有数据库连接' })).toBeVisible()
})
