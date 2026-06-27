# Next Development Priorities

Last updated: 2026-06-27

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

### 2. Cargo opportunities frontend panel (low effort, backend already done)
- Backend: `GET /airports/:id/cargo-opportunities` — fully implemented (`CargoMarketVisibilityService`),
  deployed, zero frontend wiring.
- Add `loadAirportCargoOpportunities()` call in `airport.js` (model after `loadAirportCargoDemand`).
- Add `#airportCargoOpportunitiesSection` placeholder in
  `airline-web/app/views/fragments/airport_canvas.scala.html`.
- The endpoint returns top-50 opportunities with served/unserved demand, yield estimate,
  and recommended aircraft IDs.

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

## Useful references for agents starting a new session
- Compile: `cd airline-data && sbt publishLocal`, then `cd airline-web && sbt compile`
- DB-free tests: `sbt "testOnly com.patson.CargoDemandGeneratorSpec"` (11 tests, no DB needed)
- DB tests: `sbt "testOnly com.patson.RouteForecastServiceSpec"` — needs CI MySQL; runs in
  GitHub Actions (added to `ci.yml` 2026-06-27)
- Deploy: push to `master` → `OptiPlex Deploy & Verify` workflow triggers automatically
- Live DB check: `ssh airline-dev "docker exec airline-db mysql ..."`
- SSH key: `~/.ssh/airline_optiplex_ed25519`, host `root@192.168.1.52`
