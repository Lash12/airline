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

## Suggested Next Feature Phase

- **Air Cargo** — see `docs/air-cargo-plan.md`. Model cargo as a parallel demand layer reusing the
  existing demand/link/aircraft machinery; start with belly cargo on passenger links, then a
  "Cargo Terminal" airport asset (the natural bridge to the assets feature), then optionally
  dedicated freighters. Gated behind a new `solo.cargo.*` flag like every prior solo phase.
- Tuning backlog: the `solo.airportAssets.*` cost/upkeep/income multipliers and `solo.ai.bases.*`
  knobs can be adjusted live once playtest shows how the cadence/economy feel.
