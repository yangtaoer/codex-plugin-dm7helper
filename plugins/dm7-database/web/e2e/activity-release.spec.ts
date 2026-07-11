import { test, expect } from './fixtures'

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
  await expect(page.locator('.execution-row').getByText('CANCELLED')).toBeVisible()
})

test('release recovery and export conflicts stay safely actionable', async ({ page, fixtureState }) => {
  fixtureState.releaseMode = 'recoverable'
  await page.goto('/app/release')
  await expect(page.getByRole('button', { name: '恢复导出' })).toBeVisible()
  fixtureState.releaseMode = 'conflict'
  await page.getByRole('button', { name: '发版并导出' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('checkbox').check()
  await dialog.getByRole('button', { name: '确认发版' }).click()
  await expect(page.getByRole('alert')).toContainText('发版状态已变化')
})
