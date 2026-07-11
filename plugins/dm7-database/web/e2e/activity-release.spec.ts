import { createHash } from 'node:crypto'
import { emitExecutionEvent, exportBytes, exportSha, test, expect } from './fixtures'

test('activity filters and accessible detail', async ({ page }) => {
  await page.goto('/app/activity')
  await expect(page.getByText(/UPDATE CUSTOMER_PROFILE/)).toBeVisible()
  await page.getByRole('button', { name: '查看执行详情' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog.getByText('corr-demo-001')).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
})

test('release exports v001, reloads v002 and downloads immutable artifact', async ({ page }) => {
  await page.goto('/app/release')
  await expect(page.locator('.release-hero strong')).toHaveText('v001')
  await page.getByRole('button', { name: '发版并导出' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('checkbox').check()
  await dialog.getByRole('button', { name: '确认发版' }).click()
  await expect(page.getByText('v002')).toBeVisible()
  await expect(page.getByText('当前版本暂无 SQL')).toBeVisible()
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: /下载/ }).click()
  expect((await download).suggestedFilename()).toBe('dm7-demo-v001.sql')
})

test('active activity cancellation reconciles to authoritative terminal state', async ({ page, fixtureState }) => {
  fixtureState.history[0].status = 'EXECUTING'
  await page.goto('/app/activity')
  await page.getByRole('button', { name: '取消任务' }).last().click()
  expect(fixtureState.cancelRequested.has(fixtureState.history[0].executionId)).toBe(true)
  await emitExecutionEvent(page, fixtureState, 'cancelled', fixtureState.history[0].executionId)
  await expect(page.locator('.execution-row').getByText('CANCELLED')).toBeVisible()
})

test('release artifact download bytes and SHA are exact', async ({ page, fixtureState }) => {
  fixtureState.exported = true
  await page.goto('/app/release')
  await expect(page.getByText(exportSha)).toBeVisible()
  const pending = page.waitForEvent('download')
  await page.getByRole('button', { name: /下载 dm7-demo-v001\.sql/ }).click()
  const stream = await (await pending).createReadStream(); const chunks: Buffer[] = []
  for await (const chunk of stream) chunks.push(Buffer.from(chunk))
  const bytes = Buffer.concat(chunks)
  expect(bytes.equals(exportBytes)).toBe(true)
  expect([...bytes.subarray(0, 3)]).not.toEqual([0xef, 0xbb, 0xbf])
  expect(requireHash(bytes)).toBe(exportSha)
})

test('history filters, pagination, dedupe and detail use authoritative query parameters', async ({ page, fixtureState }) => {
  const base = fixtureState.history[0]
  fixtureState.history = Array.from({ length: 51 }, (_, index) => ({
    ...base,
    executionId: `${String(index).padStart(8, '0')}-1111-4111-8111-111111111111`,
    correlationId: index === 50 ? 'corr-mcp-only' : `corr-page-${index}`,
    source: index === 50 ? 'MCP' as const : 'CONSOLE' as const,
    sqlSummary: index === 50 ? 'QUERY SELECT NAME FROM CUSTOMER_PROFILE' : `DML UPDATE CUSTOMER_PROFILE SET DISPLAY_NAME = ? WHERE ID = ${index}`,
  }))
  await page.goto('/app/activity')
  await expect(page.locator('.execution-row')).toHaveCount(50)
  await page.getByRole('button', { name: '加载更多' }).click()
  await expect(page.locator('.execution-row')).toHaveCount(51)
  const ids = await page.locator('.execution-row .execution-meta code').allTextContents()
  expect(new Set(ids).size).toBe(51)
  await page.getByLabel('来源').selectOption('MCP')
  await page.getByRole('button', { name: '应用筛选' }).click()
  await expect(page.locator('.execution-row')).toHaveCount(1)
  await expect(page.getByText('corr-mcp-only')).toBeHidden()
  await page.getByRole('button', { name: '查看执行详情' }).click()
  await expect(page.getByRole('dialog')).toContainText('corr-mcp-only')
  expect(fixtureState.networkResponses.some((item) => item.path.includes('/api/history?') && item.path.includes('source=MCP') && item.path.includes('offset=0') && item.path.includes('limit=50'))).toBe(true)
})

function requireHash(bytes: Buffer) {
  return createHash('sha256').update(bytes).digest('hex')
}

test('release recovery and export conflicts stay safely actionable', async ({ page, fixtureState }) => {
  fixtureState.releaseMode = 'recoverable'
  await page.goto('/app/release')
  await expect(page.getByRole('button', { name: '恢复导出' })).toBeVisible()
  fixtureState.releaseMode = 'conflict'
  fixtureState.expectedHttpStatuses.add(409)
  await page.getByRole('button', { name: '发版并导出' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('checkbox').check()
  await dialog.getByRole('button', { name: '确认发版' }).click()
  await expect(page.getByRole('alert')).toContainText('发版状态已变化')
})

test('tampered recovery and missing release stay bounded and correlated', async ({ page, fixtureState }) => {
  fixtureState.releaseMode = 'tampered'; fixtureState.expectedHttpStatuses.add(409)
  await page.goto('/app/release')
  await page.getByRole('button', { name: '恢复导出' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '确认恢复' }).click()
  await expect(page.getByRole('alert')).toContainText('该密封导出当前不可恢复')
  fixtureState.releaseMode = 'missing'; fixtureState.expectedHttpStatuses.add(404)
  await page.reload()
  await expect(page.getByRole('alert')).toContainText('发版状态不存在')
})
