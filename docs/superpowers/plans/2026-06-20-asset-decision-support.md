# Asset Decision Support Implementation Plan

> **Status: COMPLETE — shipped & live-validated 2026-06-20.** All tasks done; per-route demographics
> implemented via a per-leg join (`passenger_link_history → link → passenger_route_history`) instead
> of the per-partner O-D approximation in Task 3 (which returned empty for high-transfer hubs).

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the airport screen a decision-support surface — show whole-market traffic analytics (transfer share + demographics) and explain each airport asset's benefit/ROI with imagery — so a player can judge what to build and where.

**Architecture:** Pure aggregation/format helpers in `airline-data` (unit-tested), a read-only `GET /airports/:id/traffic-analytics` endpoint on `Application` built from existing `link_statistics` (transfer/direct + premium) and `passenger_route_history` (demographics), an enriched asset catalog JSON, and an airport-panel UI (analytics section + asset tooltips/images). Analytics is always-on (no `solo.*` gate); asset tooltips appear only where the flag-gated Assets section shows.

**Tech Stack:** Scala 2.13 (sbt, ScalaTest), Play (controllers/routes), jQuery + scala.html templates, MySQL.

**Spec:** `docs/superpowers/specs/2026-06-20-asset-decision-support-design.md`

**Scope note (read first):** per-route rows show **volume + transfer% + premium% + passenger-type demographic mix**; the **airport summary** shows transfer% and the overall demographic mix. Transfer/volume/premium come from `link_statistics` (per arriving leg, accurate). Per-route demographics come from `passenger_route_history` grouped by **partner airport** for O-D journeys between this airport and that partner (a robust origin/destination grouping — not a fragile per-leg `link` id join). The two are merged by partner airport id; for hub legs they describe slightly different populations (O-D city-pair mix vs all pax on the leg), which is acceptable and clearly the "who travels between these two cities" view. Already done in prior commits: the 29 asset PNGs under `airline-web/public/images/airport-assets/` and the root `NOTICE`.

---

## File structure

- `airline-data/.../model/AirportAsset.scala` — add `image` + `benefit` to `AirportAssetType`, and pure `netWeekly` / `paybackCycles` helpers.
- `airline-data/.../model/AirportTrafficStats.scala` (new) — pure aggregation of `List[LinkStatistics]` → summary + per-route rows; pure share math.
- `airline-data/.../data/ConsumptionHistorySource.scala` — add `loadAirportDemographics(airportId)`.
- `airline-data/.../test/.../AirportAssetSpec.scala` — extend (netWeekly/payback).
- `airline-data/.../test/.../AirportTrafficStatsSpec.scala` (new).
- `airline-web/.../controllers/Application.scala` — add `getAirportTrafficAnalytics`.
- `airline-web/.../controllers/AirportAssetApplication.scala` — enrich catalog JSON.
- `airline-web/conf/routes` — add analytics route.
- `airline-web/.../views/fragments/airport_canvas.scala.html` — analytics section markup.
- `airline-web/.../public/javascripts/airport.js` — render analytics + asset tooltips/images.

---

## Task 1: Asset benefit/ROI helpers + imagery on `AirportAssetType`

**Files:**
- Modify: `airline-data/src/main/scala/com/patson/model/AirportAsset.scala`
- Test: `airline-data/src/test/scala/com/patson/AirportAssetSpec.scala`

- [ ] **Step 1: Add `image` + `benefit` to the type and `netWeekly`/`paybackCycles` helpers.**

In `AirportAsset.scala`, add two fields to the `sealed abstract class AirportAssetType(...)` constructor — `val image : String` and `val benefit : String` — placed after `sizeRequirement`, and add these methods to the class body:

```scala
  /** Weekly income minus upkeep at a level. Negative for attraction/infrastructure by design. */
  def netWeekly(airport : Airport, level : Int) : Long = weeklyIncome(airport, level) - upkeep(airport, level)

  /** Cycles to recoup one level's construction cost from net weekly cash, if ever (None if net <= 0). */
  def paybackCycles(airport : Airport, level : Int) : Option[Int] = {
    val net = netWeekly(airport, level)
    if (net <= 0) None else Some(Math.ceil(constructionCost(airport, level).toDouble / net).toInt)
  }
```

