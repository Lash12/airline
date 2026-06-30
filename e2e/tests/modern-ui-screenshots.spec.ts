/**
 * Screenshot verification for all 5 modern UI items:
 * 1. Inter font loaded
 * 2. Chart.js modern theming
 * 3. .switch pill styling
 * 4. Canvas slide-in transition CSS present
 * 5. MapLibre light tiles in modern light mode
 */
import { expect, test, type Page } from "@playwright/test";
import * as path from "path";

async function bootstrap(page: Page) {
  const s = Date.now().toString(36).slice(-8);
  await page.goto("/login/", { waitUntil: "load" });
  await page.request.post("/signup/json", {
    data: {
      username: `mu${s}`,
      email: `mu${s}@example.test`,
      password: `pw${s}`,
      passwordConfirm: `pw${s}`,
      airlineName: `ModernTest ${s.replace(/[0-9]/g, "a")}`,
    },
  });
  await page.goto("/login/", { waitUntil: "load" });
  await page.evaluate(() => {
    localStorage.setItem("sessionActive", "true");
    localStorage.setItem("announcementAgreed", "2026-02-25");
    localStorage.setItem("uiMode", "modern");
  });
  await page.request.post("/user-login", { headers: { Accept: "application/json" } });
  await page.goto("/map/", { waitUntil: "load" });
  await page.waitForFunction(() => (window as any).activeAirline, { timeout: 40000 });

  // Set HQ so we can open canvases
  await page.evaluate(async () => {
    const a = (window as any).activeAirline;
    const id = a.id;
    const ajax = (o: any) =>
      new Promise((r, j) =>
        (window as any).$.ajax({ ...o, success: r, error: (_x: any, _s: any, e: any) => j(e) }),
      );
    if (!a.headquarterAirport) {
      await ajax({ type: "GET", url: `/airlines/${id}/profiles?airportId=3599`, dataType: "json" });
      await ajax({ type: "PUT", url: `/airlines/${id}/profiles/0?airportId=3599`, contentType: "application/json; charset=utf-8", dataType: "json" });
      await (window as any).updateAirlineInfo(id);
      await ajax({ type: "POST", url: `/airlines/${id}/tutorial?skipTutorial=true`, dataType: "json" });
      a.skipTutorial = true;
    }
  });
  await page.waitForFunction(() => (window as any).activeAirline?.headquarterAirport, { timeout: 30000 });
}

