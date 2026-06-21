# Current Development State

Last updated: 2026-06-20

## Repository / Environment

- Main repo path: `C:\Users\logan\OneDrive\Desktop\Airline\airline`.
- LAN deployment target: OptiPlex at `192.168.1.52`.
- SSH is configured from the Windows/Codex environment:
  - `ssh airline-dev "hostname && docker ps --format '{{.Names}} {{.Status}}'"`
  - Expected containers: `airline-cloudflared`, `airline-app`, `airline-db`.
- Cloudflare Tunnel + Access exposes the app at `https://airline.ashhome.org`.
- OptiPlex deploys are handled by GitHub Actions workflow `OptiPlex Deploy & Verify`.

## Local Tooling

The Windows host now has the core development tools installed:

- Java 17: `C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot`
- sbt: `C:\Program Files (x86)\sbt\bin\sbt.bat`
- Node/npm: verified with Node `v24.17.0`, npm `11.13.0`
- ripgrep: verified
- Playwright package in `e2e`: verified at `1.58.0`

This Codex session had an old PATH, so Java/sbt needed to be injected manually. Future sessions
should see them normally after restart. If not:

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
$env:Path="$env:JAVA_HOME\bin;C:\Program Files (x86)\sbt\bin;$env:Path"
```

Local Scala compile sequence verified:

```powershell
cd C:\Users\logan\OneDrive\Desktop\Airline\airline\airline-data
sbt publishLocal

