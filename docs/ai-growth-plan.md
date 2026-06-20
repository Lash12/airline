# Phase H — Living-World AI Growth

## Context

The single-player world is currently **static-to-shrinking**. `ComputerAirlineSimulation`
(gated `solo.ai.enabled`, already live) is deliberately **drop-only**: each cycle a bounded,
rotating subset of NPC airlines cancels its worst persistently money-losing route. Nothing
in the sim makes NPCs *grow* — they never open routes, adjust prices/frequency, or renew
fleets. The practical effect, confirmed in playtest: nearly every route a player scouts is
**greenfield with no competition**, so "find an uncontested market and dominate it" is
effectively the whole mid/late game. The world doesn't push back.

The **World News feed** (Phase "N", shipped: `WORLD_NEWS` notification category +
`WorldNews.post`, a News page, gated `solo.news.enabled`) is the visibility layer this phase
builds on. It already surfaces NPC route **drops**; this phase makes it surface **growth**
and competitive moves, which is what makes the feed — and the game — come alive.

Design rule (unchanged from every prior solo phase): gate behind a new `solo.*` flag
defaulting to upstream behavior, so multiplayer/default deploys stay byte-identical.

---

## Goal

Make NPC carriers a **living competitive force** so the map evolves over time and long-term
planning matters — **without turning the game into per-cycle busy-work.**

The north star (from the design discussion): deliver **strategic pressure, not tactical
churn.** The map should change slowly and legibly; the player engages with competition as
much or as little as they want, reacting on their own terms, never forced into per-cycle
whack-a-mole.

---

## Design principles (the anti-busy-work guardrails)

These are first-class requirements, not nice-to-haves. The feature fails if it makes the
game feel like a chore.

1. **Slow, bounded cadence.** Reuse the existing per-cycle rotating-subset design. Growth is
   capped at a small number of NPC actions per cycle and **≤1 new route per NPC per cycle**,
   tuned to a "notice something changed every few sessions" pace — not every week.
2. **Reaction is optional, never forced.** Competition on a route lowers the player's
   share/load factor; it must **not** nuke them. The player chooses which markets to defend
   (frequency, price, quality) and which to cede and redeploy. Ignoring it is a valid
   strategy, not a punishment.
3. **News is pull, not push.** Ambient world events go to the **News page** (player
   references on their terms). Only escalate to a real notification when a competitor enters
   **the player's own** route — and even then it is informational.
4. **Coarse, legible behavior over random noise.** Prefer an NPC visibly building a cluster
   ("focusing on the Southeast US") over scattered per-cycle open/drop churn. Narratives the
   player can read in the feed, not noise they must chase.
5. **Self-limiting economics.** Growth requires a real projected-profit threshold + spare
   aircraft + cash, so it throttles itself; a hard network-size ceiling per NPC prevents
   runaway expansion.
6. **Cheap per cycle.** Candidate evaluation stays bounded and reuses cached demand/profit
   estimation (never sweep all airports). This is good engineering regardless; note the
   OptiPlex AC-adapter fix (2026-06-17) lifted the 800 MHz clamp, so cycle budget is no
   longer a hard constraint — but the phase stays cheap on principle.

---

## Approach (incremental, one gated PR per step)

Everything gates behind **`solo.ai.growth.enabled`** (default `false`), independent of the
existing `solo.ai.enabled` (drops). New tuning knobs live in `SoloConfig` next to the
existing `ai*` block.

### H-1 — Route opening (the core increment)

Mirror the existing drop logic with an "open one good route" path. For each acting NPC
(same rotating subset), when growth is enabled and the NPC has spare capacity:

- Build a **small candidate set**: high-demand, underserved airports reachable from the
  NPC's **existing bases** (cap the candidate count, e.g. top-N by a cheap demand proxy).
- Estimate profit for the best candidate using the **existing demand/profit estimation**
  (reuse what the player route-planner and `LinkSimulation` already compute; do not invent a
  new model).
- Open the single best route **only if** projected profit clears a threshold **and** the NPC
  has a spare airplane + enough cash, respecting a per-NPC network-size ceiling.
- Emit a `WORLD_NEWS` item via `WorldNews.post` ("Rival X opened its A–B route") with
  `targetId = rival_{airlineId}`.

New `SoloConfig` knobs (all defaulted to current/no-op): `aiGrowthEnabled`,
`aiMaxOpensPerAirline` (default 1), `aiOpenProfitThreshold`, `aiCandidateLimit`,
`aiMaxNetworkSize`.

