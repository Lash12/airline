import { expect, type Page, test } from "@playwright/test";

const PAGES = [
  { path: "/map/",      canvas: "#worldMapCanvas" },
  { path: "/flights/",  canvas: "#linksCanvas" },
  { path: "/hangar/",   canvas: "#airplaneCanvas" },
  { path: "/office/",   canvas: "#officeCanvas" },
  { path: "/bank/",     canvas: "#bankCanvas" },
  { path: "/oil/",      canvas: "#oilCanvas" },
  { path: "/alliance/", canvas: "#allianceCanvas" },
  { path: "/country/",  canvas: "#countryCanvas" },
];

async function createAccount(page: Page) {
  const s = Date.now().toString(36).slice(-8);
  await page.goto("/login/", { waitUntil: "domcontentloaded" });
  const res = await page.request.post("/signup/json", {
    data: { username: `mu${s}`, email: `mu${s}@example.test`, password: `pw${s}`, passwordConfirm: `pw${s}`, airlineName: `Modern UI ${s.replace(/[0-9]/g,"a")}` },
  });
  expect(res.ok(), await res.text()).toBeTruthy();
  await page.goto("/login/", { waitUntil: "domcontentloaded" });
  await page.evaluate(() => localStorage.setItem("sessionActive", "true"));
  const login = await page.request.post("/user-login", { headers: { Accept: "application/json" } });
  expect(login.ok(), await login.text()).toBeTruthy();
}

async function waitForCanvas(page: Page, canvas: string) {
  await page.waitForFunction(
    (sel) => {
      const el = document.querySelector(sel);
      return el && window.getComputedStyle(el).display !== "none";
    },
    canvas,
    { timeout: 20000 },
  );
}

// ── Toggle mechanism ──────────────────────────────────────────────────────────

test("modern UI toggle button is visible in navbar", async ({ page }) => {
  await page.goto("/", { waitUntil: "domcontentloaded" });
  await expect(page.locator("#uiModeToggle")).toBeVisible();
});

test("toggle adds ui-modern class and persists to localStorage", async ({ page }) => {
  await createAccount(page);
  await page.goto("/map/", { waitUntil: "domcontentloaded" });

  // Default: no modern class
  const htmlEl = page.locator("html");
  await expect(htmlEl).not.toHaveClass(/ui-modern/);

  // Click toggle → modern mode on
  await page.locator("#uiModeToggle").click();
  await expect(htmlEl).toHaveClass(/ui-modern/);

  // localStorage should be set
  const stored = await page.evaluate(() => localStorage.getItem("uiMode"));
  expect(stored).toBe("modern");

  // Button should show aria-pressed=true
  await expect(page.locator("#uiModeToggle")).toHaveAttribute("aria-pressed", "true");

  // Click again → classic mode
  await page.locator("#uiModeToggle").click();
  await expect(htmlEl).not.toHaveClass(/ui-modern/);
  const stored2 = await page.evaluate(() => localStorage.getItem("uiMode"));
  expect(stored2).toBe("classic");
});

test("ui-modern class applied before first paint (no FOUC)", async ({ page }) => {
  await createAccount(page);
  // Pre-set localStorage to modern
  await page.goto("/login/", { waitUntil: "domcontentloaded" });
  await page.evaluate(() => localStorage.setItem("uiMode", "modern"));

  // Navigate — the inline script in <head> should apply class before DOMContentLoaded
  const classApplied = await Promise.race([
    page.waitForFunction(
      () => document.documentElement.classList.contains("ui-modern"),
      { timeout: 3000 },
    ).then(() => true),
    page.goto("/map/", { waitUntil: "domcontentloaded" }).then(() =>
      page.evaluate(() => document.documentElement.classList.contains("ui-modern")),
    ),
  ]);
  expect(classApplied).toBe(true);
});

// ── Functional smoke — all panels work in modern mode ────────────────────────

test("all primary pages render without JS errors in modern mode", async ({ page }) => {
  const errors: string[] = [];
  page.on("pageerror", (err) => errors.push(err.message));

  await createAccount(page);

  // Enable modern mode
  await page.goto("/map/", { waitUntil: "domcontentloaded" });
  await page.evaluate(() => localStorage.setItem("uiMode", "modern"));

  for (const item of PAGES) {
    errors.length = 0;
    await page.goto(item.path, { waitUntil: "domcontentloaded" });
    // modern class should already be applied by the inline head script
    await expect(page.locator("html")).toHaveClass(/ui-modern/);
    await waitForCanvas(page, item.canvas);
    await expect(page.locator(item.canvas), `${item.path} canvas visible`).toBeVisible();
    expect(errors, `${item.path} page errors in modern mode`).toEqual([]);
  }
});

// ── CSS token smoke — modern vars are reachable ───────────────────────────────

test("modern CSS variables resolve correctly in both light and dark", async ({ page }) => {
  await createAccount(page);
  await page.goto("/map/", { waitUntil: "domcontentloaded" });
  await page.evaluate(() => localStorage.setItem("uiMode", "modern"));
  await page.reload({ waitUntil: "domcontentloaded" });

  // Light modern: card shadow should not be the classic value
  const shadowLight = await page.evaluate(() => {
    const el = document.createElement("div");
    el.className = "section";
    document.body.appendChild(el);
    const v = window.getComputedStyle(el).getPropertyValue("--m-card-shadow").trim();
    document.body.removeChild(el);
    return v;
  });
  // --m-card-shadow should be non-empty in modern mode
  expect(shadowLight.length, "modern CSS var --m-card-shadow should be set").toBeGreaterThan(0);

  // Dark modern: switch theme and re-check
  await page.evaluate(() => document.documentElement.setAttribute("data-theme", "dark"));
  const bgDark = await page.evaluate(() =>
    window.getComputedStyle(document.documentElement).getPropertyValue("--background-1").trim()
  );
  // Dark modern background should be the neutral charcoal (#111318), not the purple classic (#0e0e23)
  expect(bgDark).not.toBe("#0e0e23");
});

// ── Section styling ───────────────────────────────────────────────────────────

test("section cards have modern border-radius in modern mode", async ({ page }) => {
  await createAccount(page);
  await page.goto("/office/", { waitUntil: "domcontentloaded" });
  await page.evaluate(() => localStorage.setItem("uiMode", "modern"));
  await page.reload({ waitUntil: "domcontentloaded" });
  await waitForCanvas(page, "#officeCanvas");

  const radius = await page.evaluate(() => {
    const card = document.querySelector("#officeCanvas .section") as HTMLElement | null;
    return card ? window.getComputedStyle(card).borderRadius : null;
  });
  // modern.css sets --m-card-radius: 12px
  expect(radius, "modern section border-radius").toBe("12px");
});
