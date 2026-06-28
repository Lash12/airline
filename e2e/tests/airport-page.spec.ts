import { expect, type Page, test } from "@playwright/test";

async function bootstrap(page: Page) {
  const s = Date.now().toString(36).slice(-8);
  await page.goto("/login/", { waitUntil: "load" });
  await page.request.post("/signup/json", {
    data: {
      username: `ap${s}`,
      email: `ap${s}@example.test`,
      password: `pw${s}`,
      passwordConfirm: `pw${s}`,
      airlineName: `Airport Page ${s.replace(/[0-9]/g, "a")}`,
    },
  });
  await page.goto("/login/", { waitUntil: "load" });
  await page.evaluate(() => {
    localStorage.setItem("sessionActive", "true");
    localStorage.setItem("announcementAgreed", "2026-02-25");
  });
  await page.request.post("/user-login", { headers: { Accept: "application/json" } });
  await page.goto("/map/", { waitUntil: "load" });
  await page.waitForFunction(() => (window as any).activeAirline, { timeout: 15000 });
  await page.evaluate(async () => {
    const a = (window as any).activeAirline;
    const id = a.id;
    const ajax = (o: any) =>
      new Promise((r, j) =>
        (window as any).$.ajax({ ...o, success: r, error: (_x: any, _s: any, e: any) => j(e) }),
      );
    if (!a.headquarterAirport) {
      await ajax({ type: "GET", url: `/airlines/${id}/profiles?airportId=3599`, dataType: "json" });
      await ajax({
        type: "PUT",
        url: `/airlines/${id}/profiles/0?airportId=3599`,
        contentType: "application/json; charset=utf-8",
        dataType: "json",
      });
      await (window as any).updateAirlineInfo(id);
      await ajax({ type: "POST", url: `/airlines/${id}/tutorial?skipTutorial=true`, dataType: "json" });
      a.skipTutorial = true;
    }
  });
  await page.waitForFunction(() => (window as any).activeAirline?.headquarterAirport, { timeout: 15000 });
}

const MOCK_CARGO_DEMAND = [
  { toAirportIata: "JFK", toAirportName: "New York JFK", cargoDemand: 520 },
  { toAirportIata: "ORD", toAirportName: "Chicago O'Hare", cargoDemand: 310 },
  { toAirportIata: "LHR", toAirportName: "London Heathrow", cargoDemand: 290 },
];

const MOCK_CARGO_OPPS = [
  {
    originAirportId: 3599,
    destinationAirportId: 3600,
    destinationCode: "JFK",
    destinationName: "New York JFK",
    weeklyCargoDemand: 520,
    weeklyCargoServed: 100,
    weeklyCargoUnserved: 420,
    estimatedYield: 0.01,
    recommendedAircraftModelIds: [1],
    recommendedAircraftModelNames: ["Boeing 747-8F"],
    notes: "Strong trade lane.",
  },
  {
    originAirportId: 3599,
    destinationAirportId: 3601,
    destinationCode: "ORD",
    destinationName: "Chicago O'Hare",
    weeklyCargoDemand: 310,
    weeklyCargoServed: 0,
    weeklyCargoUnserved: 310,
    estimatedYield: 0.01,
    recommendedAircraftModelIds: [],
    recommendedAircraftModelNames: [],
    notes: "Untapped market.",
  },
];

test("airport page: canvas loads with IATA and cargo demand section", async ({ page }) => {
  test.setTimeout(60000);

  await page.route("**/airports/3599/cargo-demand", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(MOCK_CARGO_DEMAND) }),
  );

  await bootstrap(page);

  await page.evaluate(() => (window as any).showAirportDetails(3599));
  await expect(page.locator("#airportCanvas")).toBeVisible({ timeout: 15000 });

  // IATA / ICAO shown in header
  await expect(page.locator("#airportCanvas .airportIataIaco")).toContainText("LAX", { timeout: 10000 });

  // Cargo demand section appears and renders cards
  await expect(page.locator("#airportCargoDemandSection")).toBeVisible({ timeout: 10000 });
  const demandCards = page.locator("#airportCargoDemandCards .card");
  await expect(demandCards).toHaveCount(3, { timeout: 10000 });
  await expect(demandCards.first().locator(".iata")).toHaveText("JFK");
});

test("airport page: cargo opportunities section renders unserved demand cards", async ({ page }) => {
  test.setTimeout(60000);

  await page.route("**/airports/3599/cargo-opportunities", route =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(MOCK_CARGO_OPPS) }),
  );

  await bootstrap(page);

  await page.evaluate(() => (window as any).showAirportDetails(3599));
  await expect(page.locator("#airportCanvas")).toBeVisible({ timeout: 15000 });

  // Opportunities section appears
  await expect(page.locator("#airportCargoOpportunitiesSection")).toBeVisible({ timeout: 10000 });
  const oppCards = page.locator("#airportCargoOpportunitiesCards .card");
  await expect(oppCards).not.toHaveCount(0, { timeout: 10000 });

  // First card has IATA and unserved demand
  const firstCard = oppCards.first();
  await expect(firstCard).toContainText("JFK");
  await expect(firstCard).toContainText("420"); // weeklyCargoUnserved
});

test("airport page: asset catalog visible and modal opens on desktop", async ({ page }) => {
  test.setTimeout(60000);
  await bootstrap(page);

  await page.evaluate(() => (window as any).showAirportDetails(3599));
  await expect(page.locator("#airportCanvas")).toBeVisible({ timeout: 15000 });

  // Asset catalog rows should appear
  await page.waitForSelector("#airportDetailsAssetCatalog .table-row", { timeout: 15000 });
  await expect(page.locator("#airportDetailsAssetCatalog .table-row").first()).toBeVisible();

  // Click first row: detail modal appears
  await page.locator("#airportDetailsAssetCatalog .table-row").first().click();
  await expect(page.locator("#airportAssetDetailsModal")).toBeVisible({ timeout: 5000 });
  await expect(page.locator("#assetModalActionButton")).toBeVisible();
  await expect(page.locator("#assetModalImage")).toBeVisible();
});