**Out of scope for H-1:** buying aircraft for the NPC (only open if a spare frame exists),
new bases, price wars. Keep the first slice minimal and observable.

### H-2 — Price / frequency tuning (after H-1 proves stable)

Let acting NPCs nudge price or frequency on **existing** links toward profitability/load
factor, bounded per cycle (small step, capped). This is where competition starts to *feel*
adaptive without new-route churn.

### H-3 — Fleet renewal

Let NPCs replace aged/retiring aircraft (and buy a frame to enable an H-1 open when cash
allows), bounded. Prevents NPC networks from quietly decaying to nothing over long games.

### H-4 — New bases (most conservative; revisit last)

Allow a thriving NPC to open a new base occasionally, enabling regional expansion. Hard caps;
only after H-1–H-3 feel right in playtest.

Status: **implemented and live** (2026-06-20) as `ComputerAirlineBases` behind
`solo.ai.bases.enabled` (default false). A thriving NPC promotes a city it already serves into a
new scale-1 base, re-homes one fully-idle owned frame there, and launches that frame's best
profitable first route in the same cycle (reusing `ComputerAirlineGrowth.bestRouteFromBase`); H-1
then grows the cluster on later cycles. Self-limiting: ≤1 base opening/cycle across all NPCs
(`solo.ai.bases.maxOpeningsPerCycle`), per-NPC ceiling (`maxBasesPerAirline`), real ledger-charged
construction cost with a cash cushion (`cashCushion`), and a profitable first route required.
Enabled in the OptiPlex deploy. **All five H-phases (H-1…H-5) are now shipped.**

### H-5 — (optional) Coarse "strategy" layer

Give each NPC a lightweight, persistent bias (e.g. a focus region/market type) so its opens
cluster into a legible narrative rather than independent decisions. Cheap: a derived or
stored per-airline tag, no heavy planning.

Status: implemented as a deterministic `ComputerAirlineStrategy` scoring bias behind
`solo.ai.strategy.enabled` (default false) with `solo.ai.strategy.maxBonus` controlling the
route-open preference strength. The small-server profile enables it for solo play.

---

## Critical files

- `airline-data/.../data/SoloConfig.scala` — add `aiGrowth*` knobs.
- `airline-data/.../ComputerAirlineSimulation.scala` — add the gated growth path alongside
  the existing drop path; reuse `playerIds`/`WorldNews.post`.
- `airline-data/.../WorldNews.scala` — reused as-is for `AI_ROUTE_OPENED`-style items.
- `airline-data/.../LinkSource` / `LinkSimulation` / demand estimation — **reuse** for
  candidate profit estimation; do not duplicate the model.
- `airline-data/.../AirlineSource` — load NPC bases / spare aircraft cheaply.
- `airline-web` — no required change for H-1 (news already renders); later, a "Rivals" or
  News filter could highlight competitive moves.

## Verification

- **Unit (airline-data spec):** with growth enabled, an NPC with a profitable candidate +
  spare frame + cash opens exactly one route/cycle, respects the network ceiling, emits one
  news item; with growth disabled or no profitable candidate, opens nothing; players are
  never touched.
- **Economic-stability check:** run N cycles with growth on; assert total NPC network size
  stays bounded, cycle time stays within budget, and the player's economy isn't destabilized.
- **Live:** enable `-Dsolo.ai.growth.enabled=true` in the deploy `SIM_EXTRA_OPTS`;
  fast-forward several weeks; confirm the News feed shows occasional, legible NPC openings;
  confirm cadence feels "check in every few sessions," not nagging; confirm the player can
  ignore it without being punished.
- **Ops:** every flag toggled via the deploy workflow; default/multiplayer deploys are
  byte-identical when `aiGrowthEnabled` is off. Ship as PRs to master (compile-gate the
  airline-data changes on a branch first, as with Step E / Phase G / Phase N).

## Out of scope (future)

- Player-facing competitive AI difficulty settings / scenario mode.
- Sophisticated long-horizon NPC planning or game-theoretic pricing — keep heuristics cheap
  and bounded.
- The 800 MHz CPU throttle — resolved 2026-06-17 (hardware/AC-adapter fault, since fixed);
  no longer a constraint on this phase.

## Bundled cleanup

The **`/news/` cold-load fix already shipped (#69)**, so there's nothing outstanding to
bundle right now. Still, fold any *new* small mobile papercuts into the first H-1 deploy to
avoid extra restarts (the sim is on a single self-hosted instance and every deploy logs the
player out).
