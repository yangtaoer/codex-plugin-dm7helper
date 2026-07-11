import { test, expect } from './fixtures'

test('tests, edits and never echoes a saved password', async ({ page }) => {
  await page.goto('/app/connections')
  await page.getByRole('button', { name: '测试华东生产只读' }).click()
  await expect(page.getByText('中文往返正常')).toBeVisible()
  await page.getByRole('button', { name: '编辑华东生产只读' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.locator('input[type=password]')).toHaveValue('')
  await dialog.locator('input[type=password]').fill('SYNTHETIC_PASSWORD_NOT_REAL')
  await dialog.getByRole('button', { name: '保存更改' }).click()
  await expect(page.locator('body')).not.toContainText('SYNTHETIC_PASSWORD_NOT_REAL')
})

test('diagnoses URL path form without rewriting it', async ({ page }) => {
  await page.goto('/app/connections')
  await page.getByRole('button', { name: '编辑华东生产只读' }).click()
  const url = page.getByLabel(/JDBC URL/)
  await url.fill('jdbc:dm7://demo.invalid:5236/SYSTEM')
  await expect(page.getByText(/URL 路径段可能被旧版驱动忽略/)).toBeVisible()
  await expect(url).toHaveValue('jdbc:dm7://demo.invalid:5236/SYSTEM')
})
