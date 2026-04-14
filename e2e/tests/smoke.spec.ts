import { test, expect } from "@playwright/test";

test("homepage loads", async ({ page }, testInfo) => {
  await page.goto("/", { waitUntil: "domcontentloaded" });

  // Loosen this assertion initially; we can tighten once we know the UI.
  await expect(page).toHaveTitle(/airline/i);

  // Optional: always capture a baseline screenshot for quick review
  await page.screenshot({ path: testInfo.outputPath("homepage.png"), fullPage: true });
});
