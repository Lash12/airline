# Balance Review — June 2026

Original pre-implementation audit before cargo contracts and disruption systems. Updated
2026-07-01 to record the shipped cargo revenue and freighter-multiplier changes.

---

## 1. Current Flag Values (OptiPlex solo config)

All values come from `SoloConfig.scala` defaults; no overrides in `optiplex-deploy.yml`
except `routeForecast.enabled=true` (added June 2026).

### Cargo

| Flag | Value | Source |
|------|-------|--------|
| `cargoEnabled` | `true` | SoloConfig default |
| `cargoRevenuePerUnitKm` | **0.01** | SoloConfig default, raised from 0.0002 |
| `cargoFreighterRevenueMultiplier` | **10.0** | SoloConfig default, applies only to cargo flight links |
| `cargoDemandAmplitude` | 1.0 | SoloConfig default |
| `cargoCaptureRatio` | 0.5 | SoloConfig default |
| `cargo.assets.enabled` | `true` | deploy flag |
| `cargo.freighters.enabled` | `true` | deploy flag |

### Airport Assets

| Flag | Value |
|------|-------|
| `airportAssets.enabled` | `true` |
| `assetsCostMultiplier` | 1.0 |
| `assetsUpkeepMultiplier` | 1.0 |
| `assetsIncomeMultiplier` | 1.0 |
| `assetsMaxLevel` | 3 |
| `UPKEEP_RATE` (hardcoded) | 0.008 / cycle |
| `INCOME_RATE` (hardcoded) | 0.01 / cycle |

### AI Simulation

| Flag | Value | Meaning |
|------|-------|---------|
| `aiAirlinesPerCycle` | 10 | NPCs that can drop routes each cycle |
| `aiMaxDropsPerAirline` | 1 | Max drops per NPC per cycle |
| `aiMaxGrowthAirlinesPerCycle` | 3 | NPCs that can open routes each cycle |
| `aiMaxOpensPerAirline` | 1 | Max opens per NPC per cycle |
| `aiBasesMaxOpeningsPerCycle` | 1 | Max new bases per cycle (all NPCs) |
| `aiMaxNetworkSize` | 60 | Hard cap on NPC routes |
| `aiOpenProfitThreshold` | 0 | NPC opens routes at any profit ≥ 0 |
| `aiBasesOpenProfitThreshold` | 0 | NPC opens bases at any profit ≥ 0 |
| `aiBasesCashCushion` | 3.0 | NPC needs 3× base cost in cash to expand |

---

## 2. Observed / Estimated Effects

### 2a. Cargo Revenue — HISTORICAL ISSUE, BASE RATE FIXED

**Formula:** `revenue = carried * distance * cargoRevenuePerUnitKm`

Representative pre-fix calculations at `cargoRevenuePerUnitKm = 0.0002`:

| Route | Configuration | Carried/wk | Revenue/wk | vs Pax Revenue |
|-------|--------------|-----------|-----------|----------------|
| JFK→LAX (3983 km) | 737-800 belly, 14 freq | 14 × 14 = 196 units | **$156** | ~0.02% of ~$595k |
| JFK→LAX (3983 km) | 737 freighter, 7 freq | 335 units (capped) | **$267** | vs ~$200-300k cost |
| JFK→LHR (5570 km) | 777-200 belly, 7 freq | 455 units | **$507** | ~0.08% of ~$600k |

Belly cargo was effectively free money on the order of rounding error. Freighter operations
lost money at a rate of 99.9%+ — no rational player would operate one.

**Rate needed for 3% belly contribution on JFK-LAX 737:** `0.0115`
**That is 57× the current value.**

At the shipped base rate of `0.01` (50× the original value):
- Belly 737 JFK-LAX 14 freq: **$7,800/week** (~1.3% of pax revenue) — meaningful but secondary
- Freighter 737 JFK-LAX: ~$13,350/week vs ~$200-300k cost — still commercially unviable

