# Solo Roadmap — Implementation Map

Status: **planning document only — no behavior changes**. Captured 2026-06-21 to give the
next 9 roadmap objectives a concrete, file-accurate landing spot before any code is written.
Every objective below was checked against the current `master` tree (not assumed from
upstream `patsonluk/airline` or prior docs). Several objectives turned out to be partially
or fully built already — that is called out explicitly so the next session doesn't redo work.

Conventions used throughout (already established by every shipped solo phase — see
`docs/current-development-state.md`):
- New behavior is gated by a `solo.*` (or `simulation.*`) flag in
  `airline-data/src/main/scala/com/patson/data/SoloConfig.scala`, default = current behavior,
  so default/multiplayer deploys stay byte-identical.
- A new DB table is never assumed to exist — `Meta.createSchema()` only runs on a *fresh*
  init. Any new table must self-create via the `ensureTable()` pattern (idempotent
  `CREATE TABLE IF NOT EXISTS`, see `HeartbeatSource.scala`, `PushSubscriptionSource.scala`,
  `AirportAssetSource.scala`). A new column needs an `information_schema`-guarded
  `ALTER TABLE` (MySQL 8 has no `ADD COLUMN IF NOT EXISTS`).
- Enum-like persisted types (`LedgerType` in `model/Airline.scala`, `NotificationCategory`)
  are **append-only** — ordinals are persisted in the DB.
- Flags are wired into both `SIM_SOLO_OPTS` and `WEB_SOLO_OPTS` (or `SIM_EXTRA_OPTS`) in
  `.github/workflows/optiplex-deploy.yml`.

---

## 1. Migration / database deploy hardening

**Status: shipped 2026-06-21.** `SchemaPatchRunner` + `SchemaMigrations` + `schema_patch`
tracking table implemented per the approach below (internal runner, not Flyway). Called from
`MainSimulation`, the web `Module`, and `MainInit`. See `docs/database-migrations.md` for the
developer-facing guide and `SchemaPatchRunnerSpec` for the CI smoke test. The `ensureTable()`
pattern and `Meta.createSchema()` semantics are unchanged. The boilerplate-refactor idea in step
1 below (a shared `SchemaEnsurer` helper for the 5 existing `ensureTable()` sources) was **not**
done — out of scope, left for a future pure-refactor PR if wanted.

### Current relevant code
- `airline-data/src/main/scala/com/patson/data/Meta.scala` — `createSchema()` does a full
  `DROP TABLE IF EXISTS` + recreate of every table; only invoked by
  `com.patson.init.MainInit` (fresh-DB bootstrap, also what CI runs in `ci.yml`). It is
  **never** run against the live OptiPlex DB.
- There is **no migration framework** (no Flyway/Liquibase; confirmed — no `flyway` hits
  anywhere in the repo). Schema evolution on the live DB happens entirely through the
  ad-hoc `ensureTable()` pattern used by 5 sources today: `HeartbeatSource.scala`,
  `PushSubscriptionSource.scala`, `AirportAssetSource.scala`, `WorldNewsSource.scala`,
  `SimControlSource.scala`. Each duplicates the same "ensure, once, with a
  `@volatile` flag + `synchronized` double-check" boilerplate.
- One ad-hoc column migration exists as a standalone, **not auto-applied** SQL file:
  `airline-data/db_scripts/patch_link_consumption_airport_pair_index.sql`. Nothing runs it;
  it's a copy-paste-into-mysql instruction, found and applied manually per the Step E memory.
- Deploy mechanics: `.github/workflows/optiplex-deploy.yml` (push-to-`master` auto-deploy via
  `scripts/optiplex-deploy.sh`), `docker-compose.yaml` / `docker-compose.small.yaml`,
  `.docker/db` (Bitnami MySQL image, persistent volume at `/bitnami/mysql` — **do not** touch
  this volume per `docs/current-development-state.md` guardrails).
- Known incident this hardening would have caught: the Airport Assets table-missing crash on
  first deploy (`Table 'airline.airport_asset' doesn't exist`) — root-caused and fixed
  reactively by adding `ensureTable()`, not caught pre-deploy.

### Proposed implementation approach
Do **not** introduce Flyway/Liquibase — too heavy for a single self-hosted MySQL instance
and a large blast-radius change for a fork that prizes small gated PRs. Instead, formalize
the pattern that already works:
1. Extract a shared helper, e.g. `airline-data/.../data/SchemaEnsurer.scala`, with a generic
   `ensureOnce(tableEnsuredFlag, ddl: String*)` so each `ensureTable()` becomes a 2-line call
   instead of duplicated boilerplate (pure refactor, behavior-preserving — verify with
   existing specs for the 5 affected sources).
2. Add a **startup self-check** logged once per process boot (sim and web both run `Meta`):
   compare a small in-code manifest of `(table, expected-columns)` against
   `information_schema.columns` for the connected DB and log a single WARN summary line per
   missing table/column — observability only, never auto-DDL outside the existing
   `ensureTable()` call sites (avoids surprising the live DB).
3. Promote `airline-data/db_scripts/*.sql` from "manual copy-paste" to "applied automatically,
   once, tracked": add a tiny `schema_patch` tracking table (itself `ensureTable()`-created)
   that records applied patch filenames, and a `SchemaPatchRunner` that walks
   `db_scripts/*.sql` in filename order at sim startup and applies any not yet recorded. This
   gives the existing ad-hoc SQL files a real (if minimal) migration runner without adopting
   a new framework dependency.
