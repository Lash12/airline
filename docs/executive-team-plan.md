# Executive Team — design plan

Status: **Phases 0–2 implemented (gated `solo.exec.enabled`, default off); not yet deployed.**
Captured 2026-06-25. A single-player feature layering a small C-suite on top of the existing Manager
system. Phases 1–3 stand alone and ship value on their own; the "autopilot" tier (Phase 4) is
deliberately deferred and uncertain — revisit only after 1–3 are play-tested.

Implementation status:
- **Phase 0 (data + read-only panel): DONE.** `Executive` model + `ExecutiveRole`, `executive` table
  (self-creating `ExecutiveSource`), `EXECUTIVE_TABLE`, `solo.exec.enabled`, read-only Office panel.
- **Phase 1 (hiring + salary + buffs): DONE.** `ExecutiveBuffs` (CFO fuel −4%/lvl, COO maintenance
  −4%/lvl, CCO +1 rec/lvl), `ExecutiveCache`, `LedgerType.EXECUTIVE_SALARY` weekly debit, reputation-
  gated appoint/dismiss, interactive panel. Unit-tested (`ExecutiveBuffsSpec`).
- **Phase 2 (leveling by performance): DONE.** `ExecutiveProgression` — seats earn 1 xp/cycle when
  their domain KPI is good (CFO profit, COO on-time, CCO load factor); level = 1 + xp/4, cap 5; higher
  level → stronger buff + higher salary next cycle. Wired in `AirlineSimulation`. Unit-tested
  (`ExecutiveProgressionSpec`). xp/next-level shown in the panel.
- **STOP POINT: evaluate Phases 1–2 in play before Phase 3 (traits) / Phase 4 (autopilot, deferred).**

## Premise

The game already separates **labor** (Managers) from **strategy**. Managers are a fungible currency:
you earn more as reputation rises and assign them to tasks (`ManagerTaskType`: COUNTRY, CAMPAIGN,
MANAGER_BASE → action points, MANAGER_AIRCRAFT_MODEL, CONSULTANT). They level by *tenure in a task*
(Trainee → Novice → Junior → Senior → Director).

Executives are the **strategy layer above managers**: a small, persistent, named C-suite where each
seat governs one operational domain. Inspiration is *Airport CEO*'s staffing/role feel — but
deliberately **not** its micro. No individual agents walking around, no morale minigame, no
hire/fire churn at the core. A handful of seats, each a legible lever with identity.

The design principle throughout: **execs multiply what managers already do**, rather than adding a
parallel system. This keeps the feature additive and cheap.

## Fit with existing systems

| Exec seat | Governs | Existing system it hooks |
|-----------|---------|--------------------------|
| **CFO** | Finance: loan rate, fuel cost fraction, dividends | `airline_ledger`, fuel cost in `LinkSimulation` |
| **CCO** | Commercial: pricing power, demand capture, route-advice depth | `link` pricing, `ConsultantAdvisor` / `ConsultantManagerTask` |
| **COO** | Operations: maintenance cost, delay/cancellation rate, condition decay | `airplane.airplane_condition`, delay counts in `link_consumption` |
| **CMO** | Marketing: campaign/ad effectiveness, reputation gain | `CampaignManagerTask`, reputation |
| **CHRO** | Service: service-quality ceiling, lounge effect, **manager leveling speed** | `service_quality`, lounges, `LevelingManagerTask` |
| **CSO** | Network: base/route/country expansion advice | `CountryManagerTask`, expansion / market overview |

Phase 1 ships only the three seats that map onto the most common pain points (CFO, CCO, COO). The
rest are reserved for later (no schema cost to add them — `role` is just an enum value).

### Worked example — Island Air (id 82)

A real solo airline on the OptiPlex deploy: HNL hub, ~$25M cash, near break-even (net per cycle
swings roughly −39k…+28k). Its problems are exactly the three Phase-1 domains:
- **CFO** would surface/relieve the fuel drag (~$911k/cycle fuel + tax) and flag the Tokyo route
  losing $74k/cycle at 96 % load (underpriced, not under-filled).
- **CCO** would deepen route/pricing advice (reprice HND, kill 0-pax MUE/HNM).
- **COO** would flag the A350 at 41.5 % condition dragging its best route's satisfaction to 0.51.

It also can't afford a full C-suite at $25M — which is the point: **salary makes the roster a real
choice for a struggling airline**, not a free buff.

## Gating & safety (applies to every phase)

- All behavior behind `solo.exec.*` keys in `SoloConfig`, **default off**, so default/multiplayer
  deploys are byte-identical. Same pattern as `solo.consultant.*`, `solo.ai.*`.
- **Single-player QoL only.** AI/NPC airlines never get a C-suite (consistent with the consultant).
  No AI-parity work.
- **Cycle budget.** All exec effects are cheap multipliers computed once per cycle in
  `AirlineSimulation`, never a per-frame or per-link sub-sim. The sim has no process manager — toggle
  via deploy env, never hand-restart.
- New tables via `SchemaPatchRunner` / `schema_patch` (see `docs/database-migrations.md`).

---

## Phase 0 — Foundation (data only, no gameplay change)

Deployable "dark": tables + CRUD + a stubbed panel, zero effect on the sim.

