import { test, expect } from "@playwright/test";

test("homepage loads", async ({ page }, testInfo) => {
  await page.goto("/", { waitUntil: "domcontentloaded" });

  // The server renders "MyFly.Club | A Fork of Airline Club" but client-side
  // JS retitles the page to "Login" for anonymous visitors.
  await expect(page).toHaveTitle(/airline|myfly|login/i);

  // Optional: always capture a baseline screenshot for quick review
  await page.screenshot({ path: testInfo.outputPath("homepage.png"), fullPage: true });
});
