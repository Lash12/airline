import { chromium, firefox } from '@playwright/test';
const BASE=process.env.BASE||'http://192.168.1.52:9000', USER=process.env.AIRLINE_USER, PASS=process.env.AIRLINE_PASS;
async function check(engine, name, w, h){
  const b=await engine.launch();
  const c=await b.newContext({ viewport:{width:w,height:h}, deviceScaleFactor: w<700?2.6:1 });
  await c.addInitScript(()=>{try{localStorage.setItem('announcementAgreed','2026-02-25')}catch(e){}});
  const p=await c.newPage();
  await p.goto(`${BASE}/login/`,{waitUntil:'domcontentloaded'}).catch(()=>{});
  await p.fill('#loginPageUserName',USER).catch(()=>{}); await p.fill('#loginPagePassword',PASS).catch(()=>{}); await p.click('.login-page-btn').catch(()=>{});
  await p.waitForSelector('#navPrimary',{state:'visible',timeout:30000}).catch(()=>{});
  await p.goto(`${BASE}/flights/`,{waitUntil:'networkidle'}).catch(()=>{}); await p.waitForTimeout(2500);
  const r = await p.evaluate(()=>({
    innerW: innerWidth,
    mqMaxWidth640: matchMedia('(max-width: 640px)').matches,
    desktopOnlyHidden: (()=>{const e=document.querySelector('#topBar .desktopOnly'); return e? getComputedStyle(e).display==='none':'no-el';})(),
    starsHidden: (()=>{const e=document.getElementById('topReputationStars'); return e? getComputedStyle(e).display==='none':'no-el';})(),
    linksPanelWidth: (()=>{const e=document.querySelector('#linksCanvas .mainPanel'); return e? Math.round(e.getBoundingClientRect().width)+'/'+Math.round((e.parentElement||document.body).getBoundingClientRect().width):'no-el';})(),
  }));
  const expectMobile = w<=640;
  const ok = (r.desktopOnlyHidden===expectMobile);
  console.log(`[${name} @${w}px] ${ok?'OK ':'BAD'} mobile=${expectMobile} | max-width:640=${r.mqMaxWidth640} desktopOnlyHidden=${r.desktopOnlyHidden} starsHidden=${r.starsHidden} linksPanelW=${r.linksPanelWidth}`);
  await b.close();
}
await check(chromium,'Chromium',412,915);
await check(firefox ,'Firefox ',412,915);
await check(chromium,'Chromium',1280,800);
await check(firefox ,'Firefox ',1280,800);
