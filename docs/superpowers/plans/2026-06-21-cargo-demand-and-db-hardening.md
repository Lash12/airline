# Airport Cargo Demand + DB Pool Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an Airport Cargo Demand panel (top-15 cargo destinations), harden the DB connection pool, and fix the asset-catalog button label.

**Architecture:** A pure `CargoDemandGenerator.topCargoDestinations` helper does the O(N) cargo math; a new cached `/airports/:id/cargo-demand` controller wires inputs and serializes; `airport.js` renders cargo cards reusing the passenger demand-card style. DB hardening = raise the Hikari pool and remove one nested-connection acquisition. Catalog label fix is a one-spot JS change.

**Tech Stack:** Scala (airline-data `CargoDemandGenerator`, airline-web Play controller), ScalaTest, jQuery/plain JS, Playwright.

## Global Constraints

- Gate cargo behaviour on `SoloConfig.cargoEnabled` (`com.patson.data.SoloConfig`); endpoint returns `Json.arr()` when disabled.
- Reuse the cycle cache + 304 pattern exactly as `Application.getAirportDemand` (`app/controllers/Application.scala:817-829`): `IF_NONE_MATCH` vs `s""""$currentCycle""""`, `ResponseCache`, headers `CACHE_CONTROL -> CYCLE_CACHE_CONTROL, ETAG -> s""""$currentCycle""""`.
- No new CSS tokens; reuse existing demand-card markup (`card` class etc. from `renderDemandCards`).
- Endpoints must not hold a DB connection while doing cargo math (load inputs, then compute) — consistent with the pool fix.
- Deploy = push to `master` (OptiPlex deploy + Playwright, pre-authorized). Backend changes need `airline-data` `sbt publishLocal` is NOT required here (web depends on the snapshot; `CargoDemandGenerator` is in airline-data — see Task 2 note).

---

### Task 1: DB pool hardening (pool size + AllianceSource nested connection)

**Files:**
- Modify: `airline-data/src/main/resources/application.conf:32`
- Modify: `airline-data/src/main/scala/com/patson/data/AllianceSource.scala` (`loadAlliancesByQueryString`, ~65-90)

**Interfaces:** none external; behaviour-preserving.

- [ ] **Step 1: Raise the pool size**

In `airline-data/src/main/resources/application.conf` line 32, change:

```
hikari.maxPoolSize = 8
```
to:
```
hikari.maxPoolSize = 16
```

- [ ] **Step 2: Read `loadAlliancesByQueryString`**

Open `airline-data/src/main/scala/com/patson/data/AllianceSource.scala` and read
`loadAlliancesByQueryString` (~lines 65-90). It opens
`Using.resource(Meta.getConnection())`, builds an `alliances` list from the result
set, and **while still inside that connection block** calls
`loadAllianceMembersByAllianceId(...)` (~line 79), which opens a second connection.

- [ ] **Step 3: Scope the read connection to just the query**

Refactor so the alliance rows are read with the connection scoped to the query,
the connection is released, and the member enrichment runs afterward. Apply the
same shape used in `AirlineSource.loadAirlinesByQueryString` (already fixed):

```scala
    val alliances = Using.resource(Meta.getConnection()) { connection =>
      Using.resource(connection.prepareStatement(queryString)) { preparedStatement =>
        // ... existing setObject loop ...
        Using.resource(preparedStatement.executeQuery()) { resultSet =>
          val alliances = new ListBuffer[Alliance]()
          while (resultSet.next()) {
            // ... existing row -> Alliance construction, alliances += alliance ...
          }
          alliances.toList
        }
      }
    }
    // connection released above; enrichment below each takes its own connection
    alliances.foreach { alliance =>
      // ... existing loadAllianceMembersByAllianceId(alliance.id) enrichment ...
    }
    alliances
```

Preserve the existing row-construction and member-enrichment bodies exactly; only
move the connection's closing brace to immediately after the read so the
`loadAllianceMembersByAllianceId` call runs after the connection is released.

- [ ] **Step 4: Compile**

Run: `cd airline-data && sbt compile`
Expected: `[success]`.

