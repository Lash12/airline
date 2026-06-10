# Single-Player Performance Roadmap

Target: a smooth single-player experience on a Dell OptiPlex 3050 Micro class box
(4 cores, 8 GB RAM, SSD), deployed with Docker Compose. This fork diverges freely
from upstream myflyclub/airline; new behavior is still gated behind config flags
so multiplayer defaults keep working.

This supersedes `small-server-performance-plan.md`. Completed from that plan:
baseline doc, small compose profile, Hikari tuning (`hikari.*` in
`airline-data/src/main/resources/application.conf` and `airline-web/conf/application.conf`),
cache metrics (Caffeine `recordStats`), bounded simulation pool
(`simulation.threadPoolSize`), CI + Playwright scaffold (`e2e/`).

Memory budget on 8 GB: MySQL ~1.2 GB, simulation JVM ~1.7 GB, web JVM ~1.2 GB,
OS + page cache the rest.

## Phase 0 — Baseline on target hardware
Run the stack on the OptiPlex and record actuals (not just procedures) using
`docs/performance-baseline.md`: container RSS (`docker stats`), full simulation
cycle wall time (from `airline-data` logs), first-page load time. Commit the
numbers to this doc as the "before" column.

## Phase 1 — Resource diet (config-only, no code changes)
1. **JVM right-sizing**: `.docker/data/start.sh` `-Xmx4G` → `-Xmx1536M`,
   `.docker/web/start.sh` `-Xmx2G` → `-Xmx1G`; `MaxMetaspaceSize` 512M → 256M on both.
2. **MySQL tuning**: add a `my.cnf` mounted by compose:
   `innodb_buffer_pool_size=768M`, `performance_schema=OFF`, `skip-log-bin`,
   `innodb_flush_log_at_trx_commit=2`, `max_connections=60`.
3. **Hikari pools**: web `hikari.maxPoolSize` 30 → 10, data 15 → 8.
4. **Fix `docker-compose.small.yaml`**: it currently references `build: .` and a
   `./app` volume that don't match the repo layout — rebuild it on the real
   `.docker/` images, drop Elasticsearch, add `mem_limit`s, mount the `my.cnf`.
   Make it the canonical single-player deployment and update `SMALL_SERVER.md`.
5. **Remove dead weight**: drop the unused `elasticsearch` service from
   `docker-compose.yaml` (no code references it). Note: the Google API deps in
   `airline-web/build.sbt` are NOT dead weight — `GooglePhotoUtil` backs the
   banner feature and `javax.mail` backs `EmailService` — they stay.

## Phase 2 — Pause-when-idle simulation (biggest win)
`MainSimulation` advances the world every 29 minutes of wall-clock time even with
zero players (`MainSimulation.scala`, `CYCLE_DURATION = 60 * 29`).

- Track player activity in airline-web (active websocket sessions in
  `app/websocket/ActorCenter.scala` and/or authenticated HTTP requests) and write
  a last-active heartbeat to a small DB table — using the DB avoids new
  pekko-remote message types between the two JVMs.
- In the simulation scheduler, before each cycle: if
  `simulation.pauseWhenIdle = true` (new flag, default false) and last activity
  is older than `simulation.idleGraceMinutes` (default 60), skip the cycle and
  log it. Game time simply stops while away — no catch-up burst on return.
- Acceptance: idle box sits near 0% CPU between scheduler wakes; play resumes
  within one cycle interval of reconnecting.

## Phase 3 — Cheaper cycles
- **Per-phase cycle profiler**: log wall time of each phase in
  `MainSimulation.startCycle` (LinkSimulation, AirportSimulation,
  AirlineSimulation, ...) so optimization targets are measured, not guessed.
- **Targeted cache invalidation — investigated and rejected.** Airports are NOT
  static from the simulation's point of view: players mutate them (bases,
  lounges) through the *web JVM*, which has its own cache instances, and the
  sim's start-of-cycle `invalidateAll()` is what picks those cross-process
  writes up. Skipping or deferring it would make the sim ignore player actions
  taken between cycles. A correct fix needs cross-JVM cache invalidation
  (event-driven over the existing pekko bridge) — deliberately out of scope.
- **Demand memoization**: `DemandGenerator` recomputes demand from airport
  population/income data that rarely changes; cache the base demand model
  between cycles, recompute on data change only.
- **PassengerSimulation**: profile first (it loops route-finding up to 10
  consumption retries); bound or early-exit when remaining capacity is zero.

## Phase 4 — Real DB index audit
`INDEXES.md` documents indexes for `bookings`/`seats`/`flights`/`passengers`
tables that do not exist in this schema — delete it.

- Enable the slow query log (`long_query_time=0.5`) during several cycles plus
  normal UI browsing; `EXPLAIN` the top offenders against the real tables
  (`link_consumption`, `passenger_route_history`, `passenger_link_history`,
  ledger/income, notifications, loyalist).
- Add measured indexes to `Meta.scala` `createSchema` plus an idempotent
  migration script for existing databases; write a new `INDEXES.md` containing
  the actual `EXPLAIN` evidence.

## Phase 5 — Frontend lightening (lower priority on LAN)
- Self-host the CDN dependencies (jQuery, Chart.js, TypeKit fonts in
  `app/views/fragments/head.scala.html`) so the game works fully offline.
- Defer non-critical scripts in `app/views/fragments/scripts.scala.html`
  (30+ JS files load on the main page).
- Images (53 MB in `public/images/`) are already lazy-loaded; optionally convert
  the largest backgrounds to WebP. Lowest priority.

## Phase 6 — Validation
- Extend the Playwright suite beyond the homepage smoke test: login, airport
  view, create-link flow.
- Re-run Phase 0 measurements on the box and record before/after in this doc.

## Suggested PR slicing
One PR per phase (Phase 1 may split config vs compose). Each PR states its
measured or expected effect against the Phase 0 baseline.
