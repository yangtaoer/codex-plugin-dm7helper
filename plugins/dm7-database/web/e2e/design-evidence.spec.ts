import { test, expect } from './fixtures'

const evidence = '../../../.gstack/design-audits/task-13/screenshots'

test('captures deterministic before and after evidence for all visual findings', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/app/connections')
  await expect(page.getByRole('heading', { name: '华东生产只读' })).toBeVisible()
  await page.addStyleTag({ content: ':root{--muted:#66706a}.card-actions button{min-height:36px}' })
  await settle(page)
  await page.screenshot({ path: `${evidence}/finding-001-002-before.png`, animations: 'disabled' })
  await page.reload(); await settle(page)
  await page.screenshot({ path: `${evidence}/finding-001-002-after.png`, animations: 'disabled' })

  await page.getByRole('button', { name: '切换为深色主题' }).click()
  await page.addStyleTag({ content: '[data-theme="dark"]{--success:#147d64}' })
  await settle(page)
  await page.screenshot({ path: `${evidence}/finding-003-before.png`, animations: 'disabled' })
  await page.reload(); await settle(page)
  await page.screenshot({ path: `${evidence}/finding-003-after.png`, animations: 'disabled' })

  await page.getByRole('button', { name: '切换为浅色主题' }).click()
  await page.goto('/app/sql')
  await page.getByTestId('sql-editor').locator('.cm-content').fill(`SELECT '达梦数据库' AS "中文列"`)
  await page.getByRole('button', { name: '执行全部' }).click()
  await expect(page.getByText('达梦数据库 · 中文结果已验证')).toBeVisible()
  await page.addStyleTag({ content: '@media(min-width:721px) and (max-height:920px){main{padding-top:32px;padding-bottom:64px}.page-header{margin:18px 0 28px;padding-bottom:22px}.sql-editor{height:330px}.sql-actions{padding-block:10px}.sql-output{margin-top:14px}}' })
  await settle(page)
  await page.screenshot({ path: `${evidence}/finding-004-before.png`, animations: 'disabled' })
  await page.reload()
  await page.getByTestId('sql-editor').locator('.cm-content').fill(`SELECT '达梦数据库' AS "中文列"`)
  await page.getByRole('button', { name: '执行全部' }).click()
  await expect(page.getByText('达梦数据库 · 中文结果已验证')).toBeInViewport()
  await settle(page)
  await page.screenshot({ path: `${evidence}/finding-004-after.png`, animations: 'disabled' })
})

async function settle(page: import('@playwright/test').Page) {
  await page.evaluate(() => document.fonts.ready)
  await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
}