Update each case object with `image` + `benefit` (append the two args after the size value):

```scala
  case object SHOPPING_MALL     extends AirportAssetType("SHOPPING_MALL", "Shopping Mall", REVENUE, AirportBoostType.INCOME, 3000, 150_000_000L, 16, 4, "SHOPPING_MALL.png", "Raises overall passenger demand at this airport by lifting its income level, and earns rent.")
  case object GRAND_HOTEL       extends AirportAssetType("GRAND_HOTEL", "Grand Hotel", REVENUE, AirportBoostType.INCOME, 2500, 120_000_000L, 12, 5, "GRAND_HOTEL_BUSINESS.png", "Raises overall passenger demand at this airport by lifting its income level, and earns room revenue.")
  case object RESORT            extends AirportAssetType("RESORT", "Resort", ATTRACTION, AirportBoostType.VACATION_HUB, 4, 90_000_000L, 12, 3, "BEACH_RESORT.png", "Strengthens this airport as a vacation hub, drawing more inbound tourist demand.")
  case object CONVENTION_CENTER extends AirportAssetType("CONVENTION_CENTER", "Convention Center", ATTRACTION, AirportBoostType.FINANCIAL_HUB, 5, 200_000_000L, 20, 6, "CONVENTION_CENTER.png", "Strengthens this airport as a financial hub, drawing more inbound business demand.")
  case object LANDMARK          extends AirportAssetType("LANDMARK", "Landmark", ATTRACTION, AirportBoostType.INTERNATIONAL_HUB, 4, 250_000_000L, 24, 7, "LANDMARK.png", "Strengthens this airport as an international hub, drawing more inbound long-haul tourist demand.")
  case object METRO             extends AirportAssetType("METRO", "Metro / Transit", INFRASTRUCTURE, AirportBoostType.POPULATION, 30000, 200_000_000L, 20, 5, "SUBWAY.png", "Grows the catchment population around this airport, raising demand across the board. No direct income.")
```

- [ ] **Step 2: Add tests to `AirportAssetSpec`.**

Add inside the existing spec (uses its `airport()` and `import AirportAssetType._`):

```scala
  "netWeekly / paybackCycles".must {
    "give a positive net and finite payback for revenue assets".in {
      SHOPPING_MALL.netWeekly(airport(), 1) should be > 0L
      SHOPPING_MALL.paybackCycles(airport(), 1) shouldBe defined
    }
    "give no payback for infrastructure (no income, net negative)".in {
      METRO.netWeekly(airport(), 1) should be < 0L
      METRO.paybackCycles(airport(), 1) shouldBe None
    }
    "map each asset to an image file that exists in the assets directory".in {
      AirportAssetType.values.foreach { t =>
        new java.io.File(s"../airline-web/public/images/airport-assets/${t.image}").exists() shouldBe true
      }
    }
  }
```

- [ ] **Step 3: Run tests.** `cd airline-data && sbt "testOnly com.patson.AirportAssetSpec"` — Expected: PASS (now ~20 tests). (The image-file test runs from `airline-data/`, so the `../airline-web/...` relative path resolves.)

- [ ] **Step 4: Commit.** `git add airline-data && git commit -m "feat(assets): asset benefit text, imagery, netWeekly/payback helpers"`

---

## Task 2: Pure traffic-analytics aggregation

**Files:**
- Create: `airline-data/src/main/scala/com/patson/model/AirportTrafficStats.scala`
- Test: `airline-data/src/test/scala/com/patson/AirportTrafficStatsSpec.scala`

Transfer semantics from `link_statistics` (see `LinkStatisticsSource`): for arrivals into airport X (`to_airport = X`), a row's `isDestination = true` means those passengers **terminate** at X (direct/O-D); `isDestination = false` means X is a **connection** and they transfer onward.

- [ ] **Step 1: Write the failing test.**

