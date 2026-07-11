import { test, expect } from './fixtures'

test('captures deterministic marketplace screenshots at 1440x900', async ({ page, fixtureState }) => {
  await page.setViewportSize({ width: 1440, height: 900 })
  await page.goto('/app/sql')
  await page.getByTestId('sql-editor').locator('.cm-content').fill(`SELECT '达梦数据库' AS "中文列"`)
  await page.getByRole('button', { name: '执行全部' }).click()
  await expect(page.getByText('达梦数据库 · 中文结果已验证')).toBeVisible()
  await expect(page.getByText('达梦数据库 · 中文结果已验证')).toBeInViewport()
  await settlePaint(page)
  await page.screenshot({ path: '../assets/screenshot-console.png', fullPage: false, animations: 'disabled' })

  fixtureState.exported = true
  await page.goto('/app/release')
  await expect(page.locator('.release-hero strong')).toHaveText('v001')
  await expect(page.getByText('dm7-demo-v001.sql')).toBeInViewport()
  await settlePaint(page)
  await page.screenshot({ path: '../assets/screenshot-release.png', fullPage: false, animations: 'disabled' })
})

async function settlePaint(page: import('@playwright/test').Page) {
  await page.evaluate(() => document.fonts.ready)
  await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
}
