import { expect, type Page, test } from "@playwright/test";

async function bootstrap(page: Page) {
  const s = Date.now().toString(36).slice(-8);
  await page.goto("/login/", { waitUntil: "load" });
  await page.request.post("/signup/json", { data: { username:`co${s}`, email:`co${s}@example.test`, password:`pw${s}`, passwordConfirm:`pw${s}`, airlineName:`Cargo Opp ${s.replace(/[0-9]/g,"a")}` }});
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
}

const MOCK_OPPORTUNITIES = [
  {
    originAirportId: 3599,
    destinationAirportId: 3600,
    destinationCode: "LAX",
    destinationName: "Los Angeles",
    weeklyCargoDemand: 500,
    weeklyCargoServed: 120,
    weeklyCargoUnserved: 380,
    estimatedYield: 0.0002,
    recommendedAircraftModelIds: [1, 2],
    recommendedAircraftModelNames: ["Boeing 747-8F", "Airbus A330-200F"],
    notes: "Long-haul trade lane; higher yield potential. High unserved demand (76% unserved)."
  },
  {
    originAirportId: 3599,
    destinationAirportId: 3601,
    destinationCode: "ORD",
    destinationName: "Chicago",
    weeklyCargoDemand: 200,
    weeklyCargoServed: 0,
    weeklyCargoUnserved: 200,
    estimatedYield: 0.0002,
    recommendedAircraftModelIds: [],
    recommendedAircraftModelNames: [],
    notes: "Untapped market with zero cargo currently served."
  }
];

test("cargo opportunities panel renders correctly from mock API", async ({ page }) => {
  test.setTimeout(60000);

  await page.route("**/cargo-opportunities*", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(MOCK_OPPORTUNITIES)
    });
  });

  await bootstrap(page);

  await page.evaluate(() => {
    (window as any).renderCargoOpportunities(
      [
        {
          originAirportId: 3599,
          destinationAirportId: 3600,
          destinationCode: "LAX",
          destinationName: "Los Angeles",
          weeklyCargoDemand: 500,
          weeklyCargoServed: 120,
          weeklyCargoUnserved: 380,
          estimatedYield: 0.0002,
          recommendedAircraftModelIds: [1, 2],
          recommendedAircraftModelNames: ["Boeing 747-8F", "Airbus A330-200F"],
          notes: "Long-haul trade lane; higher yield potential."
        }
      ],
      3599
    );
  });

  const section = page.locator("#airportCargoOpportunitiesSection");
  await expect(section).toBeVisible({ timeout: 5000 });

  const cards = page.locator("#airportCargoOpportunitiesCards .card");
  await expect(cards).toHaveCount(1);

  const card = cards.first();
  await expect(card).toContainText("LAX");
  await expect(card).toContainText("Los Angeles");
  await expect(card).toContainText("500");
  await expect(card).toContainText("120");
  await expect(card).toContainText("380");
  await expect(card.locator(".opp-aircraft")).toContainText("Boeing 747-8F");
  await expect(card.locator(".opp-notes")).toContainText("Long-haul trade lane");
  await expect(card.locator("button")).toContainText("Plan cargo route");
});

test("cargo opportunities panel hides when response is empty", async ({ page }) => {
  test.setTimeout(60000);

  await page.route("**/cargo-opportunities*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
  });

  await bootstrap(page);

  await page.evaluate(() => {
    (window as any).renderCargoOpportunities([], 3599);
  });

  const section = page.locator("#airportCargoOpportunitiesSection");
  await expect(section).toBeHidden();
});

test("cargo opportunities panel shows no-aircraft fallback when model list is empty", async ({ page }) => {
  test.setTimeout(60000);

  await page.route("**/cargo-opportunities*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
  });

  await bootstrap(page);

  await page.evaluate(() => {
    (window as any).renderCargoOpportunities(
      [
        {
          originAirportId: 3599,
          destinationAirportId: 3601,
          destinationCode: "ORD",
          destinationName: "Chicago",
          weeklyCargoDemand: 200,
          weeklyCargoServed: 0,
          weeklyCargoUnserved: 200,
          estimatedYield: 0.0002,
          recommendedAircraftModelIds: [],
          recommendedAircraftModelNames: [],
          notes: "Untapped market."
        }
      ],
      3599
    );
  });

  const section = page.locator("#airportCargoOpportunitiesSection");
  await expect(section).toBeVisible({ timeout: 5000 });

  const card = page.locator("#airportCargoOpportunitiesCards .card").first();
  await expect(card.locator(".opp-aircraft")).toContainText("No suitable freighter aircraft");
});