```scala
package com.patson

import com.patson.model._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class AirportTrafficStatsSpec extends AnyWordSpecLike with Matchers {
  private def ap(id: Int) = Airport.fromId(id)
  private val al = Airline.fromId(1)
  private def stat(from: Int, to: Int, isDest: Boolean, pax: Int, premium: Int) =
    LinkStatistics(LinkStatisticsKey(ap(from), ap(to), isDeparture = false, isDestination = isDest, al), pax, premium, 0)

  "arrivalsByOrigin" must {
    "aggregate per origin with terminating/connecting split and premium" in {
      val rows = List(
        stat(10, 99, isDest = true, pax = 80, premium = 8),
        stat(10, 99, isDest = false, pax = 20, premium = 2),
        stat(20, 99, isDest = true, pax = 50, premium = 0))
      val result = AirportTrafficStats.arrivalsByOrigin(rows).sortBy(-_.totalPax)
      result.map(_.airportId) shouldBe List(10, 20)
      val r10 = result.head
      r10.totalPax shouldBe 100
      r10.terminatingPax shouldBe 80
      r10.connectingPax shouldBe 20
      r10.premiumPax shouldBe 10
      r10.transferShare shouldBe 0.2 +- 0.0001
    }
  }

  "summary" must {
    "report overall transfer share across all arrival rows" in {
      val rows = List(
        stat(10, 99, isDest = true, pax = 75, premium = 0),
        stat(20, 99, isDest = false, pax = 25, premium = 0))
      val s = AirportTrafficStats.summary(rows)
      s.totalPax shouldBe 100
      s.connectingPax shouldBe 25
      s.transferShare shouldBe 0.25 +- 0.0001
    }
    "be safe on empty input" in {
      val s = AirportTrafficStats.summary(Nil)
      s.totalPax shouldBe 0
      s.transferShare shouldBe 0.0
    }
  }
}
```

- [ ] **Step 2: Run to verify it fails.** `cd airline-data && sbt "testOnly com.patson.AirportTrafficStatsSpec"` — Expected: FAIL (AirportTrafficStats not found).

- [ ] **Step 3: Implement.**

```scala
package com.patson.model

/** Pure aggregation of link_statistics arrival rows into player-facing traffic analytics. */
object AirportTrafficStats {
  case class RouteRow(airportId : Int, totalPax : Int, terminatingPax : Int, connectingPax : Int, premiumPax : Int) {
    def transferShare : Double = if (totalPax <= 0) 0.0 else connectingPax.toDouble / totalPax
  }
  case class Summary(totalPax : Int, terminatingPax : Int, connectingPax : Int, premiumPax : Int) {
    def transferShare : Double = if (totalPax <= 0) 0.0 else connectingPax.toDouble / totalPax
  }

  /** Group arrival LinkStatistics (to_airport = X) by origin airport. isDestination => terminating. */
  def arrivalsByOrigin(arrivals : List[LinkStatistics]) : List[RouteRow] =
    arrivals.groupBy(_.key.fromAirport.id).map { case (originId, rows) =>
      val total = rows.map(_.passengers).sum
      val terminating = rows.filter(_.key.isDestination).map(_.passengers).sum
      val premium = rows.map(_.premiumPax).sum
      RouteRow(originId, total, terminating, total - terminating, premium)
    }.toList

  def summary(arrivals : List[LinkStatistics]) : Summary = {
    val total = arrivals.map(_.passengers).sum
    val terminating = arrivals.filter(_.key.isDestination).map(_.passengers).sum
    val premium = arrivals.map(_.premiumPax).sum
    Summary(total, terminating, total - terminating, premium)
  }
}
```

- [ ] **Step 4: Run to verify it passes.** `sbt "testOnly com.patson.AirportTrafficStatsSpec"` — Expected: PASS.

- [ ] **Step 5: Commit.** `git add airline-data && git commit -m "feat(analytics): pure airport traffic aggregation + tests"`

---

## Task 3: Demographics query in `ConsumptionHistorySource`

**Files:**
- Modify: `airline-data/src/main/scala/com/patson/data/ConsumptionHistorySource.scala`

`passenger_route_history` columns (from Meta): `passenger_count`, `home_airport`, `destination_airport`, `passenger_type` (TINYINT = `PassengerType.Value.id`). The mix for an airport = journeys originating or terminating there, grouped by type.

- [ ] **Step 1: Add the loader.**

