import { test, expect } from "@playwright/test";

test("homepage loads", async ({ page }) => {
  await page.goto("/", { waitUntil: "domcontentloaded" });

  // Loosen this assertion initially; we can tighten once we know the UI.
  await expect(page).toHaveTitle(/airline/i);

  // Optional: always capture a baseline screenshot for quick review
  await page.screenshot({ path: "test-results/homepage.png", fullPage: true });
});
