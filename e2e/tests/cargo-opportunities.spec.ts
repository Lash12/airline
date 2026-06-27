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

const MOCK_OPP_FULL = {
  originAirportId: 3599,
  destinationAirportId: 3600,
  destinationCode: "LAX",
  destinationName: "Los Angeles",
  weeklyCargoDemand: 500,
  weeklyCargoServed: 120,
  weeklyCargoUnserved: 380,
  estimatedYield: 0.01,
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
  estimatedYield: 0.01,
  recommendedAircraftModelIds: [],
  recommendedAircraftModelNames: [],
  notes: "Untapped market."
};

const MOCK_OPP_SERVED = {
  originAirportId: 3599,
  destinationAirportId: 3602,
  destinationCode: "DFW",
  destinationName: "Dallas",
  weeklyCargoDemand: 300,
  weeklyCargoServed: 300,
  weeklyCargoUnserved: 0,
  estimatedYield: 0.008,
  recommendedAircraftModelIds: [1],
  recommendedAircraftModelNames: ["Boeing 747-8F"],
  notes: "Market is fully served."
};

test("cargo opportunities panel renders cards sorted by unserved demand", async ({ page }) => {
  test.setTimeout(60000);

  await page.route("**/cargo-opportunities*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
  });

  await bootstrap(page);

  // Low-unserved opp before high-unserved opp to verify sort
  const lowOpp = { ...MOCK_OPP_NO_AIRCRAFT, weeklyCargoUnserved: 50, destinationCode: "SFO" };
  const highOpp = { ...MOCK_OPP_FULL, weeklyCargoUnserved: 400 };

  const state = await page.evaluate((opps) => {
    (window as any).renderCargoOpportunities(opps, 3599);
    const section = document.getElementById('airportCargoOpportunitiesSection');
    const cards = document.querySelectorAll('#airportCargoOpportunitiesCards .card');
    const firstCard = cards[0] as HTMLElement | null;
    const secondCard = cards[1] as HTMLElement | null;
    return {
      sectionDisplay: section?.style.display ?? 'missing',
      cardCount: cards.length,
      firstIata: (firstCard?.querySelector('.iata') as HTMLElement | null)?.textContent ?? '',
      secondIata: (secondCard?.querySelector('.iata') as HTMLElement | null)?.textContent ?? '',
      firstRevenue: (firstCard?.querySelector('.opp-revenue') as HTMLElement | null)?.textContent ?? '',
      firstHasPlanBtn: !!(firstCard?.querySelector('.opp-plan-btn')),
    };
  }, [lowOpp, highOpp]);

  // Section visible
  expect(state.sectionDisplay).not.toBe('none');
  expect(state.cardCount).toBe(2);
  // High-unserved comes first after sort
  expect(state.firstIata).toBe('JFK');
  expect(state.secondIata).toBe('SFO');
  // Revenue estimate present for first card
  expect(state.firstRevenue).toContain('capturable');
  // Plan button present for unserved card
  expect(state.firstHasPlanBtn).toBe(true);
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

test("fully-served cards are dimmed and have no plan button", async ({ page }) => {
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
      cardOpacity: card?.style.opacity ?? '',
      hasPlanBtn: !!(card?.querySelector('.opp-plan-btn')),
      badgeText: card?.textContent ?? '',
    };
  }, MOCK_OPP_SERVED);

  // Section still visible (has cards)
  expect(state.sectionDisplay).not.toBe('none');
  // Card dimmed
  expect(parseFloat(state.cardOpacity)).toBeLessThan(1);
  // No plan button for fully-served route
  expect(state.hasPlanBtn).toBe(false);
  // Shows served badge
  expect(state.badgeText).toContain('Served');
});

test("all-served state shows explanatory message, no cards", async ({ page }) => {
  test.setTimeout(60000);

  await page.route("**/cargo-opportunities*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
  });

  await bootstrap(page);

  const state = await page.evaluate((opp) => {
    (window as any).renderCargoOpportunities([opp], 3599);
    const section = document.getElementById('airportCargoOpportunitiesSection');
    const cards = document.querySelectorAll('#airportCargoOpportunitiesCards .card');
    const helperText = (document.querySelector('#airportCargoOpportunitiesCards p') as HTMLElement | null)?.textContent ?? '';
    return {
      sectionDisplay: section?.style.display ?? 'missing',
      cardCount: cards.length,
      helperText,
    };
  }, MOCK_OPP_SERVED);

  expect(state.sectionDisplay).not.toBe('none');
  expect(state.cardCount).toBe(0);
  expect(state.helperText).toContain('currently being served');
});

test("no-aircraft fallback renders italic warning", async ({ page }) => {
  test.setTimeout(60000);

  await page.route("**/cargo-opportunities*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
  });

  await bootstrap(page);

  const state = await page.evaluate((opp) => {
    (window as any).renderCargoOpportunities([opp], 3599);
    const card = document.querySelector('#airportCargoOpportunitiesCards .card') as HTMLElement | null;
    return {
      aircraftText: (card?.querySelector('.opp-aircraft') as HTMLElement | null)?.textContent ?? '',
    };
  }, MOCK_OPP_NO_AIRCRAFT);

  expect(state.aircraftText).toContain('No suitable freighter aircraft');
});

test("show-more button appears when list exceeds 10", async ({ page }) => {
  test.setTimeout(60000);

  await page.route("**/cargo-opportunities*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
  });

  await bootstrap(page);

  const state = await page.evaluate((baseOpp) => {
    const opps = Array.from({ length: 13 }, (_, i) => ({
      ...baseOpp,
      destinationCode: 'A' + String(i).padStart(2, '0'),
      destinationAirportId: 4000 + i,
      weeklyCargoUnserved: 300 - i * 5,
    }));
    (window as any).renderCargoOpportunities(opps, 3599);
    const cards = document.querySelectorAll('#airportCargoOpportunitiesCards .card');
    const showMoreBtn = document.querySelector('#airportCargoOpportunitiesCards .opp-show-more') as HTMLElement | null;
    return {
      initialCardCount: cards.length,
      showMoreText: showMoreBtn?.textContent ?? '',
    };
  }, MOCK_OPP_FULL);

  expect(state.initialCardCount).toBe(10);
  expect(state.showMoreText).toContain('3 more');
});

test("cargo opportunities endpoint returns expected shape", async ({ page }) => {
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
