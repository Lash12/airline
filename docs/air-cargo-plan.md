# Air Cargo — design plan (stretch goal)

Status: **not started** (design only). Captured 2026-06-20 as the next feature after Airport Assets.

## Goal

Add a cargo dimension to the single-player game without building a second economy. Today the sim is
passenger-only: demand, links, aircraft, and revenue are all about people. Air Cargo should reuse
that machinery — cargo is "another kind of passenger demand" carried in the belly of existing
flights and, later, on dedicated freighters — so it deepens network strategy (freight hubs, route
mix, aircraft choice) rather than bolting on a parallel system.

Design rule (unchanged from every solo phase): gate behind a new `solo.cargo.enabled` flag (default
false) so default/multiplayer deploys stay byte-identical, and keep each increment a small, shippable,
compile-gated PR.

## North star

Cargo is **strategic texture, not micro-management.** The player shouldn't manage cargo per flight;
belly cargo should "just happen" on routes with cargo demand, surfacing as extra revenue and a reason
to favor certain markets/aircraft. Dedicated freighters are an opt-in advanced play, not a requirement.

## Phasing (incremental, one gated PR per step)

### C-1 — Cargo demand layer
Compute a per-airport-pair **cargo demand** figure, mirroring `DemandGenerator`'s structure but
driven by economic mass (population × income, with a different curve) rather than passenger
propensity. Likely a `CargoDemandGenerator` (or an added output of `DemandGenerator`) that produces a
weekly cargo-units demand per directed pair, cached/memoized like base passenger demand. No gameplay
effect yet — just the model + a way to inspect it. **Investigate:** whether to fold into the existing
demand memoization (`solo.demand.memoize`) to avoid a second expensive sweep.

### C-2 — Belly cargo on passenger links (the core increment)
Each passenger flight has spare belly capacity (a function of aircraft model + pax configuration).
Carry cargo up to `min(spare belly capacity, cargo demand on that pair)` and add the revenue to the
link's income.

- **Reuse `LinkSimulation`'s consumption/income path**: add a cargo revenue term computed from belly
  capacity and cargo demand captured (with a conservative capture ratio, like the AI-growth estimator).
- **Aircraft model**: add a `cargoCapacity` (or derive belly volume from `Model`) — investigate the
  `Model` case class and whether a belly figure already exists or must be added (with sensible
  defaults per type so existing models keep working).
- Surface cargo revenue in the link income breakdown + a new `LedgerType` (`CARGO_REVENUE`), appended
  to preserve enum ordinals (see the Airport Assets enum-ordinal note).
- Bounded and automatic: no per-flight player input. This alone makes cargo-heavy routes/aircraft
  meaningfully better.

### C-3 — Cargo Terminal airport asset (bridge to Airport Assets)
Add a **Cargo Terminal / Logistics Park** to the Airport Assets catalog (`docs/airport-assets.md`):
an infrastructure-style asset that boosts cargo throughput/demand at the airport (a new
`AirportBoostType.CARGO` or a multiplier consumed by the cargo demand/capture step). This is the
cleanest first synergy between cargo and assets and gives a reason to build assets at freight hubs.
Reuses the entire Airport Assets pipeline; only the boost-consumption point in C-1/C-2 is new.

### C-4 — (optional, advanced) Dedicated freighters
Introduce a freighter aircraft role and cargo-only links: aircraft whose capacity is all cargo, links
that carry only cargo demand. This is the largest piece (new aircraft role, link type, UI for
planning cargo routes) and should only follow once belly cargo (C-2) and the terminal (C-3) feel
right in playtest. Keep heuristics cheap and bounded.

## Critical files (to confirm during C-1/C-2)

- `airline-data/.../DemandGenerator.scala` — model `computeBaseDemandBetweenAirports` as the template
  for cargo demand; consider memoization reuse.
- `airline-data/.../LinkSimulation.scala` — where link income is computed; add the belly-cargo term.
- `airline-data/.../model/airplane/Model.scala` + `Airplane.scala` — belly/cargo capacity source.
- `airline-data/.../model/Airline.scala` — `LedgerType.CARGO_REVENUE` (append).
- `airline-data/.../data/SoloConfig.scala` — `solo.cargo.*` knobs (enabled, captureRatio, revenue
  multiplier, demand amplitude).
- `airline-data/.../model/AirportAsset.scala` — the Cargo Terminal type (C-3).
- `airline-web` — cargo revenue in the income/link views; freighter UI only at C-4.

## Verification

- **Unit:** pure cargo-demand and belly-capacity/capture math (mirror `AirportAssetSpec` /
  `ComputerAirlineGrowthSpec` pure-helper style).
- **Economic-stability check:** with cargo on, confirm passenger economics are unchanged when cargo
  demand is zero, and that cargo revenue is bounded (never dominates the economy at default knobs).
- **Live:** enable `-Dsolo.cargo.enabled=true` in the deploy opts; confirm cargo-heavy routes show
  extra revenue, a Cargo Terminal raises it, and the cadence/economy feel right. Remember the
  existing-DB rule: any new table must self-create (`CREATE TABLE IF NOT EXISTS`), never rely on
  `Meta.createSchema`.

## Open questions (resolve via brainstorming before C-2)

- Is belly capacity already derivable from `Model`, or is a new field needed (and how to default it)?
- One global cargo demand figure per pair, or split by cargo class (express vs standard)? Start with
  one; only split if it adds real decisions.
- Should cargo influence aircraft choice enough to matter, without forcing freighters? Tune via the
  belly-capacity-by-type curve.