- [ ] **Step 5: Commit**

```bash
git add airline-data/src/main/resources/application.conf airline-data/src/main/scala/com/patson/data/AllianceSource.scala
git commit -m "fix(db): raise Hikari pool to 16 and release AllianceSource read connection before member enrichment"
```

---

### Task 2: `CargoDemandGenerator.topCargoDestinations` pure helper (TDD)

**Files:**
- Modify: `airline-data/src/main/scala/com/patson/CargoDemandGenerator.scala`
- Test: `airline-data/src/test/scala/com/patson/CargoDemandGeneratorSpec.scala` (existing)

**Interfaces:**
- Produces: `def topCargoDestinations(fromAirport: Airport, candidates: List[Airport], relationshipsByCountry: Map[String, Int], limit: Int): List[(Airport, Int)]` — returns up to `limit` `(destinationAirport, cargoDemand)` pairs, demand `> 0`, sorted by demand descending, excluding `fromAirport` itself.

- [ ] **Step 1: Write the failing test**

In `CargoDemandGeneratorSpec.scala`, reuse the spec's existing synthetic-`Airport`
construction (it already builds airports for the other tests — use the same helper
/ pattern). Add:

```scala
  "topCargoDestinations" should "return demand>0 destinations sorted desc, capped at limit, excluding self" in {
    // Reuse this spec's existing airport builder. Build a `from` hub with high
    // economic mass and several destinations at varying mass/distance.
    val from = makeAirport(id = 1, country = "US", population = 5000000, income = 40000)
    val near = makeAirport(id = 2, country = "US", population = 4000000, income = 40000) // strong pair
    val small = makeAirport(id = 3, country = "US", population = 1000, income = 1000)     // ~0 demand
    val foreign = makeAirport(id = 4, country = "FR", population = 3000000, income = 35000)
    val rels = Map("US" -> 5, "FR" -> 3)

    val result = CargoDemandGenerator.topCargoDestinations(from, List(from, near, small, foreign), rels, limit = 2)

    result should have size 2                       // capped at limit
    result.map(_._1.id) should not contain 1        // excludes self
    result.map(_._2).reverse shouldBe sorted        // descending by demand
    all(result.map(_._2)) should be > 0             // only demand>0
  }
```