```scala
  /** Recent passenger-type mix for journeys originating or terminating at this airport. */
  def loadAirportDemographics(airportId : Int) : Map[PassengerType.Value, Int] = {
    Using.resource(Meta.getConnection()) { connection =>
      Using.resource(connection.prepareStatement(
        "SELECT passenger_type, SUM(passenger_count) AS pax FROM " + PASSENGER_ROUTE_HISTORY_TABLE +
        " WHERE home_airport = ? OR destination_airport = ? GROUP BY passenger_type")) { statement =>
        statement.setInt(1, airportId)
        statement.setInt(2, airportId)
        Using.resource(statement.executeQuery()) { rs =>
          val result = scala.collection.mutable.Map[PassengerType.Value, Int]()
          while (rs.next()) {
            val pt = PassengerType(rs.getInt("passenger_type"))
            result.put(pt, rs.getInt("pax"))
          }
          result.toMap
        }
      }
    }
  }
```

Then add the per-partner-airport demographics loader (for per-route demographics):

```scala
  /** Per-partner-airport passenger-type mix of O-D journeys between this airport and each partner:
    * journeys terminating here grouped by origin, plus journeys originating here grouped by destination. */
  def loadAirportPartnerDemographics(airportId : Int) : Map[Int, Map[PassengerType.Value, Int]] = {
    val result = scala.collection.mutable.Map[Int, scala.collection.mutable.Map[PassengerType.Value, Int]]()
    def add(partnerId : Int, pt : PassengerType.Value, pax : Int) : Unit = {
      val m = result.getOrElseUpdate(partnerId, scala.collection.mutable.Map[PassengerType.Value, Int]())
      m.put(pt, m.getOrElse(pt, 0) + pax)
    }
    Using.resource(Meta.getConnection()) { connection =>
      Using.resource(connection.prepareStatement(
        "SELECT home_airport AS partner, passenger_type, SUM(passenger_count) AS pax FROM " + PASSENGER_ROUTE_HISTORY_TABLE +
        " WHERE destination_airport = ? GROUP BY home_airport, passenger_type")) { s =>
        s.setInt(1, airportId)
        Using.resource(s.executeQuery()) { rs => while (rs.next()) add(rs.getInt("partner"), PassengerType(rs.getInt("passenger_type")), rs.getInt("pax")) }
      }
      Using.resource(connection.prepareStatement(
        "SELECT destination_airport AS partner, passenger_type, SUM(passenger_count) AS pax FROM " + PASSENGER_ROUTE_HISTORY_TABLE +
        " WHERE home_airport = ? GROUP BY destination_airport, passenger_type")) { s =>
        s.setInt(1, airportId)
        Using.resource(s.executeQuery()) { rs => while (rs.next()) add(rs.getInt("partner"), PassengerType(rs.getInt("passenger_type")), rs.getInt("pax")) }
      }
    }
    result.view.mapValues(_.toMap).toMap
  }
```

Ensure imports cover `PassengerType` (`com.patson.model._` or add it) and `PASSENGER_ROUTE_HISTORY_TABLE` (from `Constants._`). Confirm both are already imported in the file; add if missing.

- [ ] **Step 2: Compile.** `cd airline-data && sbt compile` — Expected: success.

- [ ] **Step 3: Commit.** `git add airline-data && git commit -m "feat(analytics): airport + per-partner passenger demographics queries"`

---

## Task 4: Traffic-analytics endpoint + route

**Files:**
- Modify: `airline-web/app/controllers/Application.scala` (it is `class Application @Inject()(cc, configuration) extends AbstractController(cc)`)
- Modify: `airline-web/conf/routes`

- [ ] **Step 1: Add the endpoint method to `Application`.**

