# Crew / Brand / Loyalty — Design Plan

_Status: design-only. No code yet. Stop for evaluation before any implementation._

---

## Motivation

SkylineSim's defining gameplay layers this game currently lacks:
- **Brand archetypes** — strategic positioning that affects passenger choice
- **Frequent-flyer loyalty** — accumulates per-airport, rewards retention
- **Named crew careers** — individuals that level up, affecting service quality

This game already has the scaffolding: per-airport `AirlineAppeal(loyalty)`, airline-global
`reputation`/`airlineGrade`, aggregate `currentServiceQuality`, and `LedgerType.FLIGHT_CREW`.
The plan below extends those rather than duplicating them.

**Design constraint:** all three sub-features are `solo.*`-gated, default off, AI-ignored, and
self-create any new tables via the `ensureTable()` pattern (see `AirportAssetSource.scala:26-48`).

---

## Sub-feature A — Brand Positioning

### What it adds
A brand archetype enum (BUDGET / REGIONAL / FULL_SERVICE / LUXURY) the player sets once (editable,
but rare). Archetype jointly weights `computedQuality` and crew-cost multipliers alongside existing
`currentServiceQuality`. SkylineSim calls this "brand" — here it is a tone modifier on the existing
quality axis rather than a separate axis.

### Existing primitives to reuse
| Thing | File | Use |
|-------|------|-----|
| `Airline.currentServiceQuality` | `Airline.scala:156` | Already affects link quality + crew cost |
| `Airline.airlineType` | `Airline.scala:206` | Existing types (Standard, Discount, Luxury); archetype would complement this |
| `Link.computedQuality` | `Link.scala:78` | Archetype feeds in as a fixed modifier |
| `FlightPreference.qualityAdjustRatio` | `FlightPreference.scala:51` | Where archetype modifier lands |
| `AirlineGrades` | `Airline.scala:121` | Grade already unlocks manager slots; archetype could unlock route types |

### Schema (no new table needed)
Persist as a column on `airline_info` (already holds `service_quality`, `target_service_quality`).
The `SchemaPatchRunner` auto-migration pattern (see `docs/database-migrations.md`) adds
`brand_archetype VARCHAR(20) DEFAULT 'FULL_SERVICE'`.

### Flag
`solo.brand.enabled` (default false).

### Player UI
Office → Airline Profile section; dropdown with four archetypes + description blurb. Costs nothing.

### Balance
- BUDGET: crew-cost multiplier 0.75×, quality cap −10, passenger price sensitivity +20%
- REGIONAL: no multipliers; modest loyalty bonus for home-country routes
- FULL_SERVICE: current behavior (no change)
- LUXURY: crew-cost multiplier 1.25×, quality floor +10, elite pax weight +15%

---

## Sub-feature B — Frequent-Flyer Loyalty

### What it adds
A per-airline, per-airport loyalty accumulation mechanic: flying your routes adds loyalty points
at destination airports; those points amplify the existing `AirlineAppeal(loyalty)` scalar in
`FlightPreference.loyaltyAdjustRatio`. Frames it as "your regulars keep coming back."

### Existing primitives to reuse
| Thing | File | Use |
|-------|------|-----|
| `AirlineAppeal(loyalty)` | `Airport.scala:391` | The scalar loyalty already feeds passenger choice |
| `AIRPORT_AIRLINE_APPEAL_BONUS_TABLE` | `Constants.scala:14` | Stores loyalty bonuses; already has permanent + expiring types |
| `AirportBoostContributor` | pipeline in `Airport.scala:216` | Bonus is applied via `initAirlineAppeals` |
| `FlightPreference.loyaltyAdjustRatio` | `FlightPreference.scala:117-123` | Where loyalty multiplier already lives |

### New table
`airline_loyalty` — created via `ensureTable()`:
```sql
CREATE TABLE IF NOT EXISTS airline_loyalty (
  airline_id INT NOT NULL,
  airport_id INT NOT NULL,
  loyalty_points INT NOT NULL DEFAULT 0,
  updated_cycle INT NOT NULL,
  PRIMARY KEY (airline_id, airport_id)
)
```