Freighters require a shared rate of ~0.22 to break even, at which point belly cargo alone becomes
~30% of pax revenue (too dominant). The 2026-07 pass therefore added a separate
`solo.cargo.freighterRevenueMultiplier` defaulting to `10.0`, applied only to cargo flight links.

**Risk of raising rate too high:** cargo eclipses passengers as the dominant revenue stream,
removing the core route-planning tension.

### 2b. Cargo Terminal — Net Negative (until cargo rate is fixed)

| Airport Size | Build Cost | Weekly Upkeep | Weekly Revenue Boost (at 0.0002) | Net/wk |
|-------------|-----------|--------------|----------------------------------|--------|
| 5 | $180M | $1,440k | ~$24 | **−$1,440k** |
| 7 | $252M | $2,016k | ~$40 | **−$2,016k** |
| 10 | $360M | $2,880k | ~$60 | **−$2,880k** |

The 15% `cargoTerminalMultiplier` boost on capturable cargo is worthless at the current rate.
If `cargoRevenuePerUnitKm` is raised to `0.01`, the boost becomes ~$2k/week — still not
enough to offset $1.4-2M/week upkeep. Terminals need a cargo rate of ~0.5+ to pay for
themselves purely through revenue. They are (and should remain) an infrastructure investment
that enables cargo volume, not a profit center in isolation.

**Cargo Terminals are correctly modeled as INFRASTRUCTURE** (no income, pure upkeep drain).
The value prop is throughput, not direct income. This is fine once cargo revenue is balanced.

### 2c. Airport Asset Payback (REVENUE types)

REVENUE assets (Shopping Mall, Grand Hotel) generate net positive cash:

```
net/week = unitCost × (INCOME_RATE − UPKEEP_RATE) = unitCost × 0.002
payback   = unitCost / (unitCost × 0.002) = 500 cycles  [always, regardless of size]
```

| Asset | Size 5 Build | Net/wk | Payback |
|-------|-------------|--------|---------|
| Shopping Mall | $150M | +$300k | 500 cycles |
| Grand Hotel | $120M | +$240k | 500 cycles |
| Shopping Mall | Size 10, $300M | +$600k | 500 cycles |

**500 cycles = ~10 days wall clock at 29-minute OptiPlex cycles (~50 cycles/day).**
This is tight but reasonable for an always-on persistent game. No rebalancing needed.

### 2d. ATTRACTION & INFRASTRUCTURE Assets — Cash-Negative by Design

ATTRACTION assets (`incomeFactor = 0.5`): income < upkeep → net drain.

| Asset | Size 7 Build | Net/wk |
|-------|-------------|--------|
| Resort | $126M | −$378k |
| Convention Center | $280M | −$840k |
| Landmark | $350M | −$1,050k |

These are intentionally cash-negative; their value is the demand multiplier they apply to
routes through the airport. This tension (pay upkeep to unlock more passengers) is a core
game mechanic and is working correctly.

### 2e. AI Simulation Rates

At ~50 cycles/day on OptiPlex (29-min cycle time):

| Behavior | Rate | Practical ceiling |
|---------|------|-------------------|
| Route drops | ≤10 NPCs × 1 drop × 50 cycles | ≤500 drops/day (likely ≪ in practice) |
| Route opens | ≤3 NPCs × 1 open × 50 cycles | ≤150 opens/day, gated by `openProfitThreshold=0` |
| Base expansions | ≤1/cycle × 50 cycles | ≤50/day, gated by 3× cash cushion |
| Network cap | 60 routes/NPC | Hard ceiling; limits total AI footprint |

AI growth is self-limiting: `cashCushion=3.0` means an NPC needs $300M cash to open a $100M
base. `openProfitThreshold=0` means NPCs open routes as soon as they project any profit.
These values appear well-calibrated. No changes recommended.

---

## 3. Risk Assessment

