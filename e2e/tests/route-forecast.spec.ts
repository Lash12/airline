import { expect, type Page, test } from "@playwright/test";

async function bootstrap(page: Page) {
  const s = Date.now().toString(36).slice(-8);
  await page.goto("/login/", { waitUntil: "load" });
  await page.request.post("/signup/json", { data: { username:`rf${s}`, email:`rf${s}@example.test`, password:`pw${s}`, passwordConfirm:`pw${s}`, airlineName:`Route Forecast ${s.replace(/[0-9]/g,"a")}` }});
  await page.goto("/login/", { waitUntil: "load" });
  await page.evaluate(()=>{localStorage.setItem("sessionActive","true");localStorage.setItem("announcementAgreed","2026-02-25")});
  await page.request.post("/user-login", { headers: { Accept: "application/json" }});
  await page.goto("/map/", { waitUntil: "load" });
  await page.waitForFunction(()=> (window as any).activeAirline, { timeout:15000 });
  await page.evaluate(async () => {
    const a=(window as any).activeAirline, id=a.id;
    const ajax=(o:any)=>new Promise((r,j)=>(window as any).$.ajax({...o,success:r,error:(_x:any,_s:any,e:any)=>j(e)}));
    if(!a.headquarterAirport){
      await ajax({type:"GET",url:`/airlines/${id}/profiles?airportId=3599`,dataType:"json"});
      await ajax({type:"PUT",url:`/airlines/${id}/profiles/0?airportId=3599`,contentType:"application/json; charset=utf-8",dataType:"json"});
      await (window as any).updateAirlineInfo(id);
      await ajax({type:"POST",url:`/airlines/${id}/tutorial?skipTutorial=true`,dataType:"json"});
      a.skipTutorial = true;
    }
  });
  await page.waitForFunction(()=> (window as any).activeAirline?.headquarterAirport, { timeout:15000 });
  return await page.evaluate(()=> (window as any).activeAirline.id);
}

test("route forecast panel is visible and displays forecast details", async ({ page }) => {
  test.setTimeout(60000);
  
  // Set up API interception for the route forecast
  await page.route("**/route-forecast*", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        originAirportId: 3599,
        destinationAirportId: 3600,
        passengerDemandEstimate: 820,
        cargoDemandEstimate: 140,
        expectedRevenue: 154000,
        expectedCost: 98000,
        expectedProfit: 56000,
        confidenceLevel: "HIGH",
        competitionLevel: "LOW",
        recommendedAircraftModels: ["Airbus A320-200"],
        recommendedFrequency: 14,
        reasons: [
          "Strong passenger demand on this route.",
          "Low competition. Market is mostly open.",
          "Aircraft suggestion: Airbus A320-200 fits this route's distance and runway limits."
        ]
      })
    });
  });

  await bootstrap(page);

  // Trigger the planLink flow
  await page.evaluate(() => {
    (window as any).planLink(3599, 3600);
  });

  // Verify the route forecast container is visible
  const container = page.locator("#routeForecastContainer");
  await expect(container).toBeVisible({ timeout: 10000 });

  // Verify contents
  await expect(page.locator("#forecastConfidence")).toHaveText("HIGH");
  await expect(page.locator("#forecastCompetition")).toHaveText("LOW");
  await expect(page.locator("#forecastPaxDemand")).toHaveText("820");
  await expect(page.locator("#forecastCargoDemand")).toHaveText("140");
  await expect(page.locator("#forecastRevenue")).toHaveText("$154,000");
  await expect(page.locator("#forecastCost")).toHaveText("$98,000");
  await expect(page.locator("#forecastProfit")).toHaveText("$56,000");
  
  // Verify aircraft and reasons lists are populated
  const aircraftCard = page.locator("#forecastAircraftRecommendations .aircraft-card");
  await expect(aircraftCard).toBeVisible();
  await expect(aircraftCard).toContainText("Airbus A320-200");
  await expect(aircraftCard).toContainText("Rec. Freq: 14/wk");

  const reasons = page.locator("#forecastReasons li");
  await expect(reasons).toHaveCount(3);
  await expect(reasons.nth(0)).toHaveText("Strong passenger demand on this route.");
});
