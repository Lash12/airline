import { expect, test } from "@playwright/test";

test("login page: form renders with correct fields", async ({ page }) => {
  await page.goto("/login/", { waitUntil: "load" });

  // Overlay and login form visible by default
  await expect(page.locator("#loginPageOverlay")).toBeVisible();
  await expect(page.locator("#loginForm")).toBeVisible();
  await expect(page.locator("#loginPageUserName")).toBeVisible();
  await expect(page.locator("#loginPagePassword")).toBeVisible();

  // Signup form hidden until toggled
  await expect(page.locator("#signupForm")).toBeHidden();
});

test("login page: clicking Sign Up shows signup form", async ({ page }) => {
  await page.goto("/login/", { waitUntil: "load" });
  await expect(page.locator("#loginForm")).toBeVisible();

  await page.click("button:has-text('Sign Up & Start')");

  await expect(page.locator("#signupForm")).toBeVisible();
  await expect(page.locator("#loginForm")).toBeHidden();

  // All five signup fields visible
  for (const id of [
    "#signupPageUserName",
    "#signupPageEmail",
    "#signupPagePassword",
    "#signupPagePasswordConfirm",
    "#signupPageAirlineName",
  ]) {
    await expect(page.locator(id)).toBeVisible();
  }
});

test("signup form: back button returns to login", async ({ page }) => {
  await page.goto("/login/", { waitUntil: "load" });
  await page.click("button:has-text('Sign Up & Start')");
  await expect(page.locator("#signupForm")).toBeVisible();

  await page.click("button:has-text('Back to Login')");

  await expect(page.locator("#loginForm")).toBeVisible();
  await expect(page.locator("#signupForm")).toBeHidden();
});

test("signup form: creates account and boots game", async ({ page }) => {
  test.setTimeout(60000);

  const s = Date.now().toString(36).slice(-8);
  const username = `ls${s}`;
  const airlineName = `Login Signup ${s.replace(/[0-9]/g, "a")}`;

  // Pre-agree to announcement so the overlay doesn't block post-login boot
  await page.goto("/login/", { waitUntil: "load" });
  await page.evaluate(() => localStorage.setItem("announcementAgreed", "2026-02-25"));

  await page.click("button:has-text('Sign Up & Start')");
  await expect(page.locator("#signupForm")).toBeVisible();

  await page.fill("#signupPageUserName", username);
  await page.fill("#signupPageEmail", `${username}@example.test`);
  await page.fill("#signupPagePassword", `pw${s}`);
  await page.fill("#signupPagePasswordConfirm", `pw${s}`);
  await page.fill("#signupPageAirlineName", airlineName);

  await page.click(".signup-page-btn");

  // After successful signup the SPA boots: activeAirline becomes available
  await page.waitForFunction(() => (window as any).activeAirline, { timeout: 30000 });

  // Login overlay is dismissed
  await expect(page.locator("#loginPageOverlay")).toBeHidden({ timeout: 5000 });

  // The game canvas container is rendered
  await expect(page.locator("#canvasContainer")).toBeVisible();
});
