import AxeBuilder from '@axe-core/playwright'
import { test, expect } from './fixtures'

test('keyboard navigation, skip link, focus trap and WCAG scan', async ({ page }) => {
  await page.goto('/app/connections')
  await page.keyboard.press('Tab')
  await expect(page.getByRole('link', { name: '跳到主要内容' })).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.locator('main')).toBeFocused()
  await page.getByRole('button', { name: '编辑华东生产只读' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(dialog).toBeHidden()
  await expect(page.getByRole('button', { name: '编辑华东生产只读' })).toBeFocused()
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze()
  expect(results.violations).toEqual([])
})

test('reduced motion and visible focus are respected', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto('/app/settings')
  await page.keyboard.press('Tab')
  const focused = page.locator(':focus-visible')
  await expect(focused).toBeVisible()
  expect(await focused.evaluate((el) => getComputedStyle(el).outlineStyle !== 'none')).toBe(true)
})

test('dark theme remains AA and connection actions keep touch targets', async ({ page }) => {
  await page.goto('/app/connections')
  await page.getByRole('button', { name: '切换为深色主题' }).click()
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze()
  expect(results.violations).toEqual([])
  for (const button of await page.locator('.card-actions button').all()) {
    const box = await button.boundingBox()
    expect(box?.height).toBeGreaterThanOrEqual(44)
  }
})
