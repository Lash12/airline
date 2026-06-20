import { expect, type Page, test } from "@playwright/test";

async function createAccount(page: Page) {
  const suffix = Date.now().toString(36).slice(-8);
  const username = `adel${suffix}`;
  const password = `pw${suffix}`;
  const airlineName = `Delivery ${suffix.replace(/[0-9]/g, "a")}`;

  await page.goto("/login/", { waitUntil: "domcontentloaded" });
  const signup = await page.request.post("/signup/json", {
    data: {
      username,
      email: `${username}@example.test`,
      password,
      passwordConfirm: password,
      airlineName,
    },
  });
  expect(signup.ok(), await signup.text()).toBeTruthy();

  await page.evaluate(() => localStorage.setItem("sessionActive", "true"));
  const restore = await page.request.post("/user-login", {
    headers: { Accept: "application/json" },
  });
  expect(restore.ok(), await restore.text()).toBeTruthy();
}

test("pending aircraft delivery text follows current cycle", async ({ page }) => {
  const airplaneId = 900001;
  let currentCycle = 20;
  const constructedCycle = 56;

  await page.route("**/current-cycle", route =>
    route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({ cycle: currentCycle }),
    }),
  );

  await page.route(`**/airlines/*/airplanes/${airplaneId}`, route =>
    route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        id: airplaneId,
        ownerId: 1,
        ownerName: "Delivery Test Air",
        name: "Airbus A350-900",
        modelId: 28,
        capacity: 341,
        ascentBurn: 1,
        cruiseBurn: 1,
        speed: 900,
        range: 15000,
        price: 300000000,
        condition: 100,
        constructedCycle,
        purchasedCycle: 20,
        isReady: false,
        constructionTime: 36,
        purchasePrice: 300000000,
        sellValue: 0,
        dealerValue: 0,
        configurationId: 80,
        configuration: { economy: 288, business: 48, first: 5 },
        homeAirportId: 3599,
        availableFlightMinutes: 0,
        links: [],
      }),
    }),
  );

  await createAccount(page);
  await page.goto("/hangar/", { waitUntil: "domcontentloaded" });
  await page.waitForFunction(() => typeof (window as any).loadOwnedAirplaneDetails === "function");

  await page.evaluate(() => {
    (window as any).activeAirline = {
      id: (window as any).activeAirline?.id || 1,
      balance: 650000000,
      baseAirports: [
        {
          airportId: 3599,
          airportCode: "LAX",
          city: "Los Angeles",
          countryCode: "US",
          airportRunwayLength: 3685,
          headquarter: true,
        },
      ],
    };
    (window as any).gameConstants = (window as any).gameConstants || {};
    (window as any).gameConstants.aircraft = {
      conditionBad: 50,
      conditionCritical: 25,
    };
    (window as any).loadedModelsById = {
      28: {
        id: 28,
        name: "Airbus A350-900",
        price: 300000000,
        constructionTime: 36,
        imageUrl: "",
      },
    };
  });

  await page.evaluate(id => (window as any).loadOwnedAirplaneDetails(id), airplaneId);
  await expect(page.locator("#airplaneDetailsDelivery")).toHaveText("Will be available in 36 week(s)");
  await expect(page.locator("#airplaneDetailsAge")).toBeHidden();

  currentCycle = 27;
  await page.evaluate(id => (window as any).loadOwnedAirplaneDetails(id), airplaneId);
  await expect(page.locator("#airplaneDetailsDelivery")).toHaveText("Will be available in 29 week(s)");
  await expect(page.locator("#airplaneDetailsAge")).toBeHidden();
});
