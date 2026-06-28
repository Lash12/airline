import { expect, type Page, test, type TestInfo } from "@playwright/test";

async function createAccount(page: Page) {
  const suffix = Date.now().toString(36).slice(-8);
  const username = `cargo${suffix}`;
  const password = `pw${suffix}`;
  // Remove numbers from the airline name to satisfy validation checks
  const sanitizedSuffix = suffix.replace(/[0-9]/g, "a");
  const airlineName = `Cargo Test ${sanitizedSuffix}`;

  await page.goto("/login/", { waitUntil: "load" });
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

  await page.goto("/login/", { waitUntil: "load" });
  await page.evaluate(() => {
    localStorage.setItem("sessionActive", "true");
    localStorage.setItem("announcementAgreed", "2026-02-25");
  });
  const restore = await page.request.post("/user-login", {
    headers: { Accept: "application/json" },
  });
  expect(restore.ok(), await restore.text()).toBeTruthy();
  
  // Wait for initial redirect or game load
  await page.goto("/map/", { waitUntil: "load" });
}

test("Validate Cargo UI and Assets", async ({ page }, testInfo: TestInfo) => {
  test.setTimeout(60000); // 60s timeout
  page.on("console", msg => console.log(`BROWSER LOG: [${msg.type()}] ${msg.text()}`));
  page.on("request", req => {
    if (req.url().includes("/plan-link") || req.url().includes("/profiles") || req.url().includes("/bases")) {
      console.log(`NETWORK REQ: ${req.method()} ${req.url()}`);
    }
  });
  page.on("response", res => {
    if (res.url().includes("/plan-link") || res.url().includes("/profiles") || res.url().includes("/bases")) {
      console.log(`NETWORK RES: ${res.status()} ${res.url()}`);
    }
  });
  page.on("pageerror", err => {
    console.error("PAGE UNCAUGHT ERROR:", err.stack || err.message);
  });

  // 1. Sign up and login
  console.log("Creating test account...");
  await createAccount(page);
  
  // Wait for navbar and activeAirline to load
  await page.waitForSelector("#navPrimary", { state: "visible", timeout: 15000 });
  await page.waitForFunction(() => (window as any).activeAirline, { timeout: 15000 });

  // Establish headquarters (LAX / airport 3599) if it hasn't been set yet
  await page.evaluate(async () => {
    if (!(window as any).activeAirline.headquarterAirport) {
      const airlineId = (window as any).activeAirline.id;
      // Get profiles to trigger profile initialization
      await new Promise((resolve, reject) => {
        (window as any).$.ajax({
          type: 'GET',
          url: `/airlines/${airlineId}/profiles?airportId=3599`,
          dataType: 'json',
          success: resolve,
          error: (xhr, status, err) => reject(new Error(`Failed to get profiles: ${err}`))
        });
      });
      // Build HQ with profile 0 at airport 3599 (LAX)
      await new Promise((resolve, reject) => {
        (window as any).$.ajax({
          type: 'PUT',
          url: `/airlines/${airlineId}/profiles/0?airportId=3599`,
          contentType: 'application/json; charset=utf-8',
          dataType: 'json',
          success: resolve,
          error: (xhr, status, err) => reject(new Error(`Failed to build HQ: ${err}`))
        });
      });
      // Reload airline info
      await (window as any).updateAirlineInfo(airlineId);
      // Disable tutorials
      await new Promise((resolve, reject) => {
        (window as any).$.ajax({
          type: 'POST',
          url: `/airlines/${airlineId}/tutorial?skipTutorial=true`,
          dataType: 'json',
          success: resolve,
          error: (xhr, status, err) => reject(new Error(`Failed to skip tutorial: ${err}`))
        });
      });
      (window as any).activeAirline.skipTutorial = true;
    }
  });

  await page.waitForFunction(() => (window as any).activeAirline && (window as any).activeAirline.headquarterAirport, { timeout: 15000 });
  console.log("Account created. Logged in and initialized with HQ.");

  // Get active airline and HQ info
  const hq = await page.evaluate(() => {
    return {
      id: (window as any).activeAirline.id,
      name: (window as any).activeAirline.name,
      hqAirportId: (window as any).activeAirline.headquarterAirport.airportId,
      hqAirportCode: (window as any).activeAirline.headquarterAirport.airportCode,
      hqAirportName: (window as any).activeAirline.headquarterAirport.airportName
    };
  });
  console.log(`HQ Airport Details: ID=${hq.hqAirportId}, Code=${hq.hqAirportCode}, Name=${hq.hqAirportName}`);

  // 2. Validate Office Page (Financial breakdown containing Cargo Revenue row)
  console.log("Navigating to Office Page...");
  await page.goto("/office/", { waitUntil: "load" });
  await page.waitForSelector("#officeCanvas", { state: "visible", timeout: 10000 });
  
  // Verify that the cargo revenue elements exist
  const cargoRevenueRow = page.locator("#balCargoRevenue");
  await expect(cargoRevenueRow).toBeVisible();
  await cargoRevenueRow.scrollIntoViewIfNeeded();
  
  // Capture Office Page screenshot
  await page.screenshot({ path: testInfo.outputPath("office_cargo.png") });
  console.log("Office screenshot captured.");

  // 3. Navigate to HQ Airport and check Assets
  console.log(`Navigating to HQ Airport /airport/${hq.hqAirportCode}...`);
  await page.goto(`/airport/${hq.hqAirportCode}`, { waitUntil: "load" });
  await page.waitForSelector("#airportCanvas", { state: "visible", timeout: 10000 });
  
  // Capture Airport Info screenshot
  await page.screenshot({ path: testInfo.outputPath("airport_view.png") });
  console.log("Airport info screenshot captured.");

  // Wait for the asset section to be visible
  await page.waitForSelector("#airportDetailsAssetsSection", { state: "visible", timeout: 15000 });

  // Let's scroll the assets catalog into view and take a screenshot
  const assetsContainer = page.locator("#airportDetailsAssetsSection");
  await assetsContainer.scrollIntoViewIfNeeded();
  await page.screenshot({ path: testInfo.outputPath("cargo_terminal_catalog.png") });
  console.log("Assets catalog screenshot captured.");

  // 4. Navigate to Map and open Route Planner
  console.log("Navigating to Route Planner...");
  await page.goto("/map/", { waitUntil: "load" });
  await page.waitForSelector("#worldMapCanvas", { state: "visible", timeout: 10000 });

  // Call planning logic directly in browser to open the planner side panel
  await page.evaluate((hqId) => {
    // Dynamically select targetId (an airport that exists and is not the HQ)
    let targetId = (window as any).airportIataToId?.["JFK"] || 
                   (window as any).airportIataToId?.["SFO"] || 
                   (window as any).airportIataToId?.["ORD"] || 
                   (window as any).airportIataToId?.["LHR"];
    if (!targetId || targetId === hqId) {
      if ((window as any).airportIataToId) {
        for (const [iata, id] of Object.entries((window as any).airportIataToId)) {
          if (id !== hqId) {
            targetId = id;
            break;
          }
        }
      }
    }
    console.log(`[Browser console] Planning link from HQ ${hqId} to target ${targetId}`);
    (window as any).planLink(hqId, targetId);
  }, hq.hqAirportId);
  
  // Wait for planning side panel
  await page.waitForSelector("#planLinkDetails", { state: "visible", timeout: 10000 });
  
  // Switch to cargo type
  console.log("Switching Route Type to Cargo...");
  await page.selectOption("#planLinkTransportType", "CARGO_FLIGHT");
  
  // Verify that class-based price fields are hidden
  const economyPriceInput = page.locator("#planLinkEconomyPrice");
  await expect(economyPriceInput).toBeHidden();
  console.log("Verified: Passenger pricing fields are hidden for cargo flights.");

  // Capture Route Planner screen
  await page.screenshot({ path: testInfo.outputPath("route_planner_cargo.png") });
  console.log("Route planner screenshot captured.");
});
