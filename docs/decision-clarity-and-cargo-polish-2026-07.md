# Decision Clarity and Cargo Polish - 2026-07

Status: implemented in code, pending deploy verification after push.

This pass improves the single-player decision surfaces without adding reactive AI, disruptions, progression changes, pause-when-idle behavior, cargo contracts, or destructive data operations.

## What Changed

### Route Forecast

- `GET /airlines/:id/route-forecast` now returns actionable recommendation fields:
  `recommendation`, `recommendationSeverity`, `competitorCount`, `competitorTotalFrequency`,
  `competitionSummary`, `confidenceExplanation`, `cargoShareEstimate`, and
  `aircraftRecommendationReason`.
- Forecast competition now excludes the player's own routes and reports direct competitor count
  plus total direct frequency.
- Recommendation logic is deterministic and bounded: blocked routes return `BLOCKED`; profitable
  high-confidence routes return `OPEN`; thin, crowded, or uncertain routes return cautious or wait/avoid labels.
- Aircraft suggestions prefer range/runway-compatible aircraft sized to demand, with reason text for
  thin or cargo-heavy lanes.
- The route planner card shows recommendation, expected profit, passenger/cargo demand, cargo share,
  competition, confidence, recommended aircraft/frequency, and blocking reasons.

### Cargo Opportunities

- Airport cargo opportunity cards now include readable yield, estimated profit or profit band,
  best belly-cargo aircraft, best freighter candidate, reason text, and risk text.
- Server-side opportunity sorting now uses a blended score based on estimated net-like profit/yield,
  unserved demand, aircraft suitability, and distance practicality.
- `GET /airlines/:id/cargo-market-overview` returns top network-wide cargo lanes with origin,
  destination, demand, yield, profit, recommendations, served-by-player status, and reason text.
- The Office page now includes a compact Cargo Market Overview section.
- The Plan cargo route button continues to prefill origin and destination, and now carries the
  recommended aircraft model when available.

### Freighter Viability

- Added `solo.cargo.freighterRevenueMultiplier`, default `10.0`.
- The multiplier is applied only to cargo carried on cargo flight links (`TransportType.CARGO_FLIGHT`).
- Belly cargo on passenger links remains priced at the normal `solo.cargo.revenuePerUnitKm`.
- Mixed allocation preserves that split: belly cargo is unchanged, freighter cargo receives the multiplier.
- Link revenue UI now explains when freighter cargo revenue includes the freighter-only multiplier.

### Advisor Recommendations

- Added `GET /airlines/:id/advisor/recommendations`.
- Recommendations use the existing consultant/executive level model instead of creating a second
  progression system. Existing levels 0 through 4 map to advisor tiers 1 through 5.
- The backend generates state-based recommendations for:
  - idle aircraft and route planning,
  - losing routes,
  - cargo opportunities,
  - airport asset build candidates.
- Higher tiers unlock more specific summaries, numeric detail, risk text, and safe navigation actions.
- The Office consultant panel now renders grouped advisor recommendations for Fleet, Routes, Cargo,
  and Airport assets.

## Flags And Config

- New config: `solo.cargo.freighterRevenueMultiplier = 10.0`.
- Existing cargo flag remains `solo.cargo.enabled`.
- Existing route forecast flag remains `solo.routeForecast.enabled`.
- No deploy flag change is required for the new multiplier unless the deploy wants a non-default value.

## Schema And Migration Impact

- No schema changes.
- No migration added.
- No persisted enums changed.
- No destructive database action is required.

## Tests Added Or Updated

- Scala:
  - `CargoAllocationSpec` covers belly unchanged, freighter multiplier, and mixed allocation.
  - `CargoMarketVisibilityServiceSpec` covers cargo opportunity scoring and profit bands.
  - `ConsultantAdvisorSpec` covers advisor tier/proficiency gates.
  - `RouteForecastLogicSpec` covers recommendation labels, competition summaries, confidence explanations, and cargo share.
  - `RouteForecastServiceSpec` was updated for the expanded response fields; it still requires a live MySQL database.
- JavaScript:
  - Route forecast rendering tests cover the new recommendation, competition, confidence, cargo-share, and aircraft-reason fields.
  - Cargo yield formatting tests cover the readable per-unit-per-km label.
  - Office advisor tests cover grouped recommendations and cargo market overview rendering.
- Playwright:
  - Route forecast card assertions include the new summary fields.
  - Cargo opportunities assertions include yield, profit, reason, risk, and plan-route prefill.
  - Cargo market overview and advisor panel coverage were added, including mobile-width smoke coverage.

## Validation Notes

Commands that passed locally:

```powershell
cd airline-data
sbt publishLocal
sbt "testOnly com.patson.CargoAllocationSpec com.patson.CargoMarketVisibilityServiceSpec com.patson.ConsultantAdvisorSpec com.patson.RouteForecastLogicSpec"

cd ../airline-web
sbt compile
npm test -- --runInBand
```

Commands blocked locally:

```powershell
cd airline-data
sbt "testOnly com.patson.RouteForecastServiceSpec"
```

Blocked by local MySQL refusal on `localhost:3306`; this DB-backed suite should run in CI where MySQL is available.

```powershell
cd airline-web
sbt test
```

Blocked before Scala tests by the local Play/SbtWeb asset pipeline failing to detect system npm and falling back to an old WebJars npm (`cb.apply is not a function`). `sbt compile` and the direct Jest suite passed.

```powershell
cd e2e
npm test
```

The sandboxed run could not launch Chromium (`spawn EPERM`). The elevated run launched Chromium but failed on `http://localhost:9000` connection refused because no local Play app was running. Starting the documented small-server compose profile was intentionally avoided because it includes `-Dsimulation.pauseWhenIdle=true`, which this task explicitly forbids enabling.

## Known Follow-Ups

- Run the full Playwright suite in CI or against a safe local/dev app that does not enable pause-when-idle.
- Observe cargo share and freighter route economics after deploy; tune `solo.cargo.freighterRevenueMultiplier` only if freighters remain obviously weak or become dominant.
- Cargo contracts remain future work and need a separate design pass.
- Disruption/event recovery remains design-only; see `docs/disruption-event-recovery-design.md`.
