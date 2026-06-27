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
| Executive Team phases 0-2 | (existing flag) | C-suite buffs + leveling |
| DB pool hardening | always-on | `hikari.maxPoolSize 16`; nested-connection fix in Airline/Alliance sources |
| DB schema migrations | always-on | `SchemaPatchRunner` auto-runs at startup |
| Route Forecast | `solo.routeForecast.enabled` | **See section below** |

### Route Forecast — backend complete, flag disabled by default

`GET /airlines/:id/route-forecast?originAirportId=X&destinationAirportId=Y`

- Route registered in `conf/routes` ✓
- `LinkApplication.getRouteForecast` implemented ✓
- `RouteForecastService.getForecast` implemented ✓
- `SoloConfig.routeForecastEnabled` flag exists, defaults `false` ✓
- Frontend call exists in `airline.js` (route planner panel) — needs confirmation
- **Not yet enabled in `optiplex-deploy.yml`** — add `-Dsolo.routeForecast.enabled=true` to both `SIM_SOLO_OPTS` and `WEB_SOLO_OPTS` when ready to ship

Returns structured JSON: `passengerDemandEstimate`, `cargoDemandEstimate`, `expectedRevenue`, `expectedCost`, `expectedProfit`, `confidenceLevel`, `competitionLevel`, `recommendedAircraftModels`, `recommendedFrequency`, `reasons`, plus a `compatible`/`blockingReason` block from the route-rejection check.

HTTP statuses:
- `403 FEATURE_DISABLED:…` if flag off
- `404 UNAVAILABLE_DATA:…` if airports missing
- `400` for other errors
- `200 Ok` with JSON on success

### Airport Cargo Opportunities — backend complete, NO frontend wiring

`GET /airports/:id/cargo-opportunities`

- Route registered in `conf/routes` ✓
- `Application.getAirportCargoOpportunities` implemented ✓  
- `CargoMarketVisibilityService.getCargoOpportunities` implemented ✓
- `ResponseCache.cargoOpportunitiesCache` wired ✓
- **No call in `airport.js`** — endpoint returns data but nothing renders it
- No section placeholder in `airport_canvas.scala.html` for opportunities (the demand section `#airportCargoDemandSection` exists and works; opportunities needs its own section)

This is a deliberate gap: the demand panel (C-1..C-4 scope) shipped; opportunities panel was backend-built but deferred.

## What is stale / abandoned

| Doc section | Status |
|-------------|--------|
| `single-player-performance-roadmap.md` Phase 3 & 4 | Already shipped — docs noted as stale in `current-development-state.md` |
| Idle-pause / pause-when-idle | **Abandoned — do not implement** |
| Old per-airline WORLD_NEWS rows in `notification` table | Orphaned (~10 k rows); safe to bulk-delete but needs explicit go-ahead |

## Known gaps / follow-up items (lower priority)

1. **`cargo-opportunities` frontend:** Add `loadAirportCargoOpportunities` call + `#airportCargoOpportunitiesSection` placeholder in `airport_canvas.scala.html` when ready to surface the panel.
2. **Route forecast enable in deploy:** Add `-Dsolo.routeForecast.enabled=true` to `optiplex-deploy.yml` when ready.
3. **Passenger demand ETag bug:** `_demandEtag` in `airport.js` is cycle-keyed only (no airport-id component) — switching airports in one cycle can 304 to stale passenger demand cards. Fix: key by `${airportId}_${cycle}` like `_cargoDemandEtag`.
4. **Nested-connection spots not yet fixed:** `LinkSource.loadLinksByQueryString`, `AirlineSource.loadAirlineBasesByQueryString`, `AirportAssetSource.loadByCriteria` — apply "release before cache resolve" pattern if pool pressure recurs.
5. **`topCargoDestinations` perf:** Recomputes per-pair on cache miss instead of reusing the per-cycle memo matrix — bounded by `ResponseCache` so low priority.
6. **`RouteForecastServiceSpec`:** Requires live MySQL to run; cannot be exercised locally. Consider extracting a DB-free unit test for the pure computation path (similar to `CargoDemandGeneratorSpec`).

## Recommended next prompt

Enable route forecast in the deploy config and verify end-to-end in the route planner, OR build the airport cargo opportunities frontend panel. Both are self-contained.