4. Document the whole pattern once in a new `docs/database-migrations.md` (or fold into
   `HIKARI_TUNING.md`'s sibling doc set) so future features don't reinvent it.

### Data model changes
A new `schema_patch(filename VARCHAR PRIMARY KEY, applied_at TIMESTAMP)` tracking table,
self-created the same way as the others.

### API changes
None (internal/ops only).

### UI changes
None. Optionally surface the last-applied-patch list on an existing admin/ops surface if one
exists (none does today — out of scope to add one just for this).

### Test plan
- Unit: `SchemaEnsurer` helper — idempotent across repeated calls, thread-safe under
  concurrent first-call race (mirror the existing `synchronized` double-check tests, if any —
  check `AirportAssetSpec`/`HeartbeatSource` callers for precedent, otherwise add new specs).
  `SchemaPatchRunner` — applies each file at most once, skips already-recorded files, order is
  deterministic (filename sort).
- Integration: `MainInit` + CI's "Initialize test database" step must still pass unchanged
  (fresh-DB path untouched).
- Manual/staging: point a throwaway MySQL container at a snapshot of the OptiPlex schema
  *before* a feature ships its `ensureTable()`/patch, deploy, confirm the missing table/column
  is created without an app crash — this is the regression class Airport Assets hit.

### Risks and sequencing dependencies
- Low risk if scoped to refactor + tracking table, since it doesn't touch gameplay code.
- Must not touch `Meta.createSchema()`'s DROP/CREATE semantics — that path is fresh-init-only
  and any change there is irrelevant to (and must not leak into) the live-DB path.
- Should land **first**, before objective 7 (Cargo contracts/SLA) and objective 8
  (Disruption), since both will need new tables and benefit immediately from the formalized
  patch runner instead of another bespoke `ensureTable()`.

---

## 2. Cargo market visibility

### Current relevant code
Air Cargo C-1..C-4 are **already fully shipped and deployed** (`solo.cargo.*` flags all live
in `optiplex-deploy.yml`):
- `airline-data/.../CargoDemandGenerator.scala` — per-directed-pair weekly cargo-units model
  (gravity/economic-mass based), memoized like base passenger demand; also has
  `topCargoDestinations(from, candidates, relationshipsByCountry, limit)` for the per-airport
  top-N view.
- `airline-data/.../data/SoloConfig.scala:225-230` — `cargoEnabled`, `cargoDemandAmplitude`,
  `cargoCaptureRatio`, `cargoRevenuePerUnitKm`, `cargoAssetsEnabled`, `cargoFreightersEnabled`.
- `airline-web/.../controllers/Application.scala` — `getAirportCargoDemand` /
  `computeAirportCargoDemandJson`, route `GET /airports/:id/cargo-demand` (routes:37), backed
  by `ResponseCache.cargoDemandCache` (cycle-cached + 304).
- `airline-web/public/javascripts/airport.js` — `loadAirportCargoDemand` +
  `renderCargoDemandCards`, section `#airportCargoDemandSection`.
- `airline-web/.../controllers/LinkApplication.scala:54-89` (`LinkConsumptionFormat`) — cargo
  revenue already surfaces in link income; `airline.js`'s
  `updatePlanCapacityVsDemand` (line ~1410) already shows a "Capacity vs demand" pax+cargo row
  on the route planner.
- `e2e/tests/cargo-demand-panel.spec.ts`, `e2e/tests/cargo-ui-validation.spec.ts` — existing
  Playwright coverage.
- Known follow-ups already logged in `docs/air-cargo-plan.md` "Backlog" and
  `docs/current-development-state.md` "Remaining follow-ups": `topCargoDestinations` recomputes
  per-pair on each cache miss instead of reusing the per-cycle memoized matrix; the passenger
  `_demandEtag` has a stale-304 bug on airport switch that the cargo one was fixed for but
  passenger wasn't.

### Proposed implementation approach
Given C-1..C-4 are live, "cargo market visibility" as a *new* roadmap slice should mean
**network-level** visibility (today's panel is airport-scoped only):
1. A **cargo market overview** view (mirrors the existing Consultant "Market Overview" pattern
   in `docs/current-development-state.md`'s Consultant feature) — biggest cargo lanes
   network-wide, not just per-airport. Reuse `CargoDemandGenerator`'s memoized matrix rather
   than re-querying per pair (fixes the perf follow-up at the same time).
2. Fix the two logged follow-ups (etag keying, per-cycle matrix reuse) as cheap wins bundled
   into the same PR since they touch the same files.
3. Consider a **cargo route-planner overlay**: when planning a link, show this pair's cargo
   demand explicitly (today it's folded into one "Capacity vs demand" row) so the player can
   tell whether a route's economics lean passenger or cargo.

### Data model changes
None required — all cargo demand is computed on the fly from existing economic data; no new
tables.

### API changes
New `GET /cargo/market-overview` (or extend `GET /airports/:id/cargo-demand` with an optional
`network=true` query param) returning top network-wide lanes — name TBD at implementation
time, follow the existing `cargo-demand` JSON shape for consistency.

### UI changes
New panel (likely on the existing Cargo section of the Office/Network page, alongside where
the Consultant's Market Overview already lives) + the route-planner cargo-demand line item.

### Test plan
- Unit: extend `CargoDemandGeneratorSpec.scala` for the network-aggregation function (pure,
  deterministic, same style as the existing 8 tests).
- Live: `solo.cargo.enabled=true` is already in both `SIM_SOLO_OPTS`/`WEB_SOLO_OPTS`; the new
  panel ships gated on the same flag, no new flag needed.
- E2E: extend `e2e/tests/cargo-demand-panel.spec.ts` for the new overview surface.

### Risks and sequencing dependencies
Low risk — purely additive UI/aggregation on top of a fully shipped, stable feature. No
dependency on other objectives, but pairs naturally with objective 3 (route planner overhaul)
if both touch `airline.js`'s planner code in the same window — coordinate to avoid merge
churn on `updatePlanCapacityVsDemand`.

---

## 3. Route planner and forecast overhaul

### Current relevant code
- `airline-web/.../controllers/LinkApplication.scala` — `preparePlanLink` (line ~811),
  `planLink` (line ~829, `POST /airlines/:id/plan-link`), `ModelPlanLinkInfo`/its `writes`
  (line ~142) — this is the existing "plan a new link" backend surface; it returns aircraft
  suitability info for a candidate route, not a demand *forecast* over time.
- `airline-web/public/javascripts/airline.js` — `planToAirport` (~877), `planLink` (~889,
  client-side), `updatePlanCapacityVsDemand` (~1410) — the whole planner UI lives in one large
  JS file with the rest of the airline page; no dedicated "route planner" module.
- `airline-data/.../DemandGenerator.scala` — the demand model the planner ultimately reflects
  (`computeBaseDemandBetweenAirports`, memoized base layer behind `solo.demand.memoize`).
  There is **no forecast/projection over future cycles** today — demand is a current-cycle
  snapshot; "forecast" would be new ground, not an existing-but-hidden feature.
- `airline-data/.../data/SoloConfig.scala:209` — `demandTouristAmplitude` (seasonal swing
  knob) is the closest existing concept to "forecasting" (it affects future demand
  deterministically by calendar week), but nothing surfaces it to the player as a forward
  projection.
- The Consultant feature (`docs/current-development-state.md`, gated
  `solo.consultant.enabled`) already does route *recommendation* (profit-threshold gated,
  candidate-limited, reusing the same cost/profit model as `LinkSimulation`) — this is the
  most relevant prior art for "what would a forecast/recommendation engine look like in this
  codebase" and should be reused rather than rebuilt.

### Proposed implementation approach
Two genuinely separate pieces are bundled under this roadmap title — split them:
1. **Route planner UX overhaul** (low risk, UI-only): extract the planner logic out of
   `airline.js` into a dedicated module (e.g. `route-planner.js`) so it's testable in
   isolation (Jest, like `abbreviate-money.test.js`); keep all backend calls unchanged
   (`preparePlanLink`/`planLink`). Add cargo-demand-aware display (ties to objective 2).
2. **Forecast**: rather than building a true multi-cycle simulation-ahead forecast (expensive,
   high risk of drifting from the real sim), reuse the **deterministic seasonal curve**
   already in `DemandGenerator.demandRandomizerByType` (driven by `demandTouristAmplitude`) to
   project the *known* seasonal shape forward N weeks for TOURIST demand on a candidate pair,
   labeled clearly as "seasonal estimate," not a true forecast. This is cheap (pure function of
   calendar week, no new sim state) and honest about its limits — avoids the trap of building
   a fake predictive model.
3. Bundle the Consultant's existing profit-estimation math into the planner UI directly (e.g.
   show "Consultant says: profitable" inline) rather than duplicating a second estimator —
   `consultant.enabled` is already live and validated.

### Data model changes
None for the seasonal-projection approach (pure function of existing data). If true
multi-cycle forecasting is later desired, that would need persisted historical demand
snapshots — explicitly deferred, flagged as a much larger and riskier follow-up.

### API changes
Extend `preparePlanLink`'s response (or add `GET /airlines/:id/plan-link-forecast`) with a
small seasonal-curve payload (e.g. 12 weekly multipliers) for the candidate pair.

### UI changes
Planner module extraction (`route-planner.js`); a small seasonal sparkline/chart in the
existing plan-link modal (`airline-web/.../views/fragments/modals.scala.html`).

### Test plan
- Unit (Scala): seasonal-curve extraction as a pure function, tested against known
  `demandRandomizerByType` outputs across a full year.
- Unit (JS, Jest): planner module extraction — no behavior change, snapshot the rendered
  output before/after refactor.
- E2E: extend `e2e/tests/aircraft-delivery.spec.ts`-style flow or add a new
  `route-planner.spec.ts` covering plan → forecast row renders → plan-link submits.

### Risks and sequencing dependencies
Medium risk on the JS refactor (large file, many call sites — `airline.js` is loaded
everywhere) — do the extraction as its own PR with no logic change before adding the forecast
feature on top, so a regression is easy to bisect. Forecast piece depends on nothing else and
can ship independently/after.

---

## 4. Objective / progression MVP

### Current relevant code
**This is already a shipped MVP** (Phase G, `solo.progression.enabled`, live in both
`SIM_SOLO_OPTS`/`WEB_SOLO_OPTS`):
- `airline-data/.../model/AirlineMilestone.scala` — full milestone catalog per
  `AirlineType` (Legacy/Regional/Discount/MegaHq/Luxury), each with tiered
  `MilestoneCondition(threshold, reward)`; `milestoneNotificationsToEmit` (pure, unit-tested)
  decides which newly-crossed tiers should fire a one-time achievement notification.
- `airline-data/.../AirlineSimulation.scala` — calls into the milestone evaluation each cycle
  for player airlines (per the Phase G memory: gated on `track` = progression flag + player
  airline).
- `airline-data/.../data/SoloConfig.scala:203` — `progressionEnabled` flag.
- Office page progress panel (per memory: "Office progress panel fix") — already renders
  milestone progress; exact view file not yet re-confirmed this session but referenced as
  live in `docs/current-development-state.md`/memory `phase-g-progression.md`.
- `airline-data/src/test/scala/com/patson/AirlineMilestoneSpec.scala` — existing coverage.
- Adjacent: `airline-web/.../controllers/CountryApplication.scala` —
  `getCountryAirlineTitleProgression`, route `GET /countries/:code/title-progression`
  (routes:212) — a *different* progression concept (country-level airline title), not part of
  the milestone system; worth checking for naming confusion before adding new progression UI.

### Proposed implementation approach
Since the MVP ships, this objective is really "extend/polish," not "build from zero":
1. Audit whether all 5 `AirlineType`s' milestone sets feel balanced in playtest (the
   thresholds were ported/estimated, not measured against this fork's economy — e.g.
   `MILESTONE_DIVIDENDS` thresholds may need retuning against `solo.startingBalance=25000000`).
2. Add a **milestone history/log view** (distinct from the live progress panel) so a player
   can see *when* they crossed past tiers — reuses the same notification-watermark style
   pattern as World News/Push (lazily-created per-airline watermark row) if a persisted log is
   wanted; otherwise derive from existing notification rows (`NotificationCategory` for
   milestone achievements) with zero new storage.
3. Consider a small number of **new milestone types** that reward newer mechanics
   (cargo revenue total, asset count, consultant-assisted routes) now that Cargo/Assets/
   Consultant are all live — natural cross-feature integration point.

### Data model changes
None required for a notification-derived history view. A persisted log table only if the
existing notification rows prove insufficient (e.g. if old ones get pruned) — defer until
proven necessary.

### API changes
Possibly `GET /airlines/:id/milestones/history` if a dedicated log is built; otherwise reuse
the existing notification-listing endpoints.

### UI changes
New history view/section on the existing Office progress panel.

### Test plan
- Unit: any new milestone types — pure `evaluateMilestone`/`milestoneNotificationsToEmit`
  tests, same style as `AirlineMilestoneSpec.scala`.
- Live: verify with `solo.progression.enabled=true` (already on) that no behavior changes for
  existing milestones; new ones fire exactly once per tier per airline.

### Risks and sequencing dependencies
Low risk — additive on a stable, already-live system. No hard dependency on other objectives,
but new milestone types (cargo/assets-based) are easiest right after objective 2 if both touch
cargo aggregation code.

---

## 5. Pause-when-idle simulation and cycle phase profiler

### Current relevant code
**Both pieces are already fully implemented in code** — they are just not enabled in the
deploy:
- `airline-data/.../MainSimulation.scala:17` — `CYCLE_DURATION = 60 * 29`.
- `MainSimulation.scala:25-26` — `pauseWhenIdle` (`simulation.pauseWhenIdle`, default false)
  and `idleGraceMinutes` (`simulation.idleGraceMinutes`, default 60), read via
  `Constants.configFactory`.
- `MainSimulation.scala:64` — `isIdle()`: compares `System.currentTimeMillis()` against last
  activity.
- `MainSimulation.scala:272-274` — the scheduler's `ExecuteProcessing` handler: when
  `pauseWhenIdle && isIdle() && !fastForwardPending()`, it skips the cycle and logs
  `"Simulation paused: no player activity..."`.
- `airline-data/.../data/HeartbeatSource.scala` — `touch()` / `lastActiveMillis()`, backed by
  a self-creating `activity_heartbeat` table (id=1 singleton row) — this is the **cross-JVM**
  mechanism (web JVM writes, sim JVM reads) that Phase 2 of
  `docs/single-player-performance-roadmap.md` called for, already built.
- `airline-data/.../MainSimulation.scala:97-192` — `startCycle`: `phaseTimings` /
  `timed(phaseName)(block)` wraps each phase (LinkSimulation, AirportSimulation,
  AirlineSimulation, ...) and logs `>>>>> cycle N phase timings: ...` — this **is** the Phase 3
  cycle profiler called for by the roadmap.
- **Confirmed not wired up**: `grep` of `.github/workflows/optiplex-deploy.yml` shows no
  `-Dsimulation.pauseWhenIdle` / `-Dsimulation.idleGraceMinutes` anywhere — the feature is
  dead code in production today. The phase-timing log line is unconditional (always logs),
  so the "profiler" is effectively always-on already, just not surfaced anywhere besides raw
  sim logs.
- **Confirmed wired**: `HeartbeatSource.touch()` is already called from
  `airline-web/app/websocket/ActorCenter.scala:260` (on websocket activity) and
  `airline-web/app/controllers/UserApplication.scala:78` (login path, with the comment
  `//wake the simulation if it is paused-when-idle` — confirming the author already intended
  this to be wired up for the pause feature, even though the sim-side flag is currently off).

### Proposed implementation approach
1. Heartbeat wiring is already in place end-to-end — no new code needed for the mechanism
   itself.
2. Enable `-Dsimulation.pauseWhenIdle=true` (with a sane `idleGraceMinutes`, e.g. 60–120) in
   the **single-player (`SIM_SOLO_OPTS`)** deploy profile only — leave multiplayer/default
   untouched, consistent with every other solo flag.
3. Add a lightweight ops-visible counter (e.g. cumulative cycles skipped) — `MainSimulation`
   already has `SimulationEventStream.scala:31` (`cycleDurationAverage`); a parallel
   skip-counter is a small, low-risk addition for the OBSERVABILITY.md doc.
4. Promote the existing phase-timing log line into `OBSERVABILITY.md` as a documented,
   supported diagnostic (it already exists; just needs to be discoverable) rather than new
   code.

### Data model changes
None — `activity_heartbeat` table already exists and self-creates.

### API changes
None required. Optionally surface "simulation paused due to inactivity" as a tiny web-side
status indicator (read `HeartbeatSource`/a new lightweight "is sim paused" signal) — nice-to-
have, not required for the core feature.

### UI changes
None required for the core mechanic (it's a backend resource optimization). Optional: a small
"Game paused — no recent activity" banner if the web JVM can cheaply detect the sim hasn't
advanced (compare last known cycle vs. expected cadence) — defer unless requested.

### Test plan
- This is mostly **already covered or trivially verifiable** since the code exists:
  confirm/add a unit test for `isIdle()`'s boundary (`MainSimulation` logic, may need a small
  refactor to make it unit-testable in isolation if not already — check for an existing spec;
  none was found under this name, so add one).
- Live: enable the flag in solo-only, watch sim logs for the "Simulation paused" line during
  an idle window, confirm cycle resumes promptly on reconnect (per the roadmap's acceptance
  criteria — idle box near 0% CPU between scheduler wakes).

### Risks and sequencing dependencies
Very low risk and very high value-to-effort ratio — this is **mostly a deploy-config change**,
not new code, assuming the heartbeat wiring is confirmed. Should be one of the first roadmap
items picked up given how little work remains. No dependencies on other objectives.

---

## 6. Reactive AI competition

### Current relevant code
**Phases H-1 through H-5 are all shipped and live** (per `docs/ai-growth-plan.md` and
`SoloConfig.scala:65-153`, all enabled in `optiplex-deploy.yml`):
- `airline-data/.../ComputerAirlineSimulation.scala` — orchestrates the existing drop-only
  path (`solo.ai.enabled`) plus the growth paths.
- `airline-data/.../ComputerAirlineGrowth.scala` (+ `ComputerAirlineGrowthSpec.scala`) — H-1
  route opening; `bestRouteFromBase` reused by H-4 bases.
- `airline-data/.../ComputerAirlinePriceTuning.scala` (+ spec) — H-2 price/frequency nudging
  toward load-factor equilibrium, bounded step, clamped band.
- `airline-data/.../ComputerAirlineFleet.scala` (+ spec) — H-3 fleet renewal for NPCs.
- `airline-data/.../ComputerAirlineBases.scala` (+ spec) — H-4 new-base expansion.
- `airline-data/.../ComputerAirlineStrategy.scala` (+ spec) — H-5 deterministic per-NPC bias
  so growth clusters legibly instead of randomly.
- World News (`solo.news.enabled`, live) is the existing visibility layer for all of this.
- All knobs: `SoloConfig.scala` lines 62-153 (`aiEnabled`, `aiGrowthEnabled`,
  `aiStrategyEnabled`, `aiPriceTuneEnabled`, `aiFleetEnabled`, `aiBasesEnabled` and their
  sub-knobs).

### Proposed implementation approach
"Reactive AI competition" — i.e., NPCs responding *specifically* to the **player's** actions
(not just ambient growth) — is the one genuinely new piece here; everything else is shipped.
Today's NPC behavior is self-directed (profitability/strategy-bias driven) and does not look
at what the player specifically does. A reactive layer would, per the existing design
principles in `docs/ai-growth-plan.md` (slow/bounded cadence, reaction optional/never forced,
news is pull not push):
1. Add a cheap, bounded **player-route-awareness check** inside the existing H-1
   candidate-evaluation loop in `ComputerAirlineGrowth.scala`: when scoring candidate routes,
   slightly deprioritize (not forbid) routes where the player already has a dominant,
   well-defended position (e.g. high frequency + good quality), and slightly favor entering
   markets adjacent to a player's *recently opened, still-thin* routes — modeling a believable
   "a rival noticed you expanding" reaction without per-cycle whack-a-mole.
2. Reuse `ComputerAirlineStrategy`'s existing bias-scoring mechanism (additive bonus/penalty,
   capped) rather than inventing a second scoring system — this keeps the change small and
   consistent with H-5's design.
3. **Confirmed not implemented today**: `ComputerAirlineGrowth.scala` has exactly one
   `WorldNews.post(...)` call site (line 132) with no player-route special case — NPCs "only
   act on NonPlayerAirline carriers... players are never touched" (per the file's own header
   comment), meaning today an NPC opening a route that happens to compete with the player's
   own route gets the *same* ambient World News treatment as any other opening, not an
   escalated notification. Implementing the `ai-growth-plan.md`-specified escalation
   (real notification when a competitor enters the player's own route) is genuinely new work
   for this objective, not a "wire up something that already exists" task.
4. New flag: `solo.ai.reactive.enabled` (independent toggle, same pattern as H-1..H-5), with a
   capped `reactiveBonus` knob mirroring `aiStrategyMaxBonus`.

### Data model changes
None — purely a scoring-function adjustment using data already loaded for candidate
evaluation (existing player link/quality data).

### API changes
None required.

### UI changes
None required for the scoring change itself. If the escalated "competitor entered your route"
notification doesn't already exist, it reuses the existing notification pipeline
(`NotificationCategory`, `Notification.scala`) — no new UI surface, just a new notification
category value (append-only).

### Test plan
- Unit: extend `ComputerAirlineGrowthSpec.scala` — candidate scoring with/without a
  player-dominant route present produces the expected deprioritization, capped and bounded;
  reactive bonus respects the strategy-bias cap pattern from `ComputerAirlineStrategySpec.scala`.
- Economic-stability check (same style as H-1..H-5's verification in `ai-growth-plan.md`): run
  N cycles with reactive scoring on, confirm NPC network growth stays bounded and the player's
  economy isn't destabilized — reuse whatever harness those specs already use.
- Live: enable `solo.ai.reactive.enabled=true` in solo deploy only; fast-forward and confirm
  the "noticed something changed" cadence (every-few-sessions, not per-cycle) per the existing
  design principles.

### Risks and sequencing dependencies
Low-to-medium risk — touches a stable, already-tested scoring function
(`ComputerAirlineGrowth.scala`), so regression risk is contained by the existing spec suite.
No hard dependency on other objectives. Verify the "player-route entry escalates to a real
notification" claim in `ai-growth-plan.md` against actual code before scoping further work —
this map flags it as unconfirmed, not as definitely missing.

---

## 7. Cargo contracts / SLA economy

### Current relevant code
Builds on the fully-shipped Air Cargo C-1..C-4 (see objective 2): `CargoDemandGenerator.scala`,
`SoloConfig.scala:219-230` (`cargoEnabled`/`cargoCaptureRatio`/`cargoRevenuePerUnitKm`/
`cargoAssetsEnabled`/`cargoFreightersEnabled`), belly-cargo revenue wired into
`LinkSimulation.scala`'s consumption/income path, `LedgerType.CARGO_REVENUE` (append-only
enum, `model/Airline.scala`), Cargo Terminal asset type (C-3, in `AirportAssetSource`/
`AirportAssetType` family), freighters (C-4). No contract/SLA concept exists anywhere today —
cargo revenue is purely automatic/ambient (per the original design's "no per-flight player
input" north star in `docs/air-cargo-plan.md`).

### Proposed implementation approach
This is genuinely new gameplay, not an extension of existing code — approach carefully against
the cargo design's explicit north star ("strategic texture, not micro-management"; "no
per-flight player input"). A contracts/SLA layer risks violating that principle if it becomes
per-shipment micromanagement. Recommended shape, consistent with the existing
ambient-but-strategic pattern:
1. **Contract = a standing commitment on a lane, not a per-shipment booking.** E.g. a player
   can accept a multi-cycle contract on an existing route ("commit 500 units/week to Shipper X
   for 12 cycles") that pays a premium over spot cargo revenue but penalizes (ledger-charged)
   if the route's actual carried cargo falls below the committed SLA for N consecutive cycles
   (capacity cut, demand drop, route dropped). This stays "set and forget," matching the
   passenger consultant/cargo design philosophy.
2. **New model**: `CargoContract` (analogous structure to `AirportAsset`'s lifecycle —
   constructed/active/expired states) — fields: airline, fromAirport, toAirport, committedUnits,
   premiumRate, startCycle, durationCycles, penaltyRate, status.
3. **New table** `cargo_contract`, self-created via `ensureTable()` (or the new
   `SchemaPatchRunner` from objective 1, if that lands first — natural place to dogfood it).
4. **Sim integration**: a new `CargoContractSimulation` (or extend `LinkSimulation`'s existing
   cargo step) checks active contracts each cycle: pays the premium for units actually carried
   up to the commitment, charges the penalty for shortfall, reusing the existing belly-capacity
   capture logic from C-2 rather than a new capacity model.
5. **Offer generation**: reuse the Consultant's existing candidate-scoring infrastructure
   (`docs/current-development-state.md`'s Consultant feature) to periodically surface a small
   number of contract *offers* on the player's existing cargo-heavy routes — never invented out
   of thin air, always grounded in real demand data already computed by
   `CargoDemandGenerator`.
6. New `SoloConfig` knobs: `solo.cargo.contracts.enabled` (default false, independent of the
   base `cargoEnabled` so it layers cleanly), `contractPremiumRate`, `contractPenaltyRate`,
   `contractMaxActivePerAirline`, `contractOfferIntervalCycles`.

### Data model changes
New `CargoContract` case class + `cargo_contract` table (self-created); new `LedgerType`
entries `CARGO_CONTRACT_PREMIUM` / `CARGO_CONTRACT_PENALTY` (append-only).

### API changes
- `GET /airlines/:id/cargo-contracts` — list active/offered/expired contracts.
- `POST /airlines/:id/cargo-contracts/:contractId/accept` — accept an offered contract.
- (Optional) `POST .../cancel` if early cancellation is allowed (with a cancellation cost, to
  avoid trivializing the SLA commitment).

### UI changes
New "Cargo Contracts" section on the existing Cargo panel (objective 2's market-overview
location is a natural neighbor); contract offer cards reuse the existing demand-card visual
style (`renderCargoDemandCards` precedent in `airport.js`); income statement gains the two new
ledger line items (reuse the existing `CARGO_REVENUE` income-statement label precedent from
the C-2.5 rollout).

### Test plan
- Unit: pure contract-evaluation math (premium/penalty calculation given committed vs. actual
  carried units) — mirror `CargoAllocationSpec.scala`/`CargoDemandGeneratorSpec.scala` style,
  DB-free.
- Economic-stability check: confirm contracts can't be gamed to produce unbounded revenue
  (premium capped relative to spot rate; penalty makes overcommitment a real risk) — same
  spirit as the AI-growth "economic stability" checks already in the test suite.
- Live: gate behind `solo.cargo.contracts.enabled`, verify default cargo behavior (objective
  2/C-1..C-4) is unchanged when the new flag is off.
- E2E: extend `e2e/tests/cargo-ui-validation.spec.ts` or add a new spec for the
  accept-contract → see it in the income statement flow.

### Risks and sequencing dependencies
**Hardest-to-scope objective on this list** — genuinely new economy mechanic with real risk of
becoming busy-work if not kept "ambient commitment" rather than "per-shipment task." Should
follow objective 1 (so the new table uses the formalized patch pattern) and ideally follow
objective 2's market-overview work (shares UI surface and the network-aggregation data it
introduces). Needs explicit design sign-off on the premium/penalty balance before
implementation — recommend a short design note (mirroring the `docs/superpowers/specs/*`
pattern already used for Web Push Completeness / Asset Decision Support) before coding.

---

## 8. Disruption and event recovery systems

### Current relevant code
**No delay/weather/disruption mechanics exist today.** Confirmed by searching
`airline-data/.../model/` for `delay|disruption|weather|cancellation` — only incidental hits
(`Transport.scala`, `Link.scala` etc. reference flight timing/duration fields, not stochastic
disruption). The one "event" system that does exist is unrelated:
- `airline-data/.../EventSimulation.scala` (+ `EventSimulationSpec.scala`) — the **Olympics**
  world event (`model/event/{EventType,Olympics,...}`): a multi-year scheduled event with
  candidate-airport selection, voting rounds, passenger goals. This is useful prior art for
  "how this codebase models a multi-phase scheduled world event with persisted state," but it
  has nothing to do with operational disruption (delays/cancellations/recovery).
- `LinkConsumptionResult.scala` / `LinkSimulation.scala` model flight *economics* per cycle,
  not per-flight operational reliability — there is no concept of an individual flight being
  delayed or cancelled today; the sim operates at a weekly-aggregate level (a "link" is a
  route+frequency, consumed in aggregate by `LinkSimulation`, not simulated flight-by-flight).
- `MILESTONE_ON_TIME` (`AirlineMilestone.scala`, Regional/Discount/MegaHq airline types) — the
  closest existing concept is an **on-time-departures milestone counter**, implying some
  existing "on-time" metric is tracked somewhere (`AirlineSimulation.scala` or
  `LinkSimulation.scala` likely increment a counter) — **verify this counter's source before
  assuming on-time tracking is purely a milestone label with no underlying signal; it may be
  a usable hook for a disruption system** (e.g. disruptions could lower this same counter).

### Proposed implementation approach
This is the **least-built objective on the roadmap** — there is no flight-level operational
model to disrupt, since the sim operates on weekly link aggregates, not individual flights.
Two honest paths:
1. **Aggregate-level disruption (fits the existing architecture, recommended first slice)**:
   model disruption as a **cycle-level quality/reliability penalty** on a link or airport,
   not a per-flight event — e.g. a weather event at an airport temporarily reduces effective
   capacity/quality for affected links for N cycles, computed the same way
   `AirportBoostContributor`/asset boosts already apply multiplicative factors to demand —
   reuse that exact mechanism in reverse (a temporary negative boost) rather than building a
   new effect pipeline. "Recovery" = the penalty naturally decays after N cycles; no new
   recovery logic needed if modeled as a time-bounded boost.
2. **True per-flight disruption** (delays/cancellations affecting individual departures) would
   require modeling individual flight instances, which **does not exist in this codebase's
   data model today** — this is a much larger architectural change (likely a new `Flight`
   instance concept distinct from `Link`) and should be explicitly scoped out of an initial
   slice; flag as "investigate only" rather than commit to building.
3. Recommended first slice: a `DisruptionEvent` system mirroring `EventSimulation.scala`'s
   structure (scheduled, persisted, multi-cycle lifecycle) but producing airport/link-level
   reliability penalties instead of Olympics-style scoring, surfaced via the existing World
   News pipeline (`WorldNews.post`) for visibility, consistent with how AI growth/strategy
   moves are surfaced.
4. Gate behind `solo.disruption.enabled` (default false); knobs for frequency, severity,
   duration, affected-radius.

### Data model changes
**Confirmed reusable**: `EventSource.scala`'s `event` table is already generic
(`event_type, start_cycle, duration` — see the `INSERT INTO EVENT_TABLE` at line 584), the
same table `Olympics` rows live in. A new `DisruptionEvent` case class extending the existing
`model/event` hierarchy (alongside `Olympics`) can reuse this table directly — add a new
`EventType.DISRUPTION` value (append-only enum) rather than creating a new table. Any
disruption-specific detail (affected airport/link ids, severity) needs either a small
generic key-value side-table (mirroring how Olympics' affected-airports/vote-rounds use their
own auxiliary tables — check `saveOlympicsAffectedAirports`/`saveOlympicsCandidates` in
`EventSource.scala` for the established pattern) or an encoded payload column.

### API changes
None required for a purely sim-internal aggregate penalty (surfaces via existing World
News/notification pipelines). If a player-facing "current disruptions near you" panel is
wanted, a new `GET /airports/:id/disruptions` (or fold into the existing demand/cargo-demand
endpoint family) would be needed — defer until the underlying mechanic is validated.

### UI changes
None required for the first slice beyond World News entries (already rendering). A dedicated
disruption indicator on the airport panel is a reasonable follow-up once the mechanic proves
out, reusing the Airport Asset "section, hidden when empty/flag-off" pattern.

### Test plan
- Unit: pure disruption-penalty math (time-bounded multiplicative factor, same testable shape
  as `AirportBoostContributor` boosts) — DB-free spec.
- Unit: `DisruptionEvent` lifecycle (scheduled → active → expired), mirroring
  `EventSimulationSpec.scala`'s existing Olympics-lifecycle test style.
- Economic-stability check: confirm a disruption can meaningfully affect an affected link's
  economics without being catastrophic or game-breaking (bounded severity), and that default
  (`solo.disruption.enabled=false`) behavior is provably unchanged.
- Live: enable in solo-only deploy, confirm a triggered disruption shows in World News and
  recovers automatically after its duration with no manual intervention needed.

### Risks and sequencing dependencies
**Highest architectural-uncertainty objective on the roadmap.** Recommend a short
investigation spike (read `EventSource.scala`'s actual schema, confirm whether the
`MILESTONE_ON_TIME` counter has real underlying signal worth reusing) before committing to an
implementation plan — write that as a `docs/superpowers/specs/*` design doc first, per this
codebase's established practice for non-trivial features. No hard dependency on other
objectives, but the World-News-as-visibility-layer pattern (objective 6's sibling) and the
generic-table-vs-new-table question (objective 1's patch runner) should both be resolved
before this lands so it doesn't duplicate infrastructure.

---

## 9. E2E and regression test expansion

### Current relevant code
- `e2e/tests/*.spec.ts` — `aircraft-delivery.spec.ts`, `airport-mobile.spec.ts`,
  `authenticated-pages.spec.ts`, `cargo-demand-panel.spec.ts`, `cargo-ui-validation.spec.ts`,
  `smoke.spec.ts`, `ui-polish-verify.spec.ts`. `e2e/package.json` — Playwright `^1.58.0`,
  scripts `test` (runs the suite) and `test:list` (lists without running).
- **`.github/workflows/ci.yml` only runs `npm --prefix e2e run test:list`** — it verifies the
  suite is *discoverable*, it does **not** actually execute the Playwright tests against a
  running app in CI. Real E2E execution currently only happens via the separate
  `OptiPlex Deploy & Verify` workflow (per `docs/current-development-state.md`/memory
  `airline-optiplex-ci-status.md`), i.e. **post-deploy, against the live box**, not pre-merge
  in PR CI.
- `ci.yml`'s actual test coverage: a fixed allowlist of Scala specs run via `sbt testOnly`
  (`CargoDemandGeneratorSpec`, `CargoAllocationSpec`, `AirplaneModelSpec`, `UserSpec`,
  `StockModelSpec`, `ModelDiscountSpec`, `AirportSimulationSpec`) — **not the full test
  suite** (there are 35+ specs under `airline-data/src/test`, only 7 run in PR CI). Most specs
  (e.g. `ComputerAirlineGrowthSpec`, `AirlineMilestoneSpec`, `DemandGeneratorSpec`,
  `LinkSimulationSpec`, `PassengerSimulationSpec`) are **not gated in PR CI today** — they
  presumably ran clean at some point but nothing prevents a future regression from landing
  unnoticed in PR review.
- `airline-web/test/` — only 5 test files total (2 Scala controller/push/websocket specs, 3
  Jest JS specs); not run at all in `ci.yml` (no `sbt test` step for `airline-web`, no
  `npm test` step for its Jest files — only `sbt compile`).

### Proposed implementation approach
This objective has the clearest, lowest-risk, highest-value path of the whole roadmap because
the gap is almost entirely **CI wiring, not new test-writing infrastructure**:
1. **Run the full `airline-data` spec suite in CI**, not the 7-item allowlist — change
   `sbt "testOnly A B C ..."` to plain `sbt test` (or an explicit "run everything except
   slow/flaky ones" exclude-list if some specs need a real network/external dependency —
   audit first). This alone catches regressions across AI growth, milestones, demand,
   passenger/link simulation that nothing currently gates on PRs.
2. **Run `airline-web`'s tests in CI**: add `sbt test` for the 2 Scala specs and
   `npm test --prefix airline-web` (or wherever the Jest config lives) for the 3 JS specs.
3. **Actually execute the Playwright suite in CI**, not just `test:list`: this needs the app
   stack running (web + sim + MySQL) inside the CI job — `docker-compose.small.yaml` is the
   natural candidate profile (already built for low-resource environments per the performance
   roadmap). Bring it up, wait for health, run `npm --prefix e2e test`, tear down. This is the
   single highest-value change in this objective — it closes the gap where E2E regressions are
   currently only caught *after* deploying to the live box.
4. **Expand spec.ts coverage** for the features that have shipped without E2E coverage:
   Consultant advisor flow, World News page load, Airport Assets build/upgrade/sell flow,
   Progression panel. Each as a small, focused spec file following the existing naming
   convention.
5. Add a CI **status badge / required-checks** note to `README.md` once the above lands, so
   PR authors know what's actually gated vs. advisory.

### Data model changes
None.

### API changes
None.

### UI changes
None.

### Test plan
This objective *is* the test plan — implementation should proceed in the order above (full
Scala suite → web tests → real Playwright execution → new spec coverage) so each step is
independently verifiable: confirm CI goes from "list only" to "green full run" before adding
new specs on top of a now-trustworthy baseline.

### Risks and sequencing dependencies
- Running the full `airline-data` suite may surface **pre-existing failures** that were never
  caught because only 7 specs ran — budget time to triage, don't assume everything passes.
- Standing up `docker-compose.small.yaml` in a GitHub-hosted runner needs resource and timing
  validation (cold MySQL start, app boot time before Playwright can hit it) — likely needs a
  health-check polling step before the test run, similar to the existing MySQL
  `health-cmd`/`health-interval` pattern already in `ci.yml` for the DB service.
- Should be picked up **early and independently** — it has no dependency on any other
  objective, and a hardened CI baseline makes every other objective on this list safer to
  implement (objective 8 in particular, given its architectural uncertainty).

---

## Suggested sequencing summary

| Order | Objective | Why this position |
|---|---|---|
| 1 | 9. E2E/regression CI hardening | No dependencies; de-risks everything after it; mostly config, not new code. |
| 2 | 5. Pause-when-idle + profiler | Already built; just enable + verify; near-zero implementation cost. |
| 3 | 1. Migration/DB hardening | Needed before any objective that adds new tables (7, 8). |
| 4 | 2. Cargo market visibility | Additive on a stable, shipped feature; pairs with 3's planner work. |
| 5 | 4. Progression MVP extensions | Additive on a stable, shipped feature; natural cross-link to 2. |
| 6 | 3. Route planner/forecast overhaul | Larger JS refactor; do after CI hardening (1) is in place to catch regressions. |
| 7 | 6. Reactive AI competition | Touches well-tested scoring code; needs design confirmation on escalation behavior first. |
| 8 | 7. Cargo contracts/SLA economy | Needs its own design note; benefits from 1's patch runner and 2's market UI. |
| 9 | 8. Disruption/event recovery | Highest uncertainty; needs an investigation spike and design doc before implementation. |

## Open questions to resolve before implementation (not blocking this map)
- Objective 8: confirm whether `MILESTONE_ON_TIME` has a real underlying on-time-departure
  signal worth reusing as a disruption hook (the `event` table reuse question is already
  resolved — see objective 8's data model section above).

(Objectives 5 and 6's open questions were also resolved during this map's research: heartbeat
wiring is confirmed live; the player-route notification escalation is confirmed *not yet
built* — see their sections above.)
