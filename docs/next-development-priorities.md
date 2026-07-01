# Next Development Priorities

Last updated: 2026-07-01

Quick reference for future agents. Read `docs/current-development-state.md`,
`docs/roadmap-current-state-audit.md`, and
`docs/decision-clarity-and-cargo-polish-2026-07.md` for fuller context.

## Do Not Build

- **Pause-when-idle / skip-cycle-when-idle**: abandoned by product decision. Never enable
  `simulation.pauseWhenIdle`.
- **Reactive AI competition**: deferred. Existing AI growth/world-news systems may be tuned, but do
  not add reactive player-targeting AI in the next pass.
- **Disruption/event code**: deferred. A design doc exists at
  `docs/disruption-event-recovery-design.md`; implementation is future work.
- **Cargo contracts / SLA economy**: design first, no implementation until the design is reviewed.

## Recently Completed

### Decision clarity and cargo polish - DONE 2026-07

- Route forecast now returns and renders open/wait/avoid-style recommendation, recommendation
  severity, competition count/frequency summary, confidence explanation, cargo share, and aircraft
  recommendation reason.
- Cargo opportunities now sort by a blended score and show readable yield, estimated profit/profit
  band, aircraft/freighter recommendations, reason text, and risk text.
- Network-wide cargo market overview exists on the Office page and at
  `GET /airlines/:id/cargo-market-overview`.
- Freighter-only revenue lever exists as `solo.cargo.freighterRevenueMultiplier` with default `10.0`;
  passenger belly cargo is unchanged.
- Advisor recommendations now use the existing consultant/executive level model and render grouped
  state-based recommendations for Fleet, Routes, Cargo, and Airport assets.
- Playwright/Jest/Scala coverage was expanded for the touched surfaces.

## Next Real Work

### A. Balance telemetry after cargo polish

- Validate belly cargo remains a secondary revenue source: target 2-8% for mixed carriers, up to
  roughly 20% for cargo-focused players.
- Validate freighter lanes after the `10.0` multiplier: short/medium freighter routes should become
  plausible, not automatic wins.
- Check `link_consumption.cargo_revenue`, route-level profit, and player use of cargo opportunities
  after deploy.
- Tune `solo.cargo.freighterRevenueMultiplier` or `solo.cargo.demandAmplitude` only after observing
  real route outcomes.

### B. CI and runtime E2E verification

- The expanded Playwright suite now covers route forecast, cargo opportunities, cargo market overview,
  advisor recommendations, plan-route prefill, and mobile-width smoke checks.
- Local Windows E2E was blocked because no app was listening on `localhost:9000`; earlier DB-backed
  tests also showed local MySQL unavailable on `localhost:3306`.
- Run the full suite in CI or against a safe local/dev runtime that does not enable
  `simulation.pauseWhenIdle`.
- Keep tests visible-flow based where practical; avoid reaching through hidden ancestors or direct JS
  internals unless the page cannot be booted.

### C. Cargo contracts design

- Not ready to implement. Write a design doc covering contract types, volume/SLA obligations,
  penalties, UI surfaces, and how contracts avoid making cargo a pure passive bonus.

### D. Disruption/event recovery implementation

- Design-only doc exists: `docs/disruption-event-recovery-design.md`.
- First implementation should be aggregate airport/route reliability penalties, surfaced through
  World News, bounded by config, time-limited, and automatically recovered.
- Do not model per-flight delays/cancellations unless the simulation gains flight instances first.

### E. AI/world-news balance checks

- Confirm `world_news` receives useful AI/base/route events.
- Tune existing `solo.ai.*` growth/base-expansion knobs only after observing cadence.
- Do not implement reactive AI competition in this phase.

## Useful References

- Compile: `cd airline-data && sbt publishLocal`, then `cd ../airline-web && sbt compile`
- DB-free targeted tests:
  `cd airline-data && sbt "testOnly com.patson.CargoAllocationSpec com.patson.CargoMarketVisibilityServiceSpec com.patson.ConsultantAdvisorSpec com.patson.RouteForecastLogicSpec"`
- Frontend tests: `cd airline-web && npm test -- --runInBand`
- E2E tests: `cd e2e && npm test` with a running app at `BASE_URL` or `http://localhost:9000`
- DB-backed tests: `sbt "testOnly com.patson.RouteForecastServiceSpec"` needs MySQL; rely on CI if
  local MySQL is unavailable.
- Deploy: push to `master` and watch GitHub Actions.