### Mechanic
Each simulation cycle, for each link the player operates:
- Add `passengers_carried × loyalty_gain_per_pax` to `airline_loyalty(airline, to_airport)`.
- Loyalty points decay by `loyalty_decay_rate` per cycle (so neglected airports lose loyalty).
- Convert current loyalty points to a bonus tier and insert/update in
  `AIRPORT_AIRLINE_APPEAL_BONUS_TABLE` as a permanent bonus (replacing the previous cycle's entry).

### Config flags
```
solo.loyalty.enabled = false
solo.loyalty.gainPerPax = 0.01     # loyalty points per pax carried
solo.loyalty.decayRate = 0.02      # fraction lost per cycle when no flights
solo.loyalty.maxBonus = 20.0       # max appeal bonus (caps at AirlineAppeal max=100)
```

### Player UI
Airport panel: a "Your Loyalty" row below the appeal line, showing earned points + bonus tier.
Progression milestones hook: "Loyal Following" at loyalty_points > threshold at your hub.

---

## Sub-feature C — Named Crew / Crew Careers

### What it adds
Each link generates "crew-hours" that create named crew members (pilot, cabin). Crew level up
through a career track (Junior → Senior → Lead). A fleet's average crew level feeds a multiplier
on `currentServiceQuality`, making real flying (not just assigning aircraft) the path to higher
quality. Ties to the Executive Team: a "Chief Pilot" executive seat boosts crew experience gain.

### Existing primitives to reuse
| Thing | File | Use |
|-------|------|-----|
| `LedgerType.FLIGHT_CREW` | `Airline.scala:214` | Aggregate weekly crew cost; remains unchanged |
| `currentServiceQuality` | `Airline.scala:156` | Named crew level multiplier feeds here |
| `ExecutiveBuffs` | `ExecutiveBuffs.scala` | Chief Pilot would call a new `crewXpBonus(airlineId)` |
| `AirlineMilestone` | `AirlineMilestone.scala:17+` | Add crew milestones (total crew trained, avg level) |

### New table
`crew_member` — created via `ensureTable()`:
```sql
CREATE TABLE IF NOT EXISTS crew_member (
  id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  airline_id INT NOT NULL,
  name VARCHAR(80) NOT NULL,
  crew_type ENUM('PILOT','CABIN') NOT NULL,
  xp INT NOT NULL DEFAULT 0,
  level TINYINT NOT NULL DEFAULT 1,  -- 1=Junior, 2=Senior, 3=Lead
  home_airport_id INT NOT NULL,
  INDEX idx_crew_airline (airline_id)
)
```

### Mechanic
- Each cycle, for each player link: generate `flights × 1` crew-hours of XP distributed to
  crew members already at that home airport. If none, spawn a new Junior crew member (capped
  per airline by `solo.crew.maxSize`).
- XP thresholds: Junior→Senior at 100 XP, Senior→Lead at 300 XP.
- Average crew level across the fleet computes a `serviceQualityMod` fed into
  `Airline.currentServiceQuality` update (read from `AirlineSimulation`).
- Weekly cost: no change to `FLIGHT_CREW` ledger (aggregate crew cost unchanged). The new table
  only tracks names/levels, not separate salaries. If brand archetype is LUXURY, crew-cost
  multiplier already covers it.

### Config flags
```
solo.crew.enabled = false
solo.crew.maxSize = 200             # cap per airline
solo.crew.xpPerFlight = 1
solo.crew.qualityMod = 0.1          # service quality boost per avg level above 1
solo.crew.chiefPilotXpBonus = 0.5   # extra XP per flight with Chief Pilot seated
```

### Player UI
Office → Crew section (new sub-section below Executive Team):
- "Total crew: N | Avg level: L.L"
- Small table: top-10 crew by XP (name, type, level, home airport)
- Progress bar to next tier for top crews

### Chief Pilot executive seat
Add to `ExecutiveSource` and existing exec panel (alongside CFO/CCO/COO). `ExecutiveBuffs`
gains a `crewXpBonus(airlineId)` method (same pattern as `adviceDepthBonus`). Unlocks at a
reputation threshold above COO.

---

## Phased implementation order

Ship each slice behind its own flag, evaluate before the next:

| Slice | Flag | Scope | Risk |
|-------|------|-------|------|
| B-1: Brand archetype persistence | `solo.brand.enabled` | DB column + UI dropdown | Low — additive |
| B-2: Brand effect on quality/cost | `solo.brand.enabled` | `Link.computedQuality` + crew-cost | Medium |
| L-1: Loyalty table + decay | `solo.loyalty.enabled` | New table + sim cycle hook | Low |
| L-2: Loyalty → appeal bonus | `solo.loyalty.enabled` | `AIRPORT_AIRLINE_APPEAL_BONUS_TABLE` | Medium |
| C-1: Crew spawn + XP | `solo.crew.enabled` | New table + cycle hook | Low |
| C-2: Crew level → service quality | `solo.crew.enabled` | `AirlineSimulation` update | Medium |
| C-3: Chief Pilot exec seat | `solo.exec.enabled` + `solo.crew.enabled` | ExecutiveSource + ExecutiveBuffs | Low |
| C-4: Crew UI panel | `solo.crew.enabled` | Frontend only | Low |
| Milestones | `solo.progression.enabled` | `AirlineMilestone.scala` additions | Low |

**Stop-for-eval gates:** after B-2 (does brand archetype change how players pick archetypes?),
after L-2 (does loyalty create meaningful airport attachment?), after C-2 (is crew leveling
paced correctly?).

---

## What NOT to build (yet)
- Per-crew salary (adds to ledger complexity without clear payoff at this stage)
- Crew fatigue / delay effects (needs disruption system from the roadmap first)
- Frequent-flyer redemptions / reward seats (separate economy design)
- Brand marketing campaigns (can layer on top of archetype later)

---

## Files to read before implementing any slice
- `AirportAssetSource.scala:26-48` — ensureTable pattern
- `SoloConfig.scala:21-264` — where to add new flags
- `AirlineSimulation.scala:91-100, 224` — where to hook per-cycle computations
- `ExecutiveBuffs.scala` — pattern for exec seat bonuses
- `AirlineMilestone.scala:17+` — where to add crew/loyalty milestones
- `docs/database-migrations.md` — if using SchemaPatchRunner for brand archetype column