cd C:\Users\logan\OneDrive\Desktop\Airline\airline\airline-web
sbt compile
```

`airline-data publishLocal` and `airline-web compile` both passed locally after setting the
tool paths. Existing warnings are non-blocking.

## Web Push Notifications

Web Push is implemented, deployed, and end-to-end validated for Lash Air on Firefox Android.

Important commits from the validation/fix sequence:

- `f78ca6ce fix(web): reload open clients on new deploy`
- `036e9009 chore(web): add web push validation workflow`
- `f8c9725a fix(web): start push subscriptions at current notification watermark`
- `473c86d6 ci: keep build targets out of optiplex source cleanup`
- `c1aa6460 chore(web): log failed web push responses`
- `c20241e9 ci: collect web log in push validation`
- `ea0d45c8 fix(web): encode VAPID ECDSA signatures correctly`

Key fixes:

- Rotated malformed VAPID keypair in GitHub secrets.
- Fixed mobile browser cache/deploy refresh via `/build-info`.
- Fixed new push subscriptions to start at the current notification high-water mark, avoiding
  a backlog of historical pushable notifications.
- Fixed VAPID ECDSA DER-to-JOSE signature conversion. Before this, Mozilla autopush returned
  `401 InvalidSignature`.
- Added failed push response logging in `PushNotificationScheduler`.
- Added manual workflow `Validate Web Push` for end-to-end production validation.

Validated evidence:

- OptiPlex deploy workflow passed after the final fixes.
- `Validate Web Push` workflow run `27856841823` passed.
- The validation inserted notification `10136`.
- Subscription `2` advanced to `last_pushed_notification_id = 10136` with `failure_count = 0`.
- User confirmed the Android Firefox device received the test notifications.

Manual validation workflow:

```powershell
gh workflow run "Validate Web Push" --repo Lash12/airline --ref master -f airline_id=34
gh run watch <run-id> --repo Lash12/airline --exit-status
```

Direct OptiPlex checks now possible over SSH:

```powershell
ssh airline-dev "docker exec airline-app sh -c 'grep -ai \"\\[push\\]\" /home/airline/web.log | tail -40'"
ssh airline-dev "docker exec airline-db mysql -u\"\$(docker exec airline-db printenv MYSQL_USER)\" -p\"\$(docker exec airline-db printenv MYSQL_PASSWORD)\" \"\$(docker exec airline-db printenv MYSQL_DATABASE)\" -e 'SELECT id, airline, last_pushed_notification_id, failure_count FROM push_subscription ORDER BY id;'"
```

## Deployment Guardrails

- Do not run destructive Docker volume operations against the OptiPlex database.
- MySQL persistence must remain mounted at `/bitnami/mysql` for the Bitnami legacy image.
- Prefer `scripts/optiplex-deploy.sh` / `OptiPlex Deploy & Verify` for deploys.
- SSH can be used for read-only checks, logs, DB inspection, and one-off validation.
- If changing Scala locally, run `airline-data publishLocal` before compiling `airline-web`.

## Known Worktree Notes

The local worktree had pre-existing unrelated dirty files before the web-push validation work.
Do not revert or commit unrelated changes unless the user explicitly asks. Check `git status`
before each commit and stage only the files relevant to the current task.

Untracked `.sbt-boot/` may appear under `airline-web` from local sbt boot-cache testing and
should not be committed.

## Web Push Completeness Phase — shipped 2026-06-20

Followed `docs/superpowers/specs/2026-06-20-web-push-completeness-design.md` /
`docs/superpowers/plans/2026-06-20-web-push-completeness.md`. Deployed and verified live on
the OptiPlex in two pushes:

- Settings UX cleanup: the push status line no longer shows raw developer diagnostics by
  default (now gated behind `?pushDebug=1`); a denied browser permission now shows actionable
  guidance ("enable in your browser's site settings, then reload") instead of just "Permission
  denied". Verified the deployed `push.js` asset contains the new code; full visual/permission-flow
  behavior still needs a one-time spot-check on a real device (Firefox Android) next time the
  user is on their phone.
- Admin-gated test-send + observability: new `solo.push.adminAirlineId` config (set to `34`,
  Lash Air, in the OptiPlex deploy workflow) gates a "Send test notification" button in the
  drawer (`POST /airlines/:id/push-test`) and a `GET /airlines/:id/push-summary` JSON endpoint
  (per-subscription failure/watermark state plus in-process scheduler counters). Verified both
  routes are registered and correctly return 401 without a session; verified the push sender
  started cleanly post-deploy (`[push] sender enabled for categories NEGOTIATION_READY,...`).
  The actual button-click-to-phone-notification round trip still needs the user to try it from
  a logged-in session — not yet exercised end-to-end.
- `NEGOTIATION_READY` live-cycle verification (the original item: confirming a real
  cooldown-driven push, not just a test insert) was **closed as code-verified, not live-observed**
  — by user decision, given `AirlineSimulation.scala:40` already calls
  `NegotiationReadyNotifier.emit(cycle)` every cycle, `PushPayload.urlFor` already builds the
  correct deep link, and there's an existing unit test. No live cooldown expired during this
  session to watch it fire for real; not blocking further work.

## Performance roadmap Phase 3 & 4 — already shipped (docs were stale)

A research pass on 2026-06-20 found that `docs/single-player-performance-roadmap.md`'s Phase 3
("cheaper cycles") and Phase 4 ("real DB index audit") were already fully implemented and live,
contradicting this doc's previous "suggested next feature" pointer to them. Confirmed directly:
the per-phase cycle profiler (`MainSimulation.scala`), demand memoization
(`solo.demand.memoize=true` live in `optiplex-deploy.yml`, implemented in `DemandGenerator.scala`),
`PassengerSimulation`'s early-exit-on-zero-capacity (`demandChunks.nonEmpty` in its consumption
loop), and the measured `link_consumption` airport-pair index (`Meta.scala`) are all in place.
`INDEXES.md` is already deleted. Nothing left to do here.

## World News redesign — shipped 2026-06-20 (root cause not fully resolved, mechanism replaced)

While evaluating whether to build AI-growth Phase H-4 (NPC new bases), a live check found the
player's own airline (34, "Lash Air") had received zero `WORLD_NEWS` notifications ever, despite
real drop/open events firing and reaching 95+ other (mostly unclaimed/leftover) airline records.
Investigation (see the session transcript for the full trail) ruled out airline-type
misclassification, a missing `airline_info` join row, duplicate airline rows, and "too new to
have seen one" — `airline_base.founded_cycle=3` for airline 34, vs. current cycle ~196 at the
time. The exact historical root cause inside the old per-recipient fan-out was never pinned down
with certainty (no live forensic trail remained — binlog off, sim log rotated).

Decision: replace the mechanism rather than keep chasing it. `WorldNews.post` used to write one
notification row per non-NPC airline (`WorldNews.playerAirlineIds()`, confirmed ~104 such rows in
this world, almost all unclaimed leftover slots from multiplayer world-seeding) to deliver a
single event. It now writes once to a new `world_news` table (no `airline` column — it's global
broadcast content) and each airline tracks its own read position via a lazily-created
`world_news_watermark` row, mirroring the push-subscription watermark pattern (including
defaulting a new watermark to "caught up as of now," not 0). `NotificationApplication.getNews`/
`markNewsRead` produce the same JSON shape as before, so no frontend changes were needed.

Verified live: both new tables get lazily created correctly (confirmed via the user's own News
page visit, which created airline 34's watermark at `0` — correct, since the table was empty at
the time); a fresh throwaway test account (user `claudetest1`, airline 136 — safe to delete
later) round-tripped `GET /airlines/136/news` (`[]`, no error) and `POST /airlines/136/news/read`
(`{}`, correctly created its watermark) over real HTTP with real session auth. **Not yet
verified**: an actual drop/open event landing in the new `world_news` table — none fired between
deploy and end of session (a 66-cycle dry spell, cycle 175→241, vs. roughly 48 events across the
prior 175 cycles). Check `SELECT * FROM world_news ORDER BY id DESC LIMIT 5;` next session; if
still empty, consider asking the user to fast-forward a few cycles, since the code path itself is
otherwise fully exercised and correct.

Optional follow-up, not yet done: the ~10,000+ old per-airline `WORLD_NEWS` rows in the
`notification` table are now permanently orphaned (nothing reads or writes them anymore) and
could be bulk-deleted to reclaim space — destructive, needs explicit go-ahead, do not run
automatically.

## AI growth Phase H-4 (NPC base expansion) — shipped 2026-06-20

The last unbuilt living-world AI phase is done; **all of H-1…H-5 now ship**. `ComputerAirlineBases`
(behind `solo.ai.bases.enabled`, enabled in the OptiPlex deploy) lets a thriving NPC promote a city
it already serves into a new base, re-home one fully-idle frame there, and launch its best
profitable first route in the same cycle (reusing `ComputerAirlineGrowth.bestRouteFromBase`, which
was extracted from H-1's per-frame loop). Self-limiting: ≤1 opening/cycle across all NPCs, per-NPC
base ceiling, ledger-charged cost + cash cushion, profitable-route gate. See `docs/ai-growth-plan.md`.

## Airport Assets (single-player) — shipped 2026-06-20

Player-facing investment layer adapted from `patsonluk/airline`, gated by `solo.airportAssets.enabled`
(enabled in the OptiPlex deploy, sim + web). See `docs/airport-assets.md` for the full design,
catalog, config knobs, and integration points. Highlights:

- Build assets at airports where you hold a **base**; multi-cycle construction; build/upgrade one
  level at a time; sell for half the invested cash.
- Per-type economics: **revenue** assets boost `INCOME` + earn modest income; **attraction** assets
  boost a hub type (`VACATION/FINANCIAL/INTERNATIONAL_HUB`) + small income; **infrastructure** (Metro)
  is a pure `POPULATION` boost with **no income**. All carry weekly upkeep — a self-limiting cash sink
  whose real payoff is the demand it creates at your fortress markets.
- The demand-boost half **reuses the existing `AirportBoostContributor` pipeline** (the hard part was
  already in the code via base specializations); assets feed a new `assetBoostFactors` map on `Airport`.

**Operational gotcha (important for future schema additions):** this codebase only runs
`Meta.createSchema` on a fresh init, so a new table is **absent on the existing OptiPlex DB**. The
first deploy crashed app startup (`Table 'airline.airport_asset' doesn't exist`) because the airport
load queried it. Fixed by the `HeartbeatSource` pattern: `AirportAssetSource.ensureTable()`
(idempotent `CREATE TABLE IF NOT EXISTS`) called by the DAO, plus gating the airport-load query on
the feature flag. **Any future feature that adds a table must self-create it this way (or run a
manual migration on the live DB) — do not rely on `createSchema`.**

