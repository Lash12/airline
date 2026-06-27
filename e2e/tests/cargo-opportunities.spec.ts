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

// Shared mock opportunity used across tests
const MOCK_OPP_FULL = {
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
};

const MOCK_OPP_NO_AIRCRAFT = {
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
};

test("cargo opportunities panel renders correctly from mock data", async ({ page }) => {
  test.setTimeout(60000);

  await page.route("**/cargo-opportunities*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
  });

  await bootstrap(page);

  // Call renderCargoOpportunities directly. The airport details panel may not be
  // visible (user hasn't clicked an airport), so we check DOM state via evaluate
  // rather than toBeVisible() which checks the full ancestor chain.
  const state = await page.evaluate((opp) => {
    (window as any).renderCargoOpportunities([opp], 3599);
    const section = document.getElementById('airportCargoOpportunitiesSection');
    const cards = document.querySelectorAll('#airportCargoOpportunitiesCards .card');
    const card = cards[0] as HTMLElement | undefined;
    return {
      sectionDisplay: section?.style.display ?? 'missing',
      cardCount: cards.length,
      text: card?.textContent ?? '',
      aircraftText: (card?.querySelector('.opp-aircraft') as HTMLElement | null)?.textContent ?? '',
      notesText: (card?.querySelector('.opp-notes') as HTMLElement | null)?.textContent ?? '',
      hasPlanBtn: !!(card?.querySelector('button')),
      planBtnText: (card?.querySelector('button') as HTMLElement | null)?.textContent ?? '',
    };
  }, MOCK_OPP_FULL);

  expect(state.sectionDisplay).not.toBe('none');
  expect(state.cardCount).toBe(1);
  expect(state.text).toContain('LAX');
  expect(state.text).toContain('Los Angeles');
  expect(state.text).toContain('500');
  expect(state.text).toContain('120');
  expect(state.text).toContain('380');
  expect(state.aircraftText).toContain('Boeing 747-8F');
  expect(state.notesText).toContain('Long-haul trade lane');
  expect(state.hasPlanBtn).toBe(true);
  expect(state.planBtnText).toContain('Plan cargo route');
});

test("cargo opportunities panel hides when response is empty", async ({ page }) => {
  test.setTimeout(60000);

  await page.route("**/cargo-opportunities*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
  });

  await bootstrap(page);

  const display = await page.evaluate(() => {
    (window as any).renderCargoOpportunities([], 3599);
    return document.getElementById('airportCargoOpportunitiesSection')?.style.display ?? 'missing';
  });

  expect(display).toBe('none');
});

test("cargo opportunities panel shows no-aircraft fallback when model list is empty", async ({ page }) => {
  test.setTimeout(60000);

  await page.route("**/cargo-opportunities*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
  });

  await bootstrap(page);

  const state = await page.evaluate((opp) => {
    (window as any).renderCargoOpportunities([opp], 3599);
    const section = document.getElementById('airportCargoOpportunitiesSection');
    const card = document.querySelector('#airportCargoOpportunitiesCards .card') as HTMLElement | null;
    return {
      sectionDisplay: section?.style.display ?? 'missing',
      aircraftText: (card?.querySelector('.opp-aircraft') as HTMLElement | null)?.textContent ?? '',
    };
  }, MOCK_OPP_NO_AIRCRAFT);

  expect(state.sectionDisplay).not.toBe('none');
  expect(state.aircraftText).toContain('No suitable freighter aircraft');
});

test("cargo opportunities endpoint returns array", async ({ page }) => {
  test.setTimeout(60000);
  await bootstrap(page);
  const res = await page.request.get("/airports/3599/cargo-opportunities");
  expect(res.status()).toBe(200);
  const rows = await res.json();
  expect(Array.isArray(rows)).toBeTruthy();
  if (rows.length > 0) {
    expect(rows[0]).toHaveProperty("destinationCode");
    expect(rows[0]).toHaveProperty("weeklyCargoDemand");
    expect(rows[0]).toHaveProperty("weeklyCargoServed");
    expect(rows[0]).toHaveProperty("weeklyCargoUnserved");
    expect(rows[0]).toHaveProperty("estimatedYield");
    expect(rows[0]).toHaveProperty("recommendedAircraftModelNames");
    expect(rows[0]).toHaveProperty("notes");
  }
});
