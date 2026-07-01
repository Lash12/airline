# Roadmap Current-State Audit

_Audit date: 2026-07-01_

## Compile status

Both modules compile cleanly from the `master` HEAD with no errors:

```
airline-data  publishLocal  — SUCCESS (27 s, 82 deprecation warnings, non-blocking)
airline-web   compile       — SUCCESS (37 s, 8 deprecation warnings, non-blocking)
```

Java 17 / sbt from standard PATH. `airline-data publishLocal` must be run before
`airline-web compile` when data APIs change.

## Tests run

| Suite | Result | Notes |
|-------|--------|-------|
| `CargoDemandGeneratorSpec` | 11/11 PASS | DB-free, runs locally |
| `CargoAllocationSpec`, `CargoMarketVisibilityServiceSpec`, `ConsultantAdvisorSpec`, `RouteForecastLogicSpec` | 40/40 PASS | DB-free targeted suite for cargo/advisor/forecast helper logic |
| `airline-web` Jest | 37/37 PASS | Route forecast, cargo formatting, advisor/cargo overview rendering, and existing JS tests |
| `RouteForecastServiceSpec` | SKIPPED locally | Requires live MySQL; local `localhost:3306` refused connection |
| `airline-web sbt test` | BLOCKED locally | Play/SbtWeb asset pipeline could not detect npm and fell back to old WebJars npm |
| `e2e npm test` | BLOCKED locally | Browser launches with elevation, but no app is listening on `localhost:9000` |

## Feature inventory

### Shipped and fully wired (backend + frontend + deployed)

| Feature | Flag | Notes |
|---------|------|-------|
| Web push notifications | always-on | Validated live, Firefox Android |
| World News redesign | always-on | Fan-out replaced with broadcast table |
| AI growth H-1..H-5 | `solo.ai.bases.enabled` | All NPC growth phases done |
| Airport Assets | `solo.airportAssets.enabled` | Build/upgrade/sell; self-creates table |
| Asset Decision Support | `solo.airportAssets.enabled` | ROI tooltips + asset modal |
| Traffic Analytics | always-on | Per-route demographics, no flag |
| Air Cargo C-1..C-4 | `solo.cargo.enabled` | Belly + freighters + Cargo Terminal + ledger |
| Airport Cargo Demand panel | `solo.cargo.enabled` | `GET /airports/:id/cargo-demand`, rendered in `airport.js` |
| Airport Cargo Opportunities panel | `solo.cargo.enabled` | `GET /airports/:id/cargo-opportunities`, sorted/scored with yield/profit/aircraft/reason/risk details |
| Network Cargo Market Overview | `solo.cargo.enabled` | `GET /airlines/:id/cargo-market-overview`, rendered on Office page |
| Route Forecast | `solo.routeForecast.enabled` | Backend + frontend + flag enabled; includes recommendation, competition summary, confidence explanation, cargo share, aircraft reason |
| Freighter-only cargo multiplier | `solo.cargo.freighterRevenueMultiplier=10.0` | Applies only to cargo flight links, not passenger belly cargo |
| Advisor recommendations | existing consultant/executive levels | `GET /airlines/:id/advisor/recommendations`, rendered in Office consultant panel |
| Cargo revenue rate balance fix | `solo.cargo.revenuePerUnitKm=0.01` | Raised 50× from 0.0002; see `docs/balance-review-2026-06.md` |
| Executive Team phases 0-2 | (existing flag) | C-suite buffs + leveling |
| DB pool hardening | always-on | `hikari.maxPoolSize 16`; nested-connection fix in Airline/Alliance/Link/Asset sources |
| DB schema migrations | always-on | `SchemaPatchRunner` auto-runs at startup |

### Route Forecast — fully shipped and enabled

`GET /airlines/:id/route-forecast?originAirportId=X&destinationAirportId=Y`

- Route registered in `conf/routes` ✓
- `LinkApplication.getRouteForecast` implemented ✓
- `RouteForecastService.getForecast` implemented ✓
- `SoloConfig.routeForecastEnabled` flag exists, defaults `false` ✓
- `fetchAndShowRouteForecast` in `airline.js:1485`, called from `planLink` at line 939 ✓
- **`-Dsolo.routeForecast.enabled=true` in both `SIM_SOLO_OPTS` and `WEB_SOLO_OPTS`** (added 2026-06-27) ✓
- E2E spec: `e2e/tests/route-forecast.spec.ts` ✓
- `RouteForecastServiceSpec` in CI (`ci.yml`) ✓