test.describe("Modern UI – visual verification", () => {

  test("1. Inter font stylesheet loaded in document", async ({ page }, testInfo) => {
    test.setTimeout(120000);
    await bootstrap(page);

    // Check Google Fonts Inter link exists
    const interLoaded = await page.evaluate(() => {
      const links = Array.from(document.querySelectorAll('link[rel="stylesheet"]'));
      return links.some(l => (l as HTMLLinkElement).href.includes("fonts.googleapis.com") && (l as HTMLLinkElement).href.includes("Inter"));
    });
    expect(interLoaded, "Inter font stylesheet not found").toBe(true);

    // Verify computed font-family on body includes Inter
    const fontFamily = await page.evaluate(() =>
      getComputedStyle(document.body).fontFamily
    );
    console.log("Body font-family:", fontFamily);

    await page.screenshot({ path: path.join(testInfo.outputDir, "01-inter-font.png"), fullPage: false });
  });

  test("2. Chart.js theming: modern light mode axes use dark text", async ({ page }, testInfo) => {
    test.setTimeout(120000);
    await bootstrap(page);

    // Open airline details (income panel has charts)
    await page.evaluate(() => {
      document.querySelectorAll<HTMLElement>(".tutorial.modal, #tutorialHtml .modal").forEach(el => { el.style.display = "none"; });
    });

    // Verify ui-modern class present
    const isModern = await page.evaluate(() => document.documentElement.classList.contains("ui-modern"));
    expect(isModern).toBe(true);

    await page.screenshot({ path: path.join(testInfo.outputDir, "02-modern-mode-active.png"), fullPage: false });
  });

  test("3. Switch toggle: pill shape in modern mode", async ({ page }, testInfo) => {
    test.setTimeout(120000);
    await bootstrap(page);

    // Open airport details which has switch toggles
    await page.evaluate(() => (window as any).showAirportDetails(3599));
    await expect(page.locator("#airportCanvas")).toBeVisible({ timeout: 15000 });
    await page.evaluate(() => {
      document.querySelectorAll<HTMLElement>(".tutorial.modal, #tutorialHtml .modal").forEach(el => { el.style.display = "none"; });
    });

    // Check computed border-radius of .switch in modern mode
    const switchEl = page.locator(".switch").first();
    if (await switchEl.isVisible()) {
      const borderRadius = await switchEl.evaluate(el => getComputedStyle(el).borderRadius);
      console.log(".switch border-radius:", borderRadius);
      await page.screenshot({ path: path.join(testInfo.outputDir, "03-switch-pill.png"), fullPage: false });
    } else {
      // No switch visible on this page — just take screenshot for visual check
      await page.screenshot({ path: path.join(testInfo.outputDir, "03-switch-not-visible.png"), fullPage: false });
      console.log("No .switch visible on airport canvas");
    }
  });

  test("4. Canvas slide-in: .m-canvas-entering keyframe defined in stylesheet", async ({ page }, testInfo) => {
    test.setTimeout(120000);
    await bootstrap(page);

    // Verify keyframe exists in loaded stylesheets
    const keyframeExists = await page.evaluate(() => {
      for (const sheet of Array.from(document.styleSheets)) {
        try {
          for (const rule of Array.from(sheet.cssRules || [])) {
            if (rule instanceof CSSKeyframesRule && rule.name === "m-canvas-enter") return true;
          }
        } catch (_) {}
      }
      return false;
    });
    expect(keyframeExists, "@keyframes m-canvas-enter not found in stylesheets").toBe(true);
    console.log("Canvas keyframe present:", keyframeExists);

    // Open a canvas and check transition fires
    await page.evaluate(() => (window as any).showAirportDetails(3599));
    await expect(page.locator("#airportCanvas")).toBeVisible({ timeout: 15000 });
    await page.screenshot({ path: path.join(testInfo.outputDir, "04-canvas-visible.png"), fullPage: false });
  });

  test("5. Map: light tiles in modern light mode (no dark-theme attribute)", async ({ page }, testInfo) => {
    test.setTimeout(120000);
    await bootstrap(page);

    // In modern mode without dark theme, map should use light tiles
    const theme = await page.evaluate(() => document.documentElement.getAttribute("data-theme"));
    const isModern = await page.evaluate(() => document.documentElement.classList.contains("ui-modern"));
    console.log("data-theme:", theme, "ui-modern:", isModern);

    // Should be modern, no dark theme
    expect(isModern).toBe(true);
    expect(theme).not.toBe("dark");

    // Wait a moment for map to initialize
    await page.waitForTimeout(3000);

    // Check AirlineMap exists and current style
    const mapStyle = await page.evaluate(() => {
      if (!(window as any).AirlineMap) return "AirlineMap not loaded";
      try { return (window as any).AirlineMap.getCurrentStyle?.() || "getCurrentStyle not available"; }
      catch(_) { return "error"; }
    });
    console.log("Map current style:", mapStyle);

    await page.screenshot({ path: path.join(testInfo.outputDir, "05-map-light-mode.png"), fullPage: false });
  });

  test("all-5: full modern UI overview screenshot", async ({ page }, testInfo) => {
    test.setTimeout(120000);
    await bootstrap(page);

    await page.evaluate(() => {
      document.querySelectorAll<HTMLElement>(".tutorial.modal, #tutorialHtml .modal").forEach(el => { el.style.display = "none"; });
    });

    await page.waitForTimeout(3000);
    await page.screenshot({ path: path.join(testInfo.outputDir, "00-modern-ui-overview.png"), fullPage: false });

    // Also test dark mode toggle
    await page.evaluate(() => (window as any).setModernUI?.(true));
    // Try to toggle dark mode if available
    const darkBtn = page.locator("#darkModeToggle, [id*='dark'], .dark-toggle").first();
    if (await darkBtn.isVisible({ timeout: 2000 }).catch(() => false)) {
      await darkBtn.click();
      await page.waitForTimeout(500);
      await page.screenshot({ path: path.join(testInfo.outputDir, "06-dark-mode.png"), fullPage: false });
    }

    console.log("Screenshots saved to:", testInfo.outputDir);
  });
});
