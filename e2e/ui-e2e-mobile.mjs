/**
 * Mobile UI regression sweep (iPhone 13 viewport).
 *
 * For every SPA route it asserts: exactly one expected canvas is visible (catches
 * cross-page canvas/panel leaks), no horizontal overflow, no clickable blocked by a
 * foreign element (the floating menu/chat are whitelisted), and no JS page errors.
 * Plus interaction tests: flights route-click opens the detail with no cost-calculator
 * leak, and hangar plane-tap opens its overlay with nothing blocked.
 *
 * Run: BASE=http://<host>:9000 AIRLINE_USER=.. AIRLINE_PASS=.. node e2e/ui-e2e-mobile.mjs
 * Exit code is non-zero if any check fails.
 */
import { chromium, devices } from '@playwright/test';
import { mkdirSync } from 'fs';
const BASE=process.env.BASE||'http://192.168.1.52:9000', USER=process.env.AIRLINE_USER, PASS=process.env.AIRLINE_PASS;
const OUT=process.env.SHOTS||'./shots/e2e/';
try{ mkdirSync(OUT,{recursive:true}); }catch(e){}

// Candidate fix for the airplane-canvas leak: only show #airplaneCanvas when active.
// (Deployed CSS currently has `#airplaneCanvas { display:block !important }`.)
const FIX = process.env.NOFIX ? '' : `
@media only screen and (max-device-width : 640px) {
  #airplaneCanvas:not(.active) { display: none !important; }
}`;

const ROUTES = ['map','search','airport','flights','hangar','aircraft','office','champions','bank','oil','rivals','alliance','country','olympics'];
const CANVASES = ['worldMapCanvas','searchCanvas','airportCanvas','linksCanvas','airplaneCanvas','officeCanvas','rankingCanvas','bankCanvas','oilCanvas','rivalsCanvas','allianceCanvas','countryCanvas','eventCanvas'];
// bare /airport/ has no airport to show, so it redirects to the map by design
const EXPECT = { map:'worldMapCanvas', search:'searchCanvas', airport:'worldMapCanvas', flights:'linksCanvas', hangar:'airplaneCanvas', aircraft:'airplaneCanvas', office:'officeCanvas', champions:'rankingCanvas', bank:'bankCanvas', oil:'oilCanvas', rivals:'rivalsCanvas', alliance:'allianceCanvas', country:'countryCanvas', olympics:'eventCanvas' };

const b=await chromium.launch(); const c=await b.newContext({...devices['iPhone 13']});
await c.addInitScript(()=>{try{localStorage.setItem('announcementAgreed','2026-02-25')}catch(e){}});
const p=await c.newPage();
const errors=[]; p.on('pageerror',e=>errors.push('JS:'+e.message.slice(0,80)));
await p.goto(`${BASE}/login/`,{waitUntil:'networkidle'});
await p.fill('#loginPageUserName',USER); await p.fill('#loginPagePassword',PASS); await p.click('.login-page-btn');
await p.waitForSelector('#navPrimary',{state:'visible',timeout:30000}).catch(()=>{});
await p.waitForTimeout(2000);
// map-overlay routes legitimately keep the map canvas behind them
const MAP_OVERLAY = new Set(['search']);

let pass=0, fail=0;
const log=(ok,msg)=>{ console.log((ok?'  PASS ':'  FAIL ')+msg); ok?pass++:fail++; };