## Asset Decision Support — shipped 2026-06-20

Player QOL layer on the airport screen so assets are legible and decidable. Spec/plan:
`docs/superpowers/specs/2026-06-20-asset-decision-support-design.md` (+ matching plan). Live-validated
on the OptiPlex via Playwright (logged in as Lash Air at LAX; screenshots reviewed; no JS errors).

- **Asset benefit/ROI + imagery:** each catalog row shows the asset artwork (29 PNGs vendored from
  `patsonluk/airline`, Apache 2.0, credited in root `NOTICE`) and a tooltip with the plain-language
  benefit, weekly upkeep, and — for revenue assets — income/net/payback. `AirportAssetType` gained
  `image`/`benefit`; pure `netWeekly`/`paybackCycles` helpers (payback only for net-positive revenue
  types). Always-paired with the assets section (flag-gated by `solo.airportAssets.enabled`).
- **Traffic Analytics (always-on, no flag):** `GET /airports/:id/traffic-analytics` returns an airport
  summary (transfer% vs direct, premium%, passenger-type demographic mix) and a per-route table
  (volume, transfer%, premium%, **per-route demographics**), whole-market across all airlines.
  Rendered in a new airport-panel section above the assets section.
- **Data sources:** transfer/volume/premium from `link_statistics` (per arriving leg, via pure
  `AirportTrafficStats`); demographics from `passenger_route_history`. **Per-route demographics use an
  accurate per-leg join** (`passenger_link_history → link → passenger_route_history`, keyed by the
  arriving leg's origin) — the first attempt used an O-D grouping that came back empty for
  high-transfer hub feeders; the per-leg join (driven off `idx_link_history`) fixed it. No schema
  changes; demographics reflect the ~30-week history retention.

## Air Cargo C-1 (cargo demand layer) — shipped 2026-06-20 (local commit, not yet deployed)

First slice of the Air Cargo feature. Release scope was decided as **C-1 + C-2 only, read-only
UI** (no Cargo Terminal asset / no freighters this release). Full executable plan lives at
`C:\Users\logan\.claude\plans\glittery-finding-zebra.md`; design background in
`docs/air-cargo-plan.md`.

**C-1 is committed locally as `f9144132` on `master` — NOT pushed, NOT deployed.** It has **no
gameplay effect**: it only computes the cargo demand model and logs a per-cycle inspection summary.

What shipped (all in `airline-data`):
- `data/SoloConfig.scala`: `solo.cargo.{enabled,demandAmplitude,captureRatio,revenuePerUnitKm}`,
  default off so default/multiplayer deploys are byte-identical. `captureRatio` /
  `revenuePerUnitKm` are unused until C-2 (belly revenue).
- New `CargoDemandGenerator.scala`: pure, deterministic per-directed-pair weekly cargo-units model
  — gravity geo-mean of each end's economic mass (`population * income`), affinity-weighted, with a
  sub-400 km trucking fade, scaled by `cargoDemandAmplitude`. Index-keyed per-cycle memo cache with
  fingerprint invalidation (mirrors `DemandGenerator.prepareBaseDemandCache`). `summarizeCycle`
  returns the inspection line.
- `DemandGenerator.computeDemand`: flag-gated `println(CargoDemandGenerator.summarizeCycle(...))`,
  reusing the airports + country relationships already loaded (no second airport load).
- `CargoDemandGeneratorSpec.scala`: 8 DB-free tests, all passing
  (`sbt "testOnly com.patson.CargoDemandGeneratorSpec"`). Verified determinism, monotonicity vs.
  economic mass, affinity weighting (domestic 83 vs foreign 8 on the synthetic pair), zero-income
  gating, and cache full-reset/incremental-eviction.

Calibration note: `CARGO_BASE_DIVISOR = 2.0e9` was picked so a major synthetic pair yields ~tens-
to-hundreds of weekly units; **real magnitudes are unverified** against the live airport dataset.
First time `-Dsolo.cargo.enabled=true` runs on real data, read the `[cargo] demand summary` log line
and tune `CARGO_BASE_DIVISOR` (constant) / `solo.cargo.demandAmplitude` (knob) so totals are sane
before C-2 turns demand into revenue.

### Next session: pick up at Air Cargo C-2 (belly cargo revenue — the playable increment)

Follow phase C-2 in `glittery-finding-zebra.md`. Summary of what's left:
1. **Belly capacity (C-2.1):** add a *derived* `bellyCargoCapacity` helper on `Model`
   (`model/airplane/Model.scala:26`) from existing fields (seats + range) — **no new Model DB field**
   (avoids a model-table migration). `Model` currently has no cargo/belly/weight field.
2. **Cargo revenue (C-2.2):** in `LinkSimulation.computeLinkAndLoungeConsumptionDetail`
   (`LinkSimulation.scala:315`, passenger revenue at line 368, profit at 402), add a
   `SoloConfig.cargoEnabled`-gated term: `carried = min(spareBelly, pairDemand * captureRatio)`,
   `cargoRevenue = carried * distance * revenuePerUnitKm`. Needs a per-cycle
   `CargoDemandGenerator` lookup by airport (the cache is in place; add a `demandFor(from,to)` style
   accessor — note `summarizeCycle` already populates the cache each cycle).
3. **Persist (C-2.3/C-2.4):** append `cargoRevenue: Int = 0` to `LinkConsumptionDetails`
   (`model/LinkConsumptionResult.scala:3`) and add a `cargo_revenue` column to `link_consumption`.
   **Existing-DB gotcha:** `Meta.createSchema` only runs on fresh init and MySQL 8 has no
   `ADD COLUMN IF NOT EXISTS` — add an information_schema-guarded `ALTER TABLE` (follow the
   `AirportAssetSource.ensureTable()` precedent). Update the INSERT (`LinkSource.scala` ~785) and
   SELECT (~965).
4. **Ledger (C-2.5):** append `CARGO_REVENUE` to `LedgerType` (`model/Airline.scala:210-248`,
   **append only** — ordinals are persisted). Record it in `AirlineSimulation.scala` (aggregate
   ~91-100, ledger ~216); recommend subtracting cargo from the flight-revenue ledger total so the
   income statement doesn't double-count (plan C-2.5 option b).
5. **Read-only UI (C-2.6):** add `cargoRevenue` to `LinkConsumptionFormat`
   (`airline-web/.../LinkApplication.scala:54-89`) and a "Cargo revenue" line in the link-income JS;
   `CARGO_REVENUE` needs an income-statement label like other `LedgerType` values.
6. **Deploy (after C-2):** enable `-Dsolo.cargo.*` in **both** `SIM_SOLO_OPTS` and `WEB_SOLO_OPTS`
   in `.github/workflows/optiplex-deploy.yml`, then deploy. C-1's flags are NOT yet in the workflow.

Reminders for the next session: run `airline-data` `sbt publishLocal` before compiling `airline-web`;
this checkout is the Desktop one (`C:\Users\logan\Desktop\Airline\airline`), git repo lives in the
`airline/` subdir; C-1 commit `f9144132` is local on `master` and still needs pushing/deploying
(can be folded into the C-2 deploy).

## 2026-06-21 — Cargo data surfacing, airport mobile UX, DB pool fix (shipped + deployed)

All on `master`, deployed to OptiPlex (`airline.ashhome.org` → `192.168.1.52`), CI green. Air Cargo
C-1..C-4 (belly revenue, freighters, Cargo Terminal, expense split) are all shipped now — the
"Air Cargo C-1/C-2" sections above are historical.

**1. Cargo decision-data surfacing + flights/office polish** (commits `7e9909b4`..`6f2d9424`)
- Aircraft market + hangar show cargo capacity: new **Cargo** column + detail line
  `Freighter X t / Belly Y t`. Serialized `bellyCargoCapacity` in `package.scala`
  `AirplaneModelWrites` (`freighterCargoCapacity` was already there). `formatCargoTons()` in
  `airplane.js` renders `—` instead of `NaN` if a stale payload lacks the field.
- Route planner: `Capacity vs demand (~N% fill)` row (`updatePlanCapacityVsDemand` in `airline.js`),
  pax + cargo.
- Flights list: Load Factor / Profit / Margin color-coded (`.positive`/`.negative` in `main.css`).
- Office income sheet: net + operating income colored by sign, cargo revenue shown as % of total,
  subtotal/total separator rule.
- **GOTCHA (cost us a NaN bug):** `/api/<ver>/airplane-models` (`AirplaneApplication.getAirplaneModels`)
  is cached **4 weeks, public, keyed by `currentApiVersion`**. Adding/removing a model-JSON field
  REQUIRES bumping `currentApiVersion` (`airline-web/.../controllers/package.scala:23`), or browsers
  serve the stale cached payload. Bumped `v5.1.2 → v5.1.3` this session. Always bump on model-schema
  change.

**2. Airport page mobile UX** (spec `docs/superpowers/specs/2026-06-21-airport-mobile-ux-design.md`,
plan `docs/superpowers/plans/2026-06-21-airport-mobile-ux.md`; commits `58dbd81`..`ec4c5dc4`)
- Asset detail modal `#airportAssetDetailsModal`: tap any built-asset/catalog row → prominent image +
  benefit/ROI/payback + one large Build/Upgrade/Sell button. Inline table buttons removed.
  `openAssetDetailsModal(descriptor)` in `airport.js`; rows build the descriptor.
- Airport tables scroll horizontally on mobile (≤640px): `#airportCanvas .table.data` →
  `display:block; overflow-x:auto` while rows/cells stay `table-row`/`table-cell` (browser wraps them
  in one anonymous table → content-sized, aligned columns), capped at `calc(100vw - 24px)` so wide
  tables scroll internally and narrow ones don't. **Lesson:** these are CSS-table layout — `min-width`
  on `table-row` and `overflow-x` on `display:table` are ignored (the first two attempts failed on
  this); and the global `mobile.css .table.data .cell` rule uses `!important`, so airport overrides
  must too.
- `abbreviateMoney()` in `gadgets.js` (Jest-tested) → `$1.2M`/`$340K`; `formatAssetMoney()` gates by
  viewport. `e2e/tests/airport-mobile.spec.ts` is part of the deploy verify suite.

**3. DB connection-pool exhaustion fix** (commit `23359d62`) — PRODUCTION INCIDENT
- Symptom: Flights page empty (header, **no rows and no "no routes" tip**) + intermittent HTTP 500s
  (login, etc.); "refreshes don't fix." The flights loader only renders on AJAX success, so a 500 on
  `/links-details` leaves the header with nothing — that was the tell.
- Root cause: Hikari pool (max **10**) exhausted — `SQLTransientConnectionException ... Connection is
  not available` + `Apparent connection leak detected` in `web.log`. `AirlineSource.loadAirlinesByQueryString`
  held its read connection across enrichment sub-loaders (`loadAllianceMemberByAirlines`,
  `loadAirlineBasesByAirlines`, `loadAirlineStatsForAirlines`), each of which takes its own connection
  → **two pool connections held at once** → exhaustion under concurrency. The
  `loadAirlineStatsForAirlines` (reputation/stats) enrichment in this hot path was the tipping point.
- Fix: scope the read connection to just the query; run enrichment after it is released
  (`airline-data/.../data/AirlineSource.scala`). Immediate relief via `docker restart airline-app`;
  durable fix deployed.
- **Ops notes:** OptiPlex app log is **in-container** at `/home/airline/web.log` (NOT `docker logs`,
  which only shows the supervisor wrapper). SSH: `ssh -i ~/.ssh/airline_optiplex_ed25519 root@192.168.1.52`.
  Containers: `airline-app`, `airline-db`, `airline-cloudflared`.

### Next steps
- **Airport Cargo Demand panel** (backlog in `docs/air-cargo-plan.md`): `/airports/:id/demand`
  (`Application.computeAirportDemandJson`) is passenger-only; needs a per-airport cargo-demand
  aggregate + demand-JSON field, then extend `renderDemandCards` in `airport.js`. Deferred from the
  airport pass (was out of "no new backend endpoint" scope).
- **DB resilience follow-up:** the nested-connection pattern likely exists elsewhere — grep
  `airline-data` for `Meta.getConnection()` calls made inside another `getConnection` block and apply
  the same "release before sub-loaders" fix. Consider bumping Hikari `maximumPoolSize` above 10 as
  defense-in-depth (see `HIKARI_TUNING.md`); not done this session.
- **Minor (logged):** airport asset catalog button can read "Max level" while the reason text says
  build-base-first — an unreachable combo, left as-is.

## Suggested Next Feature Phase

- **Air Cargo C-1..C-4 are all shipped/deployed.** Remaining cargo work is the Airport Cargo Demand
  panel (see Next steps above) and any economy tuning.
- Tuning backlog: the `solo.airportAssets.*` cost/upkeep/income multipliers and `solo.ai.bases.*`
  knobs can be adjusted live once playtest shows how the cadence/economy feel.