```scala
  def getAirportTrafficAnalytics(airportId : Int) = Action {
    import com.patson.data.{ConsumptionHistorySource, LinkStatisticsSource}
    import com.patson.model.AirportTrafficStats
    import com.patson.util.AirportCache

    val arrivals = LinkStatisticsSource.loadLinkStatisticsByToAirport(airportId, LinkStatisticsSource.FULL_LOAD)
    val summary = AirportTrafficStats.summary(arrivals)
    val routeRows = AirportTrafficStats.arrivalsByOrigin(arrivals).sortBy(-_.totalPax).take(50)
    val demo = ConsumptionHistorySource.loadAirportDemographics(airportId)
    val demoTotal = Math.max(1, demo.values.sum)
    val partnerDemo = ConsumptionHistorySource.loadAirportPartnerDemographics(airportId)

    def demoArray(mix : Map[com.patson.model.PassengerType.Value, Int]) : JsArray = {
      val total = Math.max(1, mix.values.sum)
      JsArray(mix.toList.sortBy(-_._2).map { case (pt, pax) => Json.obj("type" -> pt.toString, "share" -> pax.toDouble / total) })
    }

    val routesJson = routeRows.map { r =>
      val originName = AirportCache.getAirport(r.airportId, false).map(a => a.iata + " " + a.city).getOrElse(r.airportId.toString)
      Json.obj(
        "origin" -> originName,
        "totalPax" -> r.totalPax,
        "terminatingPax" -> r.terminatingPax,
        "connectingPax" -> r.connectingPax,
        "transferShare" -> r.transferShare,
        "premiumShare" -> (if (r.totalPax <= 0) 0.0 else r.premiumPax.toDouble / r.totalPax),
        "demographics" -> demoArray(partnerDemo.getOrElse(r.airportId, Map.empty)))
    }
    val demoJson = demo.toList.sortBy(-_._2).map { case (pt, pax) =>
      Json.obj("type" -> pt.toString, "pax" -> pax, "share" -> pax.toDouble / demoTotal)
    }
    Ok(Json.obj(
      "totalPax" -> summary.totalPax,
      "transferShare" -> summary.transferShare,
      "premiumShare" -> (if (summary.totalPax <= 0) 0.0 else summary.premiumPax.toDouble / summary.totalPax),
      "demographics" -> demoJson,
      "routes" -> routesJson))
  }
```

- [ ] **Step 2: Add the route** (after the `/airports/:airportId/links` line ~34 in `conf/routes`):

```
GET	 	 /airports/:airportId/traffic-analytics	controllers.Application.getAirportTrafficAnalytics(airportId : Int)
```

- [ ] **Step 3: Compile gate.** `cd airline-data && sbt publishLocal && cd ../airline-web && sbt compile` — Expected: success.

- [ ] **Step 4: Commit.** `git add airline-web && git commit -m "feat(analytics): airport traffic-analytics endpoint"`

---

## Task 5: Enrich the asset catalog JSON

**Files:**
- Modify: `airline-web/app/controllers/AirportAssetApplication.scala`

- [ ] **Step 1: Add fields to `catalogJson`** (inside the `Json.obj(...)` in `catalogJson`):

```scala
      "image" -> assetType.image,
      "benefit" -> assetType.benefit,
      "nextLevelNet" -> assetType.netWeekly(airport, nextLevel),
      "nextLevelPayback" -> assetType.paybackCycles(airport, nextLevel),
```

(`paybackCycles` returns `Option[Int]`; Play serializes it as a number or null — fine.) Optionally also add `"image" -> asset.assetType.image` to `assetJson` so owned rows show art.

- [ ] **Step 2: Compile gate.** `cd airline-data && sbt publishLocal && cd ../airline-web && sbt compile` — Expected: success. (No publishLocal needed if Task 1 already published; run it if airline-data changed since.)

- [ ] **Step 3: Commit.** `git add airline-web && git commit -m "feat(assets): expose benefit/net/payback/image in catalog JSON"`

---

## Task 6: Analytics UI (template + airport.js)

**Files:**
- Modify: `airline-web/app/views/fragments/airport_canvas.scala.html`
- Modify: `airline-web/public/javascripts/airport.js`

- [ ] **Step 1: Add the markup** — insert a new section just before the `id="airportDetailsAssetsSection"` div added previously (so analytics sits above assets):

```html
			  <div class="section mb-0 max-w-screen-nav" style="width: 100%;" id="airportTrafficAnalyticsSection">
				  <h4 class="inline-icons pt-4">Traffic Analytics</h4>
				  <div class="i-label" id="airportTrafficSummary" style="margin-bottom: 6px;"></div>
				  <div class="i-label" id="airportTrafficDemographics" style="margin-bottom: 10px;"></div>
				  <div id="airportTrafficRouteList" style="width: 100%;" class="table data">
					  <div class="table-header">
						  <div class="cell" style="width: 26%">Route (origin)</div>
						  <div class="cell" style="width: 14%; text-align: right;">Weekly Pax</div>
						  <div class="cell" style="width: 14%; text-align: right;">Transfer %</div>
						  <div class="cell" style="width: 14%; text-align: right;">Premium %</div>
						  <div class="cell" style="width: 32%">Demographics</div>
					  </div>
				  </div>
			  </div>
```