for(const route of ROUTES){
  console.log(`\n=== /${route}/ ===`);
  errors.length=0;
  await p.goto(`${BASE}/${route}/`,{waitUntil:'networkidle',timeout:25000}).catch(()=>{});
  await p.waitForTimeout(2800);
  if(FIX) await p.addStyleTag({content:FIX});
  await p.waitForTimeout(300);
  const r = await p.evaluate((args)=>{
    const {CANVASES, EXPECT, route} = args;
    const vis = CANVASES.filter(id=>{const el=document.getElementById(id); if(!el) return false; const cs=getComputedStyle(el); const rr=el.getBoundingClientRect(); return cs.display!=='none' && rr.width>2 && rr.height>2;});
    const overflow = document.body.scrollWidth > window.innerWidth+2;
    // sample interactive elements in the active canvas; report any blocked by a FOREIGN element
    const active=document.querySelector('.canvas.active') || document.getElementById(EXPECT[route]);
    let blocked=[];
    if(active){
      const cl=[...active.querySelectorAll('.clickable,[onclick],a.button,.button,a[href]')].filter(e=>{const rr=e.getBoundingClientRect(); return rr.width>4&&rr.height>4&&rr.top>=44&&rr.top<window.innerHeight;}).slice(0,25);
      // known intentional floating overlays (the hamburger menu + chat button) are not bugs
      const isFloatingUI = el => { if(!el) return false; let n=el; for(let i=0;i<4&&n;i++){ const id=n.id||''; const cl=(n.className||'').toString(); if(/navPrimary|menuToggle|messenger|chatButton|notificationButton|floating/i.test(id+' '+cl)) return true; n=n.parentElement;} return false; };
      blocked = cl.filter(e=>{const rr=e.getBoundingClientRect();const hit=document.elementFromPoint(rr.left+Math.min(rr.width/2,20), rr.top+rr.height/2); return hit && !e.contains(hit) && !hit.contains(e) && hit!==e && !active.contains(hit) && !isFloatingUI(hit);}).map(e=>{const rr=e.getBoundingClientRect();const hit=document.elementFromPoint(rr.left+Math.min(rr.width/2,20), rr.top+rr.height/2);return (e.className||e.tagName).toString().slice(0,24)+' <blocked-by> '+(hit?((hit.id?'#'+hit.id:'')+'.'+(hit.className||hit.tagName).toString().slice(0,24)+' pos='+getComputedStyle(hit).position):'null');});
    }
    return {vis, overflow, blocked:[...new Set(blocked)]};
  }, {CANVASES, EXPECT, route});
  const canvasOk = MAP_OVERLAY.has(route) ? r.vis.includes(EXPECT[route]) : (r.vis.length===1 && r.vis[0]===EXPECT[route]);
  log(canvasOk, `expected canvas visible (got [${r.vis.join(',')}], expect ${EXPECT[route]})`);
  log(!r.overflow, `no horizontal overflow`);
  log(r.blocked.length===0, `no clickables blocked by foreign element${r.blocked.length?' ('+r.blocked.join(', ')+')':''}`);
  log(errors.length===0, `no JS page errors${errors.length?' ('+errors.join(' | ')+')':''}`);
  await p.screenshot({path:`${OUT}${route}.png`,fullPage:false}).catch(()=>{});
}

// ---- Interaction tests ----
console.log(`\n=== INTERACTION: flights route click ===`);
await p.goto(`${BASE}/flights/`,{waitUntil:'networkidle'}); await p.waitForTimeout(3000);
if(FIX) await p.addStyleTag({content:FIX});
const row=await p.$('#linksCanvas .table.data .table-row[data-link-id] .cell:nth-child(2)');
if(row){ await row.click().catch(()=>{}); await p.waitForTimeout(2000);
  const rr=await p.evaluate(()=>{
    const cc=document.querySelector('.airplaneCanvasRightGroup'); const ccVis = cc && getComputedStyle(cc).display!=='none' && cc.getBoundingClientRect().height>2;
    const det=document.querySelector('#linkDetails'); const detVis = det && getComputedStyle(det).display!=='none' && det.getBoundingClientRect().height>2;
    return {ccVis, detVis};
  });
  log(!rr.ccVis, `cost calculator NOT leaking onto flights route detail`);
  log(rr.detVis, `route detail panel opens on click`);
} else log(false,'could not find a route row to click');

console.log(`\n=== INTERACTION: hangar plane click ===`);
await p.goto(`${BASE}/hangar/`,{waitUntil:'networkidle'}); await p.waitForTimeout(3500);
if(FIX) await p.addStyleTag({content:FIX});
const planeBlocked = await p.evaluate(()=>{
  const left=document.querySelector('.airplaneCanvasLeftGroup');
  const secs=[...left.querySelectorAll('.section.clickable,.clickable.isAirplane')].filter(e=>{const r=e.getBoundingClientRect();return r.width>0&&r.height>0;});
  return secs.filter(e=>{const r=e.getBoundingClientRect();const hit=document.elementFromPoint(r.left+r.width/2,r.top+r.height/2);return hit&&!e.contains(hit)&&hit!==e;}).length;
});
log(planeBlocked===0, `no hangar plane sections blocked (${planeBlocked} blocked)`);
const picon=await p.$$('.airplaneCanvasLeftGroup .clickable.isAirplane');
if(picon.length){ const last=picon[picon.length-1]; await last.scrollIntoViewIfNeeded(); await last.click().catch(()=>{}); await p.waitForTimeout(1200);
  const modal=await p.evaluate(()=>[...document.querySelectorAll('.modal')].some(m=>getComputedStyle(m).display!=='none'));
  log(modal, `tapping a plane opens its detail overlay`);
}

console.log(`\n========== ${pass} passed, ${fail} failed ==========`);
await b.close();
process.exit(fail?1:0);
