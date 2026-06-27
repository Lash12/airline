# Roadmap Current-State Audit

_Audit date: 2026-06-27_

## Compile status

Both modules compile cleanly from the `master` HEAD with no errors:

```
airline-data  publishLocal  — SUCCESS (27 s, 82 deprecation warnings, non-blocking)
airline-web   compile       — SUCCESS (37 s, 8 deprecation warnings, non-blocking)
```

Java 17 / sbt from standard PATH. No manual JAVA_HOME injection needed on this checkout.

## Tests run

| Suite | Result | Notes |
|-------|--------|-------|
| `CargoDemandGeneratorSpec` | 11/11 PASS | DB-free, runs locally |
| `RouteForecastServiceSpec` | SKIPPED (cannot run) | Requires live MySQL; `HikariPool$PoolInitializationException` on local run — expected |

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
| Airport Cargo Opportunities panel | `solo.cargo.enabled` | `GET /airports/:id/cargo-opportunities`, fully wired JS + HTML + e2e (2026-06-27) |
| Route Forecast | `solo.routeForecast.enabled` | Backend + frontend + flag enabled in deploy + e2e (2026-06-27) |
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

Returns structured JSON: `passengerDemandEstimate`, `cargoDemandEstimate`, `expectedRevenue`, `expectedCost`, `expectedProfit`, `confidenceLevel`, `competitionLevel`, `recommendedAircraftModels`, `recommendedFrequency`, `reasons`, plus a `compatible`/`blockingReason` block from the route-rejection check.

HTTP statuses:
- `403 FEATURE_DISABLED:…` if flag off
- `404 UNAVAILABLE_DATA:…` if airports missing
- `400` for other errors
- `200 Ok` with JSON on success

### Airport Cargo Opportunities — fully shipped

`GET /airports/:id/cargo-opportunities`

- Route registered in `conf/routes` ✓
- `Application.getAirportCargoOpportunities` implemented ✓
- `CargoMarketVisibilityService.getCargoOpportunities` implemented ✓ (returns model names via `AirplaneModelCache`)
- `ResponseCache.cargoOpportunitiesCache` wired ✓
- `loadAirportCargoOpportunities` in `airport.js:1671`, called from `populateAirportDetails` at line 996 ✓
- `renderCargoOpportunities` in `airport.js:1690` (cards with demand/yield/aircraft/notes/"Plan cargo route" button) ✓
- `#airportCargoOpportunitiesSection` placeholder in `airport_canvas.scala.html:407` ✓
- E2E spec: `e2e/tests/cargo-opportunities.spec.ts` (4 tests, uses `page.evaluate` to bypass ancestor visibility) ✓

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
6. **`RouteForecastServiceSpec`:** Requires live MySQL; cannot run locally. DB-free extraction is optional polish.
7. **Cargo revenue rate** — raised from `0.0002` → `0.01` (50×) on 2026-06-27 per `docs/balance-review-2026-06.md`. Freighter viability still needs a separate multiplier (tracked as R2 in that doc, deferred).

## Recommended next work

See `docs/next-development-priorities.md`. Short list: cargo opportunities UX polish, route
forecast quality improvements, consultant/advisor polish, balance telemetry after the cargo
rate change, and E2E hardening. Cargo contracts need a design doc before implementation.