- [ ] **Step 2: Add the loader/render to `airport.js`** — call `loadAirportTrafficAnalytics(airportId)` from `showAirportDetails` right after `loadAirportAssets(airportId)`, and append:

```javascript
function loadAirportTrafficAnalytics(airportId) {
    $.ajax({
        type: 'GET',
        url: "/airports/" + airportId + "/traffic-analytics",
        dataType: 'json',
        success: function(data) { renderAirportTrafficAnalytics(data) },
        error: function() { $('#airportTrafficAnalyticsSection').hide() }
    })
}

function renderAirportTrafficAnalytics(data) {
    var pct = function(x) { return (x * 100).toFixed(0) + '%' }
    if (!data || data.totalPax === 0) {
        $('#airportTrafficSummary').text('No recent traffic data for this airport yet.')
        $('#airportTrafficDemographics').empty()
        $('#airportTrafficRouteList').children('.table-row').remove()
        return
    }
    $('#airportTrafficSummary').text('Weekly passengers: ' + data.totalPax.toLocaleString()
        + ' — ' + pct(data.transferShare) + ' transferring, ' + pct(1 - data.transferShare) + ' direct'
        + ' — ' + pct(data.premiumShare) + ' premium')
    $('#airportTrafficDemographics').text('Demographics: '
        + (data.demographics || []).map(function(d) { return d.type + ' ' + pct(d.share) }).join(', '))

    var $list = $('#airportTrafficRouteList')
    $list.children('.table-row').remove()
    $.each(data.routes, function(i, r) {
        var $row = $('<div class="table-row"></div>')
        $row.append($('<div class="cell" style="width:26%"></div>').text(r.origin))
        $row.append($('<div class="cell" style="width:14%; text-align:right;"></div>').text(r.totalPax.toLocaleString()))
        $row.append($('<div class="cell" style="width:14%; text-align:right;"></div>').text(pct(r.transferShare)))
        $row.append($('<div class="cell" style="width:14%; text-align:right;"></div>').text(pct(r.premiumShare)))
        var demoText = (r.demographics || []).slice(0, 4).map(function(d) { return d.type + ' ' + pct(d.share) }).join(', ')
        $row.append($('<div class="cell" style="width:32%"></div>').text(demoText || '—'))
        $list.append($row)
    })
}
```

- [ ] **Step 3: Compile gate.** `cd airline-web && sbt compile` — Expected: success (validates the template).

- [ ] **Step 4: Commit.** `git add airline-web && git commit -m "feat(analytics): airport traffic analytics panel UI"`

---

## Task 7: Asset images + benefit/ROI tooltips in the Assets UI

**Files:**
- Modify: `airline-web/public/javascripts/airport.js` (the `renderAirportAssets` catalog loop added previously)

- [ ] **Step 1: Show the image + a benefit/ROI tooltip in each catalog row.** In `renderAirportAssets`, replace the label cell append for the catalog with an image + tooltip. Inside the `$.each(data.catalog ...)` loop, change the first cell:

```javascript
        var payback = entry.nextLevelPayback ? (entry.nextLevelPayback + ' cycles') : 'n/a (boost only)'
        var tip = entry.benefit
            + '\nUpkeep: $' + entry.nextLevelUpkeep.toLocaleString() + '/wk'
            + (entry.generatesIncome ? ('\nIncome: $' + entry.nextLevelIncome.toLocaleString() + '/wk'
                + '\nNet: $' + entry.nextLevelNet.toLocaleString() + '/wk'
                + '\nPayback: ' + payback) : '')
        var $name = $('<div class="cell" style="width:22%"></div>')
        $('<img loading="lazy" style="height:24px; vertical-align:middle; margin-right:6px;">')
            .attr('src', '/assets/images/airport-assets/' + entry.image)
            .attr('title', tip).appendTo($name)
        $name.append($('<span></span>').attr('title', tip).text(entry.label))
        $row.append($name)
```

