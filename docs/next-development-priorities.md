# Next Development Priorities

Last updated: 2026-06-27 (reconciled with code 2026-06-27)

Quick reference for future agents. Read `docs/current-development-state.md` and
`docs/roadmap-current-state-audit.md` for fuller context.

## Do Not Build
- **Pause-when-idle / skip-cycle-when-idle**: abandoned by product decision. Never enable
  `simulation.pauseWhenIdle`. See `docs/single-player-performance-roadmap.md`.

---

## Ordered Priority List

### 1. Route forecast UI wiring — DONE 2026-06-27

- Backend: `GET /airlines/:id/route-forecast` — fully implemented.
- Frontend: `fetchAndShowRouteForecast` in `airline.js:1485`, called from `planLink` at line 939.
  `showRouteForecast` renders to `#routeForecastContainer` and all child IDs.
- HTML: `world_map.scala.html` has all required DOM IDs.
- Tests: JS unit tests at `airline-web/test/javascript/route-planner.test.js`; e2e spec at
  `e2e/tests/route-forecast.spec.ts`.
- Flag: `-Dsolo.routeForecast.enabled=true` added to both `SIM_SOLO_OPTS` and `WEB_SOLO_OPTS`
  in `.github/workflows/optiplex-deploy.yml` (2026-06-27).

### 2. Cargo opportunities frontend panel — DONE 2026-06-27

- Backend: `GET /airports/:id/cargo-opportunities` — fully implemented.
- Frontend: `loadAirportCargoOpportunities` in `airport.js:1671`, called from `populateAirportDetails`.
  `renderCargoOpportunities` renders cards with demand/yield/aircraft names/notes and "Plan cargo route" button.
- HTML: `#airportCargoOpportunitiesSection` in `airport_canvas.scala.html:407`.
- E2E: `e2e/tests/cargo-opportunities.spec.ts` (4 tests).
- Model names enriched server-side via `AirplaneModelCache`.

### 3. DB pool and ETag follow-ups — FIXED 2026-06-27

- **Passenger demand ETag bug** — FIXED. `_demandEtagAirportId` added to `airport.js`;
  `If-None-Match` now gated on same airportId, matching the cargo demand pattern.
- **Nested-connection spots** — FIXED. Four methods restructured to read all raw rows first,
  release the connection, then do cache enrichment:
  - `LinkSource.loadLinksByQueryString` (+ `loadAssignedAirplanesByLinks` now opens own connection)
  - `LinkSource.loadLinkConsumptionsByQuery` (highest risk: was resolving caches inside active cursor)
  - `AirportAssetSource.loadByCriteria`
  - `AirlineSource.loadAirlineBasesByQueryString`
- Both modules compiled clean; 11/11 DB-free tests pass.

### 4. Objectives / progression milestones
- `solo-roadmap-implementation-map.md` objective 4 (progression MVP extensions).
- Additive on a stable, shipped feature; low risk.

### 5. Cargo demand tuning
- `CARGO_BASE_DIVISOR` and `solo.cargo.demandAmplitude` were set conservatively.
- Once the route forecast UI is live, read the forecast's `cargoDemandEstimate` for
  major real-world pairs (JFK→LHR, LAX→NRT) and tune so totals are gameplay-plausible.

### 6. AI/world-news visibility and balance
- Confirm `world_news` table is receiving events (check `SELECT * FROM world_news ORDER BY id DESC LIMIT 5`).
- Tune `solo.ai.*` base-expansion knobs and asset/upkeep multipliers after playtesting cadence.

### 7. Route planner / forecast UI overhaul (larger effort)
- `solo-roadmap-implementation-map.md` objective 3.
- Larger JS refactor; do after CI hardening and route forecast wiring are confirmed stable.

### 8. Cargo contracts / SLA economy (later)
- `solo-roadmap-implementation-map.md` objective 7.
- Needs its own design note. Not ready to implement.

### 9. Disruption / event recovery (later, highest uncertainty)
- `solo-roadmap-implementation-map.md` objective 8.
- Needs investigation spike and design doc before any implementation.

---

## Next Real Work (as of 2026-06-27)

Items 1-3 are finished. The next meaningful work, roughly ordered:

### A. Cargo opportunities UX polish
- Current panel renders cards but the "Plan cargo route" button links to `planLink(airportId, opp.destinationAirportId)` without pre-populating aircraft or frequency.
- Improvements: sort by unserved demand (largest first), add a yield-per-unit label that converts `estimatedYield` to `$/t/km` in readable form, make the plan-route deeplink open the route planner with origin+destination pre-filled.
- Low risk, JS-only.

### B. Route forecast quality improvements
- Forecast renders but competitionLevel is HIGH/MEDIUM/LOW with no explanation of how many competitors or at what frequency.
- Add: competitor count + total competitor frequency in the reasons list; confidence calibration (flag when demand estimate is based on thin data); better aircraft suggestion (prefer lower seat-count when demand is low).
- Moderate effort, mostly in `RouteForecastService.scala`.

### C. Consultant/advisor polish
- Existing advisor suggests routes; add actionable recommendations tied to real player state (e.g., "You have 3 idle aircraft — open these routes").
- Needs `ExecutiveTeam` / consultant integration review before implementation.

### D. Balance telemetry
- After `cargoRevenuePerUnitKm=0.01` deploys, validate belly cargo is 2-8% of airline revenue in practice.
- Check: log or query `link_consumption.cargo_revenue` for real routes; tune `solo.cargo.demandAmplitude` if demand on mid-tier routes is thin.
- See playtest checklist in `docs/balance-review-2026-06.md`.

### E. E2E / CI hardening
- `cargo-opportunities.spec.ts` uses `page.evaluate` to bypass ancestor visibility — fragile to JS API renames.
- Add: a full-flow test that opens an airport panel, waits for the opportunities section to render, and checks real data shape.
- Also: route-forecast e2e currently only verifies the API shape; add a UI-level test that opens the route planner and checks the forecast card renders.

### F. Cargo contracts (design first)
- Not ready to implement. Needs a design doc covering: contract types, SLA obligations, penalty model, UI surface. Write the doc before any code.

---

## Useful references for agents starting a new session
- Compile: `cd airline-data && sbt publishLocal`, then `cd airline-web && sbt compile`
- DB-free tests: `sbt "testOnly com.patson.CargoDemandGeneratorSpec"` (11 tests, no DB needed)
- DB tests: `sbt "testOnly com.patson.RouteForecastServiceSpec"` — needs CI MySQL; runs in
  GitHub Actions (added to `ci.yml` 2026-06-27)
- Deploy: push to `master` → `OptiPlex Deploy & Verify` workflow triggers automatically
- Live DB check: `ssh airline-dev "docker exec airline-db mysql ..."`
- SSH key: `~/.ssh/airline_optiplex_ed25519`, host `root@192.168.1.52`
