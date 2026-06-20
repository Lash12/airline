# Airport Assets (single-player)

Shipped 2026-06-20. A player-facing investment layer adapted from `patsonluk/airline` (our fork
came from `myflyclub`, which never had it). Players spend cash to build assets at airports where
they hold a base; assets boost demand there and, depending on type, earn a modest income. Gated
behind `solo.airportAssets.enabled` (default false), so default/multiplayer deploys are unchanged.

## Player model

- Build only at an airport where you have a **base** (synergizes with bases / AI growth H-4).
- Each asset has an **airport size requirement** (`Airport.size`, 1–10) and a cash cost.
- **Multi-cycle construction**: an asset is `UNDER_CONSTRUCTION` until its `completionCycle`, then
  flips to `ACTIVE` (and the owner gets an `AIRPORT_ASSET_COMPLETE` notification). Only ACTIVE
  assets boost demand or earn/charge money.
- **Build/upgrade one level at a time** (up to `solo.airportAssets.maxLevel`); upgrading re-enters
  construction at the new level.
- **Sell** returns half the invested cash.

## Economics (per-type profile)

Each asset boosts a single `AirportBoostType` (linear in level) and has one of three profiles:

| Profile | Boost | Income? | Catalog (starter set) |
|---------|-------|---------|------------------------|
| **Revenue** | `INCOME` | yes (modest, rate 1.0) | Shopping Mall (size 4), Grand Hotel (size 5) |
| **Attraction** | `VACATION_HUB` / `FINANCIAL_HUB` / `INTERNATIONAL_HUB` | small (rate 0.5) | Resort (size 3), Convention Center (size 6), Landmark (size 7) |
| **Infrastructure** | `POPULATION` | **none** | Metro / Transit (size 5) |

- `unitCost(airport) = baseCost × airportModifier × solo.airportAssets.costMultiplier`, rounded to
  1000. `airportModifier = max(0.4, size/5)` (size 5 = 1.0×, size 10 = 2.0×). Cost is flat per level;
  total invested = `unitCost × level`; sell value = half that.
- `weeklyUpkeep = unitCost × 0.008 × level × upkeepMultiplier` (all types).
- `weeklyIncome = unitCost × 0.01 × incomeFactor × level × incomeMultiplier`, where `incomeFactor`
  is 1.0 (revenue), 0.5 (attraction), 0.0 (infrastructure). So revenue runs a small surplus,
  attraction a small deficit — the demand boost is the real return either way.

## Config knobs (`SoloConfig`, `solo.airportAssets.*`)

`enabled` (false), `maxLevel` (3), `costMultiplier` (1.0), `upkeepMultiplier` (1.0),
`incomeMultiplier` (1.0). All tunable live via the deploy opts.

## How the demand boost works (reused infrastructure)

The hard half already existed. `Airport` exposes `AirportBoostType` boosts via the
`AirportBoostContributor` trait (used by base specializations like `PowerhouseSpecialization`).
`Airport.income/population/popMiddleIncome` are lazy values that fold in these boosts and flow into
`DemandGenerator`; hub-type boosts feed `computeFeatures`. Assets plug in as a **new boost source**:

- `Airport.initAirportAssets(assets)` collects boosts from ACTIVE assets into a new
  `assetBoostFactors` map, merged into `createBoostFactorsLoader` and `computeFeatures` alongside the
  specialization factors.
- Assets are loaded in the `AirportSource` `fullLoad` path (same per-airport-query style as bases),
  **gated on `solo.airportAssets.enabled`**.

## Key files

- `airline-data/.../model/AirportAsset.scala` — `AirportAssetType` catalog + `AirportAsset` instance
  + pure helpers (cost/upkeep/income/boost/validation), unit-tested in `test/.../AirportAssetSpec.scala`.
- `airline-data/.../model/Airport.scala` — `assetBoostFactors`, `initAirportAssets`, loader/feature merge.
- `airline-data/.../data/AirportAssetSource.scala` — DAO + `ensureTable()`.
- `airline-data/.../data/{Meta,Constants}.scala` — `airport_asset` table.
- `airline-data/.../AirportAssetSimulation.scala` — per-cycle phase (completion + income/upkeep),
  wired into `MainSimulation.startCycle` after the airport phase.
- `airline-data/.../model/{Airline,Notification}.scala` — new `LedgerType` (`AIRPORT_ASSET_*`) and
  `NotificationCategory.AIRPORT_ASSET_COMPLETE` (both **appended** to preserve enum ordinals).
- `airline-web/.../controllers/AirportAssetApplication.scala` + `conf/routes` — get/build/sell API.
- `airline-web/.../views/fragments/airport_canvas.scala.html` + `public/javascripts/airport.js` —
  the Airport Assets panel section.

## Existing-database caveat (read before adding any new table)

`Meta.createSchema` only runs on a **fresh** init, so a new table does not exist on the live
OptiPlex DB. The first Airport Assets deploy crashed app startup with
`Table 'airline.airport_asset' doesn't exist`. The fix (and the rule going forward): self-create the
table with `AirportAssetSource.ensureTable()` (idempotent `CREATE TABLE IF NOT EXISTS`, indexes
declared inline since MySQL lacks `CREATE INDEX IF NOT EXISTS`), called by the DAO and before the
gated airport-load query. **Any future feature that adds a table must self-create it the same way,
or run a manual migration against the live DB — never rely on `createSchema` for existing data.**

## Live verification checklist

1. Open an airport where you have a base → **Airport Assets** section appears.
2. Build an asset (respecting size requirement + cost) → shows "Building (N cycles)".
3. Fast-forward past construction → flips to Active; completion notification; demand at the airport
   rises (more pax on your routes there); `AIRPORT_ASSET_INCOME`/`UPKEEP` appear in the ledger
   (none for Metro).
4. Sell → returns half the invested cash.

## Out of scope / future

- Throughput-linked income (scale income with the airport's realized passenger volume).
- NPC asset investment by the living-world AI.
- Passenger-discount / transit-time assets (patson's cost modifiers).
- A **Cargo Terminal** asset as the bridge to Air Cargo (see `docs/air-cargo-plan.md`).
