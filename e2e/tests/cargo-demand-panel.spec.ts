import { expect, type Page, test } from "@playwright/test";

async function bootstrap(page: Page) {
  const s = Date.now().toString(36).slice(-8);
  await page.goto("/login/", { waitUntil: "load" });
  await page.request.post("/signup/json", { data: { username:`cd${s}`, email:`cd${s}@example.test`, password:`pw${s}`, passwordConfirm:`pw${s}`, airlineName:`Cargo Demand ${s.replace(/[0-9]/g,"a")}` }});
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

test("cargo demand endpoint returns sorted top-N or empty", async ({ page }) => {
  test.setTimeout(60000);
  await bootstrap(page);
  const res = await page.request.get("/airports/3599/cargo-demand");
  expect(res.status()).toBe(200);
  const rows = await res.json();
  expect(Array.isArray(rows)).toBeTruthy();
  expect(rows.length).toBeLessThanOrEqual(15);
  if (rows.length > 1) {
    const demands = rows.map((r:any)=> r.cargoDemand);
    for (let i=1;i<demands.length;i++) expect(demands[i]).toBeLessThanOrEqual(demands[i-1]);
    expect(rows[0]).toHaveProperty("toAirportIata");
  }
});