(If the existing spec names its airport helper differently, use that — the point
is synthetic airports with `latitude/longitude/zone/countryCode/population/income`
set, exactly as the other tests in this file already do.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd airline-data && sbt "testOnly com.patson.CargoDemandGeneratorSpec"`
Expected: FAIL — `topCargoDestinations is not a member`.

- [ ] **Step 3: Implement the helper**

In `CargoDemandGenerator.scala`, ensure these imports exist at the top (add any
missing): `import com.patson.Util` and `import com.patson.model.Computation` (the
file already imports `com.patson.model._` for `Airport`; if so, `Computation` is
already in scope — only add what is missing). Then add:

```scala
  /**
   * Top cargo-demand destinations from `fromAirport` among `candidates`.
   * Pure: callers supply the candidate airports and a country->relationship map
   * for `fromAirport`'s country (so this opens no DB connection). O(candidates).
   */
  def topCargoDestinations(fromAirport: Airport, candidates: List[Airport], relationshipsByCountry: Map[String, Int], limit: Int): List[(Airport, Int)] = {
    candidates.iterator.filter(_.id != fromAirport.id).flatMap { to =>
      val distance = Util.calculateDistance(fromAirport.latitude, fromAirport.longitude, to.latitude, to.longitude).toInt
      val relationship = relationshipsByCountry.getOrElse(to.countryCode, 0)
      val affinity = Computation.calculateAffinityValue(fromAirport.zone, to.zone, relationship)
      val demand = computeCargoDemandBetweenAirports(fromAirport, to, affinity, distance)
      if (demand > 0) Some((to, demand)) else None
    }.toList.sortBy(-_._2).take(limit)
  }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd airline-data && sbt "testOnly com.patson.CargoDemandGeneratorSpec"`
Expected: PASS (all tests in the spec).

- [ ] **Step 5: Publish the snapshot for airline-web**

Run: `cd airline-data && sbt publishLocal`
Expected: `[success]` (so airline-web picks up `topCargoDestinations` in Task 3).

- [ ] **Step 6: Commit**

```bash
git add airline-data/src/main/scala/com/patson/CargoDemandGenerator.scala airline-data/src/test/scala/com/patson/CargoDemandGeneratorSpec.scala
git commit -m "feat(cargo): add topCargoDestinations pure helper for per-airport cargo demand"
```

---

### Task 3: `/airports/:id/cargo-demand` endpoint

**Files:**
- Modify: `airline-web/app/controllers/ResponseCache.scala` (add `cargoDemandCache`)
- Modify: `airline-web/app/controllers/Application.scala` (new action + JSON builder)
- Modify: `airline-web/conf/routes`

**Interfaces:**
- Consumes: `CargoDemandGenerator.topCargoDestinations` (Task 2), `AirportCache.getAllAirports()`, `CountrySource.getCountryMutualRelationships(country)`.
- Produces: `GET /airports/:airportId/cargo-demand` → JSON array (≤15) of
  `{ toAirportId, toAirportName, toAirportIata, cargoDemand }`, sorted by
  `cargoDemand` desc; `[]` when `!SoloConfig.cargoEnabled`.

- [ ] **Step 1: Add the cache**

In `airline-web/app/controllers/ResponseCache.scala`, after the `demandCache`
block, add:

```scala
  /** Per-airport cargo demand data — keyed by airportId */
  val cargoDemandCache: Cache[Int, (Int, JsValue)] =
    Caffeine.newBuilder()
      .maximumSize(4000)
      .expireAfterWrite(CYCLE_DURATION_SECONDS, TimeUnit.SECONDS)
      .build[Int, (Int, JsValue)]()
```

If `ResponseCache.invalidateAll()` lists caches explicitly, add
`cargoDemandCache.invalidateAll()` there too (mirror how `demandCache` is handled).

- [ ] **Step 2: Add the route**

In `airline-web/conf/routes`, directly after the existing demand line (line 36):

```
GET	 	 /airports/:airportId/cargo-demand	      controllers.Application.getAirportCargoDemand(airportId : Int)
```

- [ ] **Step 3: Add the controller action + builder**

In `airline-web/app/controllers/Application.scala`: ensure `CargoDemandGenerator`
is imported (add to the existing `import com.patson.{AirportSimulation, DemandGenerator, LinkSimulation}` → include `CargoDemandGenerator`). `SoloConfig`, `CountrySource` come from `com.patson.data._`; `AirportCache` is already imported. Then add, next to `getAirportDemand`:

```scala
  def getAirportCargoDemand(airportId: Int) = Action { request =>
    request.headers.get(IF_NONE_MATCH) match {
      case Some(etag) if etag == s""""$currentCycle"""" =>
        NotModified
      case _ =>
        val json = Option(ResponseCache.cargoDemandCache.getIfPresent(airportId)).filter(_._1 == currentCycle).map(_._2).getOrElse {
          val result = computeAirportCargoDemandJson(airportId)
          ResponseCache.cargoDemandCache.put(airportId, (currentCycle, result))
          result
        }
        Ok(json).withHeaders(CACHE_CONTROL -> CYCLE_CACHE_CONTROL, ETAG -> s""""$currentCycle"""")
    }
  }

  private def computeAirportCargoDemandJson(airportId: Int): JsValue = {
    if (!SoloConfig.cargoEnabled) {
      Json.arr()
    } else {
      AirportCache.getAirport(airportId) match {
        case None => Json.arr()
        case Some(fromAirport) =>
          val candidates = AirportCache.getAllAirports()
          val relationships = CountrySource.getCountryMutualRelationships(fromAirport.countryCode)
          val top = CargoDemandGenerator.topCargoDestinations(fromAirport, candidates, relationships, 15)
          Json.toJson(top.map { case (to, demand) =>
            Json.obj(
              "toAirportId"   -> to.id,
              "toAirportName" -> to.city,
              "toAirportIata" -> to.iata,
              "cargoDemand"   -> demand
            )
          })
      }
    }
  }
```

- [ ] **Step 4: Compile**

Run: `cd airline-web && sbt compile`
Expected: `[success]`.

- [ ] **Step 5: Commit**

```bash
git add airline-web/app/controllers/ResponseCache.scala airline-web/app/controllers/Application.scala airline-web/conf/routes
git commit -m "feat(cargo): add /airports/:id/cargo-demand endpoint (top-15, cycle-cached)"
```

---

### Task 4: Frontend cargo demand cards

**Files:**
- Modify: `airline-web/public/javascripts/airport.js` (load/render + call site)
- Modify: `airline-web/app/views/fragments/airport_canvas.scala.html` (section)

**Interfaces:**
- Consumes: `GET /airports/:id/cargo-demand` (Task 3); existing `commaSeparateNumber`, `activeAirport`.

- [ ] **Step 1: Add the section markup**

In `airline-web/app/views/fragments/airport_canvas.scala.html`, the passenger
demand section is at lines 399-403 (`<div class="section">` … `#airportDemandCards`
… `</div>`). Immediately AFTER that section's closing `</div>` (line 403), add:

```html
		  <div class="section" id="airportCargoDemandSection" style="display:none;">
			  <div id="airportCargoDemandCards" class="vertical-group text-sm"></div>
		  </div>
```

- [ ] **Step 2: Add load + render functions**

In `airline-web/public/javascripts/airport.js`, add near `loadAirportDemand`
(~line 1601):

```javascript
var _cargoDemandEtag = null

async function loadAirportCargoDemand(airportId) {
    const section = document.getElementById('airportCargoDemandSection')
    const container = document.getElementById('airportCargoDemandCards')
    if (!container) return
    try {
        const headers = {}
        if (_cargoDemandEtag) headers['If-None-Match'] = _cargoDemandEtag
        const response = await fetch('/airports/' + airportId + '/cargo-demand', { headers })
        if (response.status === 304) return
        if (!response.ok) { if (section) section.style.display = 'none'; return }
        _cargoDemandEtag = response.headers.get('ETag')
        renderCargoDemandCards(await response.json())
    } catch (e) {
        console.error('loadAirportCargoDemand failed', e)
        if (section) section.style.display = 'none'
    }
}

function renderCargoDemandCards(demands) {
    const section = document.getElementById('airportCargoDemandSection')
    const container = document.getElementById('airportCargoDemandCards')
    if (!container) return
    container.innerHTML = ''
    if (!demands || demands.length === 0) {
        if (section) section.style.display = 'none'
        return
    }
    if (section) section.style.display = ''

    const header = document.createElement('h3')
    header.textContent = `${activeAirport.city} Cargo Demand`
    container.appendChild(header)
    const helper = document.createElement('p')
    helper.textContent = 'Top freight destinations by weekly cargo demand'
    helper.classList = 'pb-4'
    container.appendChild(helper)

    demands.forEach(function(d) {
        const card = document.createElement('div')
        card.className = 'card'

        const headerRow = document.createElement('div')
        headerRow.style.cssText = 'display:flex;justify-content:space-between;align-items:center;margin-bottom:3px;'
        headerRow.innerHTML = `<strong class="iata">${d.toAirportIata}</strong>${d.toAirportName}`

        const statsRow = document.createElement('div')
        statsRow.innerHTML = '<span>&#128230; ' + commaSeparateNumber(d.cargoDemand) + ' cargo</span>'

        card.appendChild(headerRow)
        card.appendChild(statsRow)
        container.appendChild(card)
    })
}
```

- [ ] **Step 3: Call it alongside passenger demand**

In `airport.js`, the passenger demand load is at line 991 (`loadAirportDemand(airport.id)`).
Immediately after it add:

```javascript
    loadAirportCargoDemand(airport.id)
```

- [ ] **Step 4: Syntax check + compile templates**

Run: `cd airline-web && node --check public/javascripts/airport.js && sbt compile`
Expected: no JS error; `[success]`.

- [ ] **Step 5: Commit**

```bash
git add airline-web/public/javascripts/airport.js airline-web/app/views/fragments/airport_canvas.scala.html
git commit -m "feat(cargo): airport cargo demand cards (top freight destinations)"
```

---

### Task 5: Catalog button label fix (C)

**Files:**
- Modify: `airline-web/public/javascripts/airport.js` (`renderAirportAssets` catalog loop, ~line 385)

**Interfaces:** none.

- [ ] **Step 1: Replace the actionLabel derivation**

In `airport.js`, the current code at ~line 385 is:

```javascript
		var actionLabel = !entry.canUpgrade ? 'Max level'
			: (entry.ownedLevel === 0 ? 'Build' : ('Upgrade to ' + (entry.ownedLevel + 1)))
```

Replace with (prioritize `!data.hasBase` so the label never says "Max level"
when the real block is "build a base first"):

```javascript
		var actionLabel
		if (!data.hasBase) {
			actionLabel = entry.ownedLevel === 0 ? 'Build' : ('Upgrade to ' + (entry.ownedLevel + 1))
		} else if (!entry.canUpgrade) {
			actionLabel = 'Max level'
		} else {
			actionLabel = entry.ownedLevel === 0 ? 'Build' : ('Upgrade to ' + (entry.ownedLevel + 1))
		}
```

- [ ] **Step 2: Syntax check**

Run: `cd airline-web && node --check public/javascripts/airport.js`
Expected: no error.

- [ ] **Step 3: Commit**

```bash
git add airline-web/public/javascripts/airport.js
git commit -m "fix(ui): asset catalog button label matches disabled reason (no false Max level)"
```

---

### Task 6: Playwright verification (deploy-gated)

**Files:**
- Create: `airline-web/../e2e/tests/cargo-demand-panel.spec.ts` → `e2e/tests/cargo-demand-panel.spec.ts`

**Interfaces:** reuses the account/HQ bootstrap from `e2e/tests/airport-mobile.spec.ts`.

- [ ] **Step 1: Write the spec**

Create `e2e/tests/cargo-demand-panel.spec.ts`:

```typescript
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
```

(Note: rows is non-empty only when `solo.cargo.enabled` is on for the deploy — the
OptiPlex deploy enables cargo, so expect ≥0 rows and assert sorting only when >1.)

- [ ] **Step 2: Run against live (after deploy)**

Run: `cd e2e && BASE_URL="http://192.168.1.52:9000" npx playwright test cargo-demand-panel.spec.ts --retries=0 --reporter=line`
Expected: 1 passed.

- [ ] **Step 3: Commit**

```bash
git add e2e/tests/cargo-demand-panel.spec.ts
git commit -m "test(e2e): cargo demand endpoint shape + sorting"
```

---

## Execution note

Tasks 1-5 are verified locally (sbt compile, sbt testOnly, node --check). Deploy
once after Task 5 (push to master → OptiPlex), then run Task 6 against the live
server. Manually confirm on the airport page (mobile + desktop): the "Cargo
Demand" section shows top freight destinations (or is hidden if cargo off / no
demand), and a no-base airport's catalog button reads "Build"/"Upgrade", not
"Max level".

## Self-Review

- **Spec coverage:** A backend helper → Task 2; A endpoint+cache+route → Task 3; A
  frontend cards+section+wiring → Task 4; B pool size + AllianceSource → Task 1;
  C label → Task 5; verification → Task 6. All spec sections covered.
- **Placeholders:** none — every code step has concrete code; Task 2's test reuses
  the existing spec's airport builder (named explicitly, not a placeholder).
- **Type consistency:** `topCargoDestinations(Airport, List[Airport], Map[String,Int], Int): List[(Airport,Int)]` defined in Task 2, consumed identically in Task 3; JSON fields `toAirportId/toAirportName/toAirportIata/cargoDemand` match between Task 3 (producer) and Task 4 (consumer) and Task 6 (assertion); `cargoDemandCache` defined Task 3 Step 1, used Step 3.
