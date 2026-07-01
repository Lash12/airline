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

// ── Helper: open the route planner between two airports and wait for the forecast card ──────────
async function openForecastFor(page: Page, originId: number, destId: number) {
  await page.evaluate(([o, d]) => { (window as any).planLink(o, d); }, [originId, destId]);
  await expect(page.locator("#routeForecastContainer")).toBeVisible({ timeout: 10000 });
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
        competitionSummary: "1 competitor with light frequency.",
        confidenceExplanation: "High confidence: both airports show strong demand signals.",
        recommendation: "OPEN",
        recommendationSeverity: "positive",
        cargoShareEstimate: 0.12,
        aircraftRecommendationReason: "Airbus A320-200 fits the route and has useful belly cargo capacity.",
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
  await expect(page.locator("#forecastRecommendation")).toContainText("Open");
  await expect(page.locator("#forecastCompetitionSummary")).toContainText("1 competitor");
  await expect(page.locator("#forecastConfidenceExplanation")).toContainText("High confidence");
  await expect(page.locator("#forecastPaxDemand")).toHaveText("820");
  await expect(page.locator("#forecastCargoDemand")).toHaveText("140");
  await expect(page.locator("#forecastCargoShare")).toHaveText("12% of revenue");
  await expect(page.locator("#forecastRevenue")).toHaveText("$154,000");
  await expect(page.locator("#forecastCost")).toHaveText("$98,000");
  await expect(page.locator("#forecastProfit")).toHaveText("$56,000");
  
  // Verify aircraft and reasons lists are populated
  const aircraftCard = page.locator("#forecastAircraftRecommendations .aircraft-card");
  await expect(aircraftCard).toBeVisible();
  await expect(aircraftCard).toContainText("Airbus A320-200");
  await expect(aircraftCard).toContainText("Rec. Freq: 14/wk");
  await expect(page.locator("#forecastAircraftReason")).toContainText("useful belly cargo");

  const reasons = page.locator("#forecastReasons li");
  await expect(reasons).toHaveCount(3);
  await expect(reasons.nth(0)).toHaveText("Strong passenger demand on this route.");
});

test("route forecast reasons include competitor airline count and frequency", async ({ page }) => {
  test.setTimeout(60000);

  await page.route("**/route-forecast*", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        originAirportId: 3599,
        destinationAirportId: 3600,
        passengerDemandEstimate: 500,
        cargoDemandEstimate: 0,
        expectedRevenue: 120000,
        expectedCost: 80000,
        expectedProfit: 40000,
        confidenceLevel: "MEDIUM",
        competitionLevel: "MEDIUM",
        competitionSummary: "3 competitors with moderate frequency.",
        confidenceExplanation: "Medium confidence: demand exists, but competition is material.",
        recommendation: "OPEN_CAUTIOUSLY",
        recommendationSeverity: "warning",
        cargoShareEstimate: 0,
        aircraftRecommendationReason: "Airbus A320-200 is the best current fit.",
        recommendedAircraftModels: ["Airbus A320-200"],
        recommendedFrequency: 7,
        reasons: [
          "Moderate passenger demand. Plan schedule and capacity carefully.",
          "Moderate competition: 3 airline(s), 28 flights/wk.",
          "Healthy profit margins projected under typical load factors."
        ]
      })
    });
  });

  await bootstrap(page);
  await openForecastFor(page, 3599, 3600);

  // Competitor detail is in the reasons list
  await expect(page.locator("#forecastCompetitionSummary")).toContainText("3 competitors");
  const reasons = page.locator("#forecastReasons li");
  await expect(reasons).toHaveCount(3);
  const competitionReason = reasons.nth(1);
  await expect(competitionReason).toContainText("3 airline(s)");
  await expect(competitionReason).toContainText("28 flights/wk");
});

test("route forecast thin-market reason appears for low-demand routes", async ({ page }) => {
  test.setTimeout(60000);

  await page.route("**/route-forecast*", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        originAirportId: 3599,
        destinationAirportId: 3600,
        passengerDemandEstimate: 120,
        cargoDemandEstimate: 0,
        expectedRevenue: 30000,
        expectedCost: 25000,
        expectedProfit: 5000,
        confidenceLevel: "MEDIUM",
        competitionLevel: "NONE",
        competitionSummary: "No direct competitors.",
        confidenceExplanation: "Medium confidence: demand exists, but passenger demand is modest.",
        recommendation: "OPEN_CAUTIOUSLY",
        recommendationSeverity: "warning",
        cargoShareEstimate: 0,
        aircraftRecommendationReason: "ATR 72-600 keeps capacity conservative on a thin market.",
        recommendedAircraftModels: ["ATR 72-600"],
        recommendedFrequency: 3,
        reasons: [
          "Moderate passenger demand. Plan schedule and capacity carefully.",
          "No direct competition on this route — a monopoly opportunity.",
          "Thin market (~120 pax/wk). Start with one frame, watch load factors before adding frequency.",
          "Healthy profit margins projected under typical load factors."
        ]
      })
    });
  });

  await bootstrap(page);
  await openForecastFor(page, 3599, 3600);

  // Thin-market advisory is visible in reasons
  const reasons = page.locator("#forecastReasons li");
  await expect(reasons).toHaveCount(4);
  await expect(reasons.nth(2)).toContainText("Thin market");
  await expect(reasons.nth(2)).toContainText("one frame");
});
