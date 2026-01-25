const { test, expect } = require('@playwright/test');

test('homepage loads', async ({ page }) => {
  await page.goto('/', { waitUntil: 'domcontentloaded' });
  // Initial loose assertion: page should not be blank and should have a title.
  const title = await page.title();
  expect(title).toBeTruthy();
});