- **Schema** (`schema_patch`):
  - `executive(id, airline, role, level, xp, hired_cycle, trait, salary)` — one row per filled seat.
  - `role` is an enum (CFO/CCO/COO/CMO/CHRO/CSO); `trait` nullable (used in Phase 3).
- **`SoloConfig`**: `solo.exec.enabled` (master, default false) + per-role unlock/salary/buff knobs.
- **`ExecutiveSource`** (data layer, mirrors `ManagerSource`): load/save/delete.
- **`model/Executive.scala`**: case class + `ExecutiveRole` enum + buff descriptors (pure, unit-testable).
- **Web**: read-only Executive panel stub on the Office page (mirrors the consultant card).

Exit criteria: tables migrate cleanly, roster CRUD works, nothing changes in the sim.

## Phase 1 — Hiring & passive buffs (the core loop)

The minimum that is actually fun: appoint execs, pay them, feel the domain improve.

- **Seats unlock by reputation** (knob-driven thresholds), so a new airline starts with 0–1 seats
  and earns the rest — reuses the existing "more managers as reputation rises" feel.
- **Appoint to a seat**: pick a role; exec starts at level 1. (Phase 1 uses simple appointment, not a
  candidate market — that's Phase 3.)
- **Salary** = recurring `airline_ledger` debit each cycle, scaling with level. Computed in
  `AirlineSimulation` alongside the existing financial/notification step.
- **Passive domain buffs** (small, bounded, knob-tuned multipliers, applied at cycle):
  - **CFO**: −x % effective loan interest and/or fuel cost fraction.
  - **CCO**: deepen `ConsultantAdvisor` output (more/earlier recommendations) + small demand-capture
    nudge. (The consultant's existing advice is effectively the CCO's level-1 output — this seat
    gives that engine a home and a spine.)
  - **COO**: −x % maintenance cost and/or slower condition decay.
- **Web**: Executive panel becomes interactive (appoint, view buff + salary, see locked seats).

Design intent: every buff must be felt but never dominant; the salary should make a marginal airline
weigh "which seat first?" Tuning lives entirely in `solo.exec.*` so it can be balanced live.

Exit criteria: a player can appoint CFO/CCO/COO, see salary on the ledger, and measure the buff.

## Phase 2 — Leveling by performance

Execs level differently from managers **on purpose**: managers level by tenure, execs by **how well
their domain performs under them** — making them feel responsive to play.

- XP accrues from domain KPIs each cycle:
  - **CFO**: positive net-income cycles.
  - **COO**: low delay/cancellation rate, high average fleet condition.
  - **CCO**: high average load factor / yield.
- Level up → stronger buff and higher salary (so power growth has an ongoing cost).
- Reuse `airline_statistics` / milestone infra where the KPI already exists; avoid new per-cycle work.

Exit criteria: an exec visibly levels from good results in its domain, and the salary tracks up.

## Phase 3 — Traits & specialization (build identity)

The replay/identity layer. Pure flavor + tuning; no new core systems.

- On hire/appoint, an exec carries a **trait** = a tradeoff buff, e.g.
  - *Yield Hawk* (CCO): stronger pricing, weaker satisfaction.
  - *Penny Pincher* (CFO): lower costs, slower growth/expansion appetite.
  - *Road Warrior* (COO): better reliability, higher salary.
- Optional **hiring market**: a small rolled candidate pool (varied role/level/trait/salary ask)
  instead of plain appointment — adds a real "who do I hire?" decision.
- Everything here is data + multipliers; no sim-architecture changes.

Exit criteria: two airlines with the same seats but different traits play noticeably differently.

---

## Phase 4 — DEFERRED / uncertain: Advisor → Delegate → Autopilot

> Captured for completeness only. **Do not build until Phases 1–3 are play-tested** and the rest of
> the design feels right. Listed last because the value is least certain and the risk highest.

The idea: an exec's authority grows with skill.
- **Advisor** (today's consultant): suggests, never acts.
- **Delegate**: at mid level, may auto-execute a *bounded, reversible* action with player opt-in —
  e.g. close a persistent 0-pax route, nudge price on a full-but-losing route. Could reuse the NPC
  primitives already built (H-2 price-tune, AI drop) but applied to the *player* with consent.
- **Autopilot**: at high level, runs a domain within guardrails.

Open questions to resolve before committing:
- How much agency is fun vs. removing the game? (Risk: autopilot plays the game *for* you.)
- Consent model: per-action confirm, standing policy, or undo log?
- Separate gate (`solo.exec.autopilot.enabled`) so it can ship — or never ship — independently.

Decision: **leave open.** Phases 1–3 deliver a complete feature without it.

---

## Risks & non-goals

- **Scope creep** — the full suite + traits + market + autopilot is large. Phasing is the mitigation;
  ship Phase 1 thin.
- **Not Airport CEO micro** — no morale meters, no individual staff churn, no agents at MVP.
- **AI parity** — explicitly out of scope; solo-only, AI ignores execs.
- **Perf** — effects must stay O(airlines) per cycle; no per-link exec math.

## Rollout

Each phase: feature-flagged, merged to `master`, auto-deployed by the OptiPlex "Deploy & Verify"
workflow, validated on the live deploy (Island Air is a convenient test airline), tuned via
`solo.exec.*` deploy env without code changes.
