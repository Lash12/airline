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
   _(Update: pool was raised to 16 in the DB pool exhaustion fix 2026-06-21 —
   keep at 16 unless memory pressure demands lower.)_
4. **Fix `docker-compose.small.yaml`**: it currently references `build: .` and a
   `./app` volume that don't match the repo layout — rebuild it on the real
   `.docker/` images, drop Elasticsearch, add `mem_limit`s, mount the `my.cnf`.
   Make it the canonical single-player deployment and update `SMALL_SERVER.md`.
5. **Remove dead weight**: drop the unused `elasticsearch` service from
   `docker-compose.yaml` (no code references it). Note: the Google API deps in
   `airline-web/build.sbt` are NOT dead weight — `GooglePhotoUtil` backs the
   banner feature and `javax.mail` backs `EmailService` — they stay.

## ~~Phase 2 — Pause-when-idle simulation~~ — ABANDONED

**Product decision (2026-06): do not implement pause-when-idle or any feature that
skips, pauses, or delays simulation cycles because the player is inactive.**

The mechanism is already fully built in the code (`MainSimulation.scala`,
`HeartbeatSource.scala`, `ActorCenter.scala`), but enabling it is intentionally
refused. The game world should advance continuously. Do not wire
`-Dsimulation.pauseWhenIdle=true` in any deploy config.

If a future agent sees this code, leave it disabled. Do not treat "the code exists"
as a reason to enable it.

## Phase 3 — Cheaper cycles — SHIPPED

All three items confirmed shipped as of 2026-06-20 review:

- **Per-phase cycle profiler**: `MainSimulation.startCycle` wraps every phase in
  `timed(phaseName)(block)` and logs `>>>>> cycle N phase timings:` unconditionally
  on every cycle. Profiler is always-on; no new code needed.
- **Demand memoization**: `DemandGenerator` caches the base demand model between
  cycles, controlled by `solo.demand.memoize=true` (live in `optiplex-deploy.yml`).
- **PassengerSimulation early exit**: `demandChunks.nonEmpty` guard in the
  consumption loop prevents spinning on zero-capacity routes.

No further work in this phase.

## Phase 4 — Real DB index audit — SHIPPED

- `INDEXES.md` (stale upstream remnant) already deleted.
- `link_consumption` airport-pair index (`Meta.scala`) confirmed in place.
- Slow-query audit is available via `.docker/db/small.cnf` if needed.

No further work in this phase.

## Phase 5 — Frontend lightening (lower priority on LAN)
- Self-host the CDN dependencies (jQuery, Chart.js, TypeKit fonts in
  `app/views/fragments/head.scala.html`) so the game works fully offline.
- Defer non-critical scripts in `app/views/fragments/scripts.scala.html`
  (30+ JS files load on the main page).
- Images (53 MB in `public/images/`) are already lazy-loaded; optionally convert
  the largest backgrounds to WebP. Lowest priority.

## Phase 6 — Playwright validation — PARTIALLY DONE
- Base scaffold (`e2e/`) exists and runs in CI.
- Login, airport view, asset modal, and mobile UX tests added through 2026-06-21.
- Remaining: broader link-creation flow tests; re-run Phase 0 measurements on
  the live box and record before/after in `docs/performance-baseline.md`.
