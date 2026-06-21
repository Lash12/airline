import { expect, type Page, test } from "@playwright/test";
import * as path from "path";

// Screenshots land next to the spec under e2e/shots so they are easy to collect.
const SHOTS_DIR = path.join(__dirname, "..", "shots", "ui-polish");

async function createAccount(page: Page) {
  const suffix = Date.now().toString(36).slice(-8);
  const username = `uip${suffix}`;
  const password = `pw${suffix}`;
  const sanitizedSuffix = suffix.replace(/[0-9]/g, "a");
  const airlineName = `UI Polish ${sanitizedSuffix}`;

  await page.goto("/login/", { waitUntil: "load" });
  const signup = await page.request.post("/signup/json", {
    data: { username, email: `${username}@example.test`, password, passwordConfirm: password, airlineName },
  });
  expect(signup.ok(), await signup.text()).toBeTruthy();

  await page.goto("/login/", { waitUntil: "load" });
  await page.evaluate(() => {
    localStorage.setItem("sessionActive", "true");
    localStorage.setItem("announcementAgreed", "2026-02-25");
  });
  const restore = await page.request.post("/user-login", { headers: { Accept: "application/json" } });
  expect(restore.ok(), await restore.text()).toBeTruthy();
  await page.goto("/map/", { waitUntil: "load" });
}

test("UI polish pass - visual verify", async ({ page }) => {
  test.setTimeout(60000);
  page.on("pageerror", err => console.error("PAGE ERROR:", err.stack || err.message));

  await createAccount(page);
  await page.waitForSelector("#navPrimary", { state: "visible", timeout: 15000 });
  await page.waitForFunction(() => (window as any).activeAirline, { timeout: 15000 });

  // Establish HQ at LAX (3599) + skip tutorial, mirroring cargo-ui-validation.spec.ts
  await page.evaluate(async () => {
    if (!(window as any).activeAirline.headquarterAirport) {
      const airlineId = (window as any).activeAirline.id;
      const ajax = (opts: any) => new Promise((resolve, reject) =>
        (window as any).$.ajax({ ...opts, success: resolve, error: (_x: any, _s: any, e: any) => reject(new Error(String(e))) }));
      await ajax({ type: "GET", url: `/airlines/${airlineId}/profiles?airportId=3599`, dataType: "json" });
      await ajax({ type: "PUT", url: `/airlines/${airlineId}/profiles/0?airportId=3599`, contentType: "application/json; charset=utf-8", dataType: "json" });
      await (window as any).updateAirlineInfo(airlineId);
      await ajax({ type: "POST", url: `/airlines/${airlineId}/tutorial?skipTutorial=true`, dataType: "json" });
      (window as any).activeAirline.skipTutorial = true;
    }
  });
  await page.waitForFunction(() => (window as any).activeAirline?.headquarterAirport, { timeout: 15000 });

  // 1. Aircraft market - Cargo column should be present in the model table
  await page.goto("/aircraft/", { waitUntil: "load" });
  await page.waitForSelector("#airplaneModelTable .table-row", { timeout: 15000 });
  await expect(page.locator("#airplaneModelSortHeader", { hasText: "Cargo" })).toBeVisible();
  await page.screenshot({ path: path.join(SHOTS_DIR, "market_cargo_column.png"), fullPage: false });

  // 2. Model detail panel - cargo capacity line
  await page.locator("#airplaneModelTable .table-row").first().click();
  await page.waitForSelector("#airplaneCanvas #cargoCapacity", { timeout: 10000 });
  const cargoText = await page.locator("#airplaneCanvas #cargoCapacity").textContent();
  console.log("Model detail cargo line:", cargoText);
  expect(cargoText).toContain("Freighter");
  await page.screenshot({ path: path.join(SHOTS_DIR, "market_model_detail.png"), fullPage: false });

  // 3. Office income sheet - colored totals + cargo % + separator rows
  await page.goto("/office/", { waitUntil: "load" });
  await page.waitForSelector("#balNetIncome", { timeout: 10000 });
  await page.locator("#balNetIncome").scrollIntoViewIfNeeded();
  await page.screenshot({ path: path.join(SHOTS_DIR, "office_income.png"), fullPage: false });

  console.log("Screenshots written to", SHOTS_DIR);
});