(Use the native `title` attribute for the tooltip — simplest and reliable; the codebase's `.tooltiptext` spans are an option but `title` avoids extra markup per row.) Owned-asset rows: optionally prepend the same `<img>` to the label cell in the `data.assets` loop using `asset.image` (added in Task 5).

- [ ] **Step 2: Compile/asset check.** `cd airline-web && sbt compile` — Expected: success. (JS isn't compiled; this just confirms nothing else broke.)

- [ ] **Step 3: Commit.** `git add airline-web && git commit -m "feat(assets): asset imagery + benefit/ROI tooltips in catalog"`

---

## Task 8: Full verification + deploy

- [ ] **Step 1: Full compile gate + tests.**

```
cd airline-data && sbt "testOnly com.patson.AirportAssetSpec com.patson.AirportTrafficStatsSpec" publishLocal
cd ../airline-web && sbt compile
```
Expected: all tests pass; both compile.

- [ ] **Step 2: Push (triggers OptiPlex deploy).** `git push origin master`

- [ ] **Step 3: Watch deploy.** `gh run watch <run-id> --repo Lash12/airline --exit-status` — Expected: success.

- [ ] **Step 4: Confirm deploy green** before UI validation (Task 9 validates the **live** OptiPlex screens, per the user — not local).

---

## Task 9: Post-deploy UI validation (Playwright + screenshot review)

Runs **after** Task 8's deploy is green, against the **live** site `https://airline.ashhome.org`. Validate
real screens with the changes in place; review the screenshots directly (Read the PNGs).

**Credentials:** game login `Lash12` / (password supplied by the user at runtime). Pass via env vars
(`AIRLINE_USER`, `AIRLINE_PASS`) — **never** commit them to a file, script, or memory.

- [ ] **Step 1: Write a throwaway Playwright script** under `e2e/` (gitignored or deleted after) that:
  logs in via the game's login form, opens a **busy** airport's detail panel (e.g. the Lash Air HQ
  or a major hub), scrolls to the **Traffic Analytics** section and screenshots it, then scrolls to
  the **Airport Assets** section, hovers a catalog row to surface the benefit/ROI tooltip, and
  screenshots it (and the asset art). Save PNGs to `e2e/screenshots/`.

```javascript
// e2e/asset-ui-validate.spec.js  (throwaway; delete after review)
const { test, expect } = require('@playwright/test');
test('asset decision support UI', async ({ page }) => {
  await page.goto('https://airline.ashhome.org');
  // log in (selectors to confirm against the live login form)
  await page.fill('#username, input[name="userName"]', process.env.AIRLINE_USER);
  await page.fill('#password, input[name="password"]', process.env.AIRLINE_PASS);
  await page.click('button:has-text("Login"), #loginButton');
  await page.waitForLoadState('networkidle');
  // open an airport detail (navigate via search or map; confirm flow live)
  // ... screenshot #airportTrafficAnalyticsSection and #airportDetailsAssetsSection
  await page.screenshot({ path: 'e2e/screenshots/analytics.png', fullPage: false });
});
```

(The exact login selectors + airport-navigation flow must be confirmed against the live DOM — adapt
during the run. If Cloudflare Access fronts the site with an SSO wall that blocks automated login,
fall back to the deploy workflow's verification approach or ask the user to confirm access.)

- [ ] **Step 2: Run it.** `cd e2e && AIRLINE_USER=Lash12 AIRLINE_PASS=*** npx playwright test asset-ui-validate.spec.js` (password from the user, not echoed/committed).

- [ ] **Step 3: Review screenshots yourself.** Read `e2e/screenshots/*.png` and verify: analytics summary + demographics + per-route table render with real numbers; asset rows show art + working benefit/ROI tooltips; layout isn't broken on the airport panel. Note any visual issues and fix forward.

- [ ] **Step 4: Clean up.** Delete the throwaway spec + screenshots (do not commit credentials or large PNGs).

---

## Out of scope (follow-ons)
- Exact **per-leg** demographics (attributing connecting pax to the specific arriving `link` via a
  `passenger_link_history` → `link` join); we use the robust per-partner O-D grouping instead.
- Historical trend charts; caching the analytics endpoint (add to `ResponseCache` if it proves heavy).
- Using the remaining vendored asset images (we only reference 6 until the catalog grows).
