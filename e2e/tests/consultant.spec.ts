import { expect, type Page, test } from "@playwright/test";

async function bootstrap(page: Page) {
  const s = Date.now().toString(36).slice(-8);
  await page.goto("/login/", { waitUntil: "load" });
  await page.request.post("/signup/json", { data: { username:`cs${s}`, email:`cs${s}@example.test`, password:`pw${s}`, passwordConfirm:`pw${s}`, airlineName:`Consultant Test ${s.replace(/[0-9]/g,"a")}` }});
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

const MOCK_REC = {
  id: 9001,
  category: "CONSULTANT_ADVICE",
  message: 'LAX → JFK · 3,983 km · Boeing 737-800 (154Y) · ~$42,000/wk||{"r":["Strong demand: ~750 pax/wk","No direct competition","Requires fleet expansion — no Boeing 737-800 in your fleet"],"x":true}',
  cycle: 1688,
  isRead: false,
  targetId: "3599-3600"
};

const MOCK_REC_COMMONALITY = {
  id: 9002,
  category: "CONSULTANT_ADVICE",
  message: 'LAX → ORD · 2,800 km · Boeing 737-800 (154Y) · ~$28,000/wk · fits your Boeing 737 fleet (3)||{"r":["Moderate demand: ~320 pax/wk","Low competition","Fleet commonality: 3 Boeing 737 in service"],"x":false}',
  cycle: 1688,
  isRead: false,
  targetId: "3599-3601"
};

const MOCK_MARKET = {
  id: 9003,
  category: "MARKET_OVERVIEW",
  message: 'LAX ↔ JFK · 1,200 pax/wk · 3,983 km · ⚠ fleet gap — consider Boeing 747-8||{"r":["Strong demand: ~1,200 pax/wk","Requires fleet expansion — consider Boeing 747-8"],"x":true}',
  cycle: 1688,
  isRead: false,
  targetId: "3599-3600"
};

test("renderConsultantAdvice shows reason chips and plan button", async ({ page }) => {
  test.setTimeout(60000);
  await page.route("**/consultant-advice*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
  });
  await bootstrap(page);

  const state = await page.evaluate((recs) => {
    (window as any).renderConsultantAdvice(recs);
    const list = document.getElementById('consultantAdviceList');
    const card = list?.querySelector('.consultant-rec-card') as HTMLElement | null;
    const chips = Array.from(card?.querySelectorAll('[style*="background:rgba(255,255,255,0.08)"]') ?? []) as HTMLElement[];
    const planBtn = card?.querySelector('.consultant-plan-btn') as HTMLElement | null;
    const badge = card?.querySelector('.consultant-expansion-badge') as HTMLElement | null;
    const asOf = document.getElementById('consultantAsOf')?.textContent ?? '';
    return {
      cardCount: list?.querySelectorAll('.consultant-rec-card').length ?? 0,
      routeText: (card?.querySelector('.consultant-rec-route') as HTMLElement | null)?.textContent ?? '',
      chipTexts: chips.map(c => c.textContent ?? ''),
      hasPlanBtn: !!planBtn,
      planFromId: planBtn?.getAttribute('data-from') ?? '',
      planToId: planBtn?.getAttribute('data-to') ?? '',
      hasExpansionBadge: !!badge,
      asOfText: asOf,
    };
  }, [MOCK_REC, MOCK_REC_COMMONALITY]);

  expect(state.cardCount).toBe(2);
  // Route header
  expect(state.routeText).toContain('LAX');
  // Reason chips from sidecar
  expect(state.chipTexts.some(t => t.includes('Strong demand'))).toBe(true);
  expect(state.chipTexts.some(t => t.includes('No direct competition'))).toBe(true);
  expect(state.chipTexts.some(t => t.includes('fleet expansion'))).toBe(true);
  // Plan button
  expect(state.hasPlanBtn).toBe(true);
  expect(state.planFromId).toBe('3599');
  expect(state.planToId).toBe('3600');
  // Fleet expansion badge on first card
  expect(state.hasExpansionBadge).toBe(true);
  // Last-refreshed timestamp shown
  expect(state.asOfText).toContain('Last refreshed');
});

test("renderConsultantAdvice shows fleet commonality for non-expansion card", async ({ page }) => {
  test.setTimeout(60000);
  await page.route("**/consultant-advice*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
  });
  await bootstrap(page);

  const state = await page.evaluate((recs) => {
    (window as any).renderConsultantAdvice(recs);
    const list = document.getElementById('consultantAdviceList');
    const cards = Array.from(list?.querySelectorAll('.consultant-rec-card') ?? []) as HTMLElement[];
    const secondCard = cards[1] as HTMLElement | null;
    const chips = Array.from(secondCard?.querySelectorAll('[style*="background:rgba(255,255,255,0.08)"]') ?? []) as HTMLElement[];
    const badge = secondCard?.querySelector('.consultant-expansion-badge') as HTMLElement | null;
    return {
      chipTexts: chips.map(c => c.textContent ?? ''),
      hasExpansionBadge: !!badge,
    };
  }, [MOCK_REC, MOCK_REC_COMMONALITY]);

  expect(state.chipTexts.some(t => t.includes('Fleet commonality'))).toBe(true);
  expect(state.hasExpansionBadge).toBe(false);
});

test("renderConsultantAdvice empty state shows helpful message", async ({ page }) => {
  test.setTimeout(60000);
  await page.route("**/consultant-advice*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
  });
  await bootstrap(page);

  const state = await page.evaluate(() => {
    (window as any).renderConsultantAdvice([]);
    const list = document.getElementById('consultantAdviceList');
    return {
      text: list?.textContent ?? '',
      heading: (document.getElementById('consultantRecsHeading') as HTMLElement | null)?.style.display ?? '',
    };
  });

  expect(state.text).toContain('Assign a manager');
  expect(state.heading).toBe('none');
});

test("renderMarketOverview shows fleet-fit badges and plan button", async ({ page }) => {
  test.setTimeout(60000);
  await page.route("**/consultant-market*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
  });
  await bootstrap(page);

  const state = await page.evaluate((markets) => {
    (window as any).renderMarketOverview(markets);
    const list = document.getElementById('consultantMarketList');
    const card = list?.querySelector('.consultant-market-card') as HTMLElement | null;
    const planBtn = card?.querySelector('.consultant-plan-btn') as HTMLElement | null;
    const badge = card?.querySelector('.consultant-expansion-badge') as HTMLElement | null;
    const chips = Array.from(card?.querySelectorAll('[style*="background:rgba(255,255,255,0.08)"]') ?? []) as HTMLElement[];
    return {
      cardCount: list?.querySelectorAll('.consultant-market-card').length ?? 0,
      hasExpansionBadge: !!badge,
      hasPlanBtn: !!planBtn,
      chipTexts: chips.map(c => c.textContent ?? ''),
    };
  }, [MOCK_MARKET]);

  expect(state.cardCount).toBe(1);
  expect(state.hasExpansionBadge).toBe(true);
  expect(state.hasPlanBtn).toBe(true);
  expect(state.chipTexts.some(t => t.includes('Strong demand'))).toBe(true);
});

test("renderMarketOverview empty state shows level-up hint", async ({ page }) => {
  test.setTimeout(60000);
  await page.route("**/consultant-market*", async (route) => {
    await route.fulfill({ status: 200, contentType: "application/json", body: "[]" });
  });
  await bootstrap(page);

  const text = await page.evaluate(() => {
    (window as any).renderMarketOverview([]);
    return document.getElementById('consultantMarketList')?.textContent ?? '';
  });

  expect(text).toContain('higher level');
});