| System | Risk Level | Issue |
|--------|-----------|-------|
| Cargo revenue rate | **LOW** | Base rate raised to 0.01; monitor actual cargo share after deploy |
| Freighter viability | **MEDIUM** | Freighter-only multiplier shipped; monitor whether freighter lanes are viable without dominating |
| Cargo Terminal value | **MEDIUM** | Dependent on cargo rate fix; currently pure drain |
| REVENUE asset payback | **LOW** | 500-cycle payback is tight but acceptable |
| AI growth rates | **LOW** | Well-calibrated; no action needed |
| ATTRACTION assets | **LOW** | Cash-negative by design; functioning correctly |

---

## 4. Recommended Adjustments

### Immediate (before cargo contracts or disruptions)

**R1 — Raise `cargoRevenuePerUnitKm` from 0.0002 -> 0.01 — DONE 2026-06**

In `SoloConfig.scala` default + `optiplex-deploy.yml`:
```
-Dsolo.cargoRevenuePerUnitKm=0.01
```

Effect at 0.01:
- Belly cargo JFK-LAX 737/14 freq: $7,800/week (~1.3% pax revenue) — players notice it
- 777-200 belly JFK-LHR 7 freq: $25,350/week (~4% pax revenue) — long-haul cargo feels real
- Cargo Terminal boost (15%): ~$1,200-2,000/week — still not self-funding, correctly so
- Freighters: ~$13,350/week vs $200-300k cost — still unviable

**R2 — Add a `cargoFreighterRevenueMultiplier` (default 10.0) for freighter-only routes — DONE 2026-07**

Freighters cannot be made profitable via the shared `cargoRevenuePerUnitKm` without
breaking belly cargo economics. A separate multiplier (applied only when link has no pax
capacity, implemented as cargo flight link pricing) lets freighters reach ~$130k/week —
approaching viability on low-cost short/medium routes. The implementation is in
`CargoAllocation.scala` and is covered by unit tests proving belly cargo is unchanged.

### Deferred (after playtest of R1)

**R3 — Consider `cargoDemandAmplitude` increase if routes feel empty**

Default is 1.0. If cargo demand on mid-tier routes feels too thin after R1, raising to 1.5
would proportionally increase served/unserved demand without touching revenue per unit.

**R4 — `assetsMaxLevel = 5` experiment**

Current cap of 3 limits large-hub investment depth. Raising to 5 extends the REVENUE asset
payback window (still 500 cycles, just higher absolute cost/return) and lets players invest
more deeply at major hubs. Low risk; purely additive.

---

## 5. Manual Playtest Checklist

After deploying R1/R2 (`cargoRevenuePerUnitKm = 0.01`,
`cargoFreighterRevenueMultiplier = 10.0`):

- [ ] Open a short domestic route (< 1000 km), note belly cargo revenue in link stats — expect ~$1-3k/week
- [ ] Open a long-haul route (> 5000 km), note belly cargo revenue — expect ~$10-30k/week on widebody
- [ ] Build a Cargo Terminal at a mid-size airport (size 5-7), confirm upkeep shown in airport finances
- [ ] Open a cargo route from that airport, confirm `cargoTerminalMultiplier` boost visible in served demand
- [ ] Open or inspect a freighter route, confirm freighter cargo revenue is materially higher than belly revenue on the same distance
- [ ] Confirm passenger belly cargo does not receive the freighter multiplier
- [ ] Check `cargo-opportunities` panel at JFK — expect non-trivial unserved demand numbers
- [ ] Observe 2-3 AI airlines over a few cycles — confirm they open/drop routes without flooding the market
- [ ] Verify REVENUE asset (Shopping Mall level 1) shows positive net income in asset panel
- [ ] Verify ATTRACTION asset (Resort level 1) shows negative net income in asset panel with demand note

---

## 6. What to Monitor After R1

- **Cargo share of total revenue** — target 2-8% for mixed carriers, up to 20% for cargo-focused players
- **Freighter route economics** — short/medium lanes should be plausible but not guaranteed profitable
- **Unserved cargo demand** at major hubs — should drop when players add belly capacity
- **AI network growth rate** — watch for runaway expansion (none expected given current caps)
- **Player engagement with cargo opportunities panel** — leading indicator that cargo feels worthwhile
