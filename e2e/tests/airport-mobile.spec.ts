import { expect, type Page, test } from "@playwright/test";
import * as path from "path";

const SHOTS = path.join(__dirname, "..", "shots", "airport-mobile");

async function bootstrap(page: Page) {
  const s = Date.now().toString(36).slice(-8);
  await page.goto("/login/", { waitUntil: "load" });
  await page.request.post("/signup/json", { data: { username:`am${s}`, email:`am${s}@example.test`, password:`pw${s}`, passwordConfirm:`pw${s}`, airlineName:`Airport M ${s.replace(/[0-9]/g,"a")}` }});
  await page.goto("/login/", { waitUntil: "load" });
  await page.evaluate(()=>{localStorage.setItem("sessionActive","true");localStorage.setItem("announcementAgreed","2026-02-25")});
  await page.request.post("/user-login", { headers: { Accept: "application/json" }});
  await page.goto("/map/", { waitUntil: "load" });
  await page.waitForFunction(()=> (window as any).activeAirline, { timeout:15000 });
  await page.evaluate(async () => {
    const a=(window as any).activeAirline; const id=a.id;
    const ajax=(o:any)=>new Promise((res,rej)=>(window as any).$.ajax({...o,success:res,error:(_x:any,_s:any,e:any)=>rej(e)}));
    if(!a.headquarterAirport){
      await ajax({type:"GET",url:`/airlines/${id}/profiles?airportId=3599`,dataType:"json"});
      await ajax({type:"PUT",url:`/airlines/${id}/profiles/0?airportId=3599`,contentType:"application/json; charset=utf-8",dataType:"json"});
      await (window as any).updateAirlineInfo(id);
      await ajax({type:"POST",url:`/airlines/${id}/tutorial?skipTutorial=true`,dataType:"json"});
      a.skipTutorial = true;   // stop checkTutorial() from showing tutorial modals client-side
    }
  });
  await page.waitForFunction(()=> (window as any).activeAirline?.headquarterAirport, { timeout:15000 });
}

test.use({ viewport: { width: 390, height: 844 } });

test("airport page mobile: tables scroll, asset modal opens", async ({ page }) => {
  test.setTimeout(60000);
  await bootstrap(page);
  // Open the airport detail SPA view via the in-app entry point (direct /airport/<id>
  // URL only loads the SPA shell on the map; it does not activate the airport canvas).
  await page.evaluate(() => (window as any).showAirportDetails(3599));
  await page.waitForSelector("#airportDetailsAssetCatalog .table-row", { timeout: 15000 });
  // Belt-and-suspenders: remove any tutorial/announcement overlay so it can't intercept the tap.
  await page.evaluate(() => {
    (window as any).closeAllModals && (window as any).closeAllModals();
    document.querySelectorAll('.tutorial.modal, #tutorialHtml').forEach(e => (e as HTMLElement).remove());
  });

  // Catalog table should overflow horizontally (scrollWidth > clientWidth), not stack.
  const overflow = await page.$eval("#airportDetailsAssetCatalog", el => el.scrollWidth > el.clientWidth + 4);
  expect(overflow).toBeTruthy();
  await page.screenshot({ path: path.join(SHOTS, "airport_assets_mobile.png") });

  // Tap first catalog row -> modal with image + readable action button.
  await page.locator("#airportDetailsAssetCatalog .table-row").first().click();
  await page.waitForSelector("#airportAssetDetailsModal", { state: "visible", timeout: 5000 });
  await expect(page.locator("#assetModalActionButton")).toBeVisible();
  await expect(page.locator("#assetModalImage")).toBeVisible();
  await page.screenshot({ path: path.join(SHOTS, "asset_modal_mobile.png") });
});