Returns structured JSON: `passengerDemandEstimate`, `cargoDemandEstimate`, `expectedRevenue`,
`expectedCost`, `expectedProfit`, `confidenceLevel`, `competitionLevel`,
`recommendedAircraftModels`, `recommendedFrequency`, `reasons`, `competitorCount`,
`competitorTotalFrequency`, `competitionSummary`, `confidenceExplanation`, `recommendation`,
`recommendationSeverity`, `cargoShareEstimate`, and `aircraftRecommendationReason`, plus a
`compatible`/`blockingReason` block from the route-rejection check.

HTTP statuses:
- `403 FEATURE_DISABLED:…` if flag off
- `404 UNAVAILABLE_DATA:…` if airports missing
- `400` for other errors
- `200 Ok` with JSON on success

### Airport Cargo Opportunities — fully shipped

`GET /airports/:id/cargo-opportunities`

- Route registered in `conf/routes` ✓
- `Application.getAirportCargoOpportunities` implemented ✓
- `CargoMarketVisibilityService.getCargoOpportunities` implemented ✓ (returns model names, yield/profit bands, best aircraft/freighter candidates, reason/risk text, and score)
- `ResponseCache.cargoOpportunitiesCache` wired ✓
- `loadAirportCargoOpportunities` in `airport.js:1671`, called from `populateAirportDetails` at line 996 ✓
- `renderCargoOpportunities` in `airport.js:1690` (cards with demand/yield/profit/aircraft/reason/risk/"Plan cargo route" button) ✓
- `#airportCargoOpportunitiesSection` placeholder in `airport_canvas.scala.html:407` ✓
- E2E spec: `e2e/tests/cargo-opportunities.spec.ts` includes visible field checks and plan-route prefill coverage ✓

### Network Cargo Market Overview — shipped

`GET /airlines/:id/cargo-market-overview`

- Route registered in `conf/routes` ✓
- `Application.getCargoMarketOverview` implemented ✓
- `CargoMarketVisibilityService.getCargoMarketOverview` implemented ✓
- `ResponseCache.cargoMarketOverviewCache` wired ✓
- Office page renders the top network-wide lanes with demand, yield, profit, aircraft, served status, and reason text ✓

### Advisor Recommendations — shipped

`GET /airlines/:id/advisor/recommendations`

- Reuses existing consultant/executive levels; no progression schema or milestone changes ✓
- Tier helpers live in `ConsultantAdvisor` and are covered by `ConsultantAdvisorSpec` ✓
- Backend recommendations are generated from real airline state: idle aircraft, losing routes, cargo opportunities, and airport assets ✓
- Office consultant panel groups recommendations by Fleet, Routes, Cargo, and Airport assets ✓

## What is stale / abandoned

| Doc section | Status |
|-------------|--------|
| `single-player-performance-roadmap.md` Phase 3 & 4 | Already shipped — docs noted as stale in `current-development-state.md` |
| Idle-pause / pause-when-idle | **Abandoned — do not implement** |
| Old per-airline WORLD_NEWS rows in `notification` table | Orphaned (~10 k rows); safe to bulk-delete but needs explicit go-ahead |

## Known gaps / follow-up items (lower priority)

1. ~~**`cargo-opportunities` frontend**~~ — **DONE 2026-06-27.** Fully wired.
2. ~~**Route forecast enable in deploy**~~ — **DONE 2026-06-27.** Flag enabled in both SIM + WEB opts.
3. ~~**Passenger demand ETag bug**~~ — **FIXED 2026-06-27.** `_demandEtagAirportId` added to `airport.js`.
4. ~~**Nested-connection spots not yet fixed**~~ — **FIXED 2026-06-27.** Four methods restructured.
5. **`topCargoDestinations` perf:** Recomputes per-pair on cache miss instead of reusing the per-cycle memo matrix — bounded by `ResponseCache` so low priority.
6. **`RouteForecastServiceSpec`:** Requires live MySQL; cannot run locally on this Windows host.
7. ~~**Freighter viability multiplier**~~ — **DONE 2026-07.** `solo.cargo.freighterRevenueMultiplier` defaults to `10.0` and is cargo-link only.

## Recommended next work

See `docs/next-development-priorities.md`. Short list: balance telemetry after the cargo/freighter
changes, runtime E2E verification in CI or a safe dev app, cargo contracts design, and future
disruption/event implementation from `docs/disruption-event-recovery-design.md`.
