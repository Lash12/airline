# Balance Review — Repeatable Process

Repeatable telemetry workflow for evaluating cargo, airport assets, AI activity, and consultant
recommendations on the OptiPlex solo deployment. Run the queries after every major feature or
config change. No DB mutations; all queries are read-only SELECTs.

---

## 0. Quick-Connect Setup

Every query below uses this shell fragment. Run once per terminal session on the OptiPlex host,
or prefix each `mysql` call with the `eval` line:

```bash
eval "$(docker exec airline-db sh -c \
  'echo DB_USER=$MYSQL_USER DB_PASS=$MYSQL_PASSWORD DB_NAME=$MYSQL_DATABASE')"
MYSQL="docker exec airline-db mysql -u$DB_USER -p$DB_PASS $DB_NAME"
```

Then run any query as:

```bash
$MYSQL -e "SELECT ..."
```

Get the current cycle number first — every time-bounded query below uses `@CUR`:

```bash
$MYSQL -e "SET @CUR := (SELECT cycle FROM cycle LIMIT 1); SELECT @CUR;"
```

---

## 1. Live Config Snapshot

Values active on OptiPlex as of the last push to `master` (from `optiplex-deploy.yml`).
Update this table whenever `SIM_SOLO_OPTS` changes.

### Cargo

| Knob | Live Value | SoloConfig default | Notes |
|------|-----------|-------------------|-------|
| `cargo.enabled` | `true` | `false` | C-1 demand + C-2 belly revenue active |
| `cargo.captureRatio` | **0.5** | 0.5 | Fraction of pair demand capturable |
| `cargo.revenuePerUnitKm` | **0.01** | 0.01 | $0.01 per unit·km (R1 deployed June 2026) |
| `cargo.demandAmplitude` | 1.0 | 1.0 | Not set in deploy; uses default |
| `cargo.assets.enabled` | `true` | `false` | Cargo Terminal buildable |
| `cargo.freighters.enabled` | `true` | `false` | Freighter aircraft purchasable |

### Airport Assets

| Knob | Live Value | Notes |
|------|-----------|-------|
| `airportAssets.enabled` | `true` | |
| `airportAssets.maxLevel` | 3 | Not overridden |
| `airportAssets.costMultiplier` | 1.0 | Not overridden |
| `airportAssets.upkeepMultiplier` | 1.0 | Not overridden |
| `airportAssets.incomeMultiplier` | 1.0 | Not overridden |
| Hardcoded `UPKEEP_RATE` | 0.008/cycle | 0.8% of unit cost |
| Hardcoded `INCOME_RATE` | 0.01/cycle | 1.0% of unit cost (revenue assets only) |

Net for REVENUE assets: `unitCost × 0.002/cycle` → **payback = 500 cycles** regardless of airport size.
At ~50 cycles/day on OptiPlex: **~10 days wall clock**.

### AI Simulation

| Knob | Live Value | Practical ceiling at 50 cycles/day |
|------|-----------|-------------------------------------|
| `ai.enabled` | `true` | Drops active |
| `ai.airlinesPerCycle` | 10 | 10 NPCs evaluated for drops/cycle |
| `ai.maxDropsPerAirline` | 1 | ≤500 drops/day (upper bound) |
| `ai.growth.enabled` | `true` | Opens active |
| `ai.growth.maxAirlinesPerCycle` | 3 | ≤3 opens/cycle |
| `ai.growth.maxOpensPerAirline` | 1 | ≤150 opens/day |
| `ai.growth.openProfitThreshold` | 0 | Opens at any projected profit ≥ 0 |
| `ai.growth.maxNetworkSize` | 60 | Hard cap on NPC route count |
| `ai.growth.captureRatio` | 0.65 | Conservative load estimate |
| `ai.pricetune.enabled` | `true` | NPCs adjust prices adaptively |
| `ai.fleet.enabled` | `true` | NPC fleet renewal active |
| `ai.bases.enabled` | `true` | NPC base expansion active |
| `ai.bases.maxOpeningsPerCycle` | 1 | ≤1 new base per cycle globally |
| `ai.bases.cashCushion` | 3.0 | NPC needs 3× base cost in cash |

### Consultant

| Knob | Live Value |
|------|-----------|
| `consultant.enabled` | `true` |
| `consultant.captureRatio` | 0.7 |
| `consultant.candidateLimit` | 40 |
| `consultant.maxRecommendations` | 15 |
| `consultant.commonalityLevel` | 2 |
| `consultant.marketLevel` | 2 |

---

## 2. Cargo Revenue Share

### Query

```sql
SELECT
  a.name                                          AS airline,
  SUM(d.ticket_revenue)                           AS pax_rev,
  SUM(d.cargo_revenue)                            AS cargo_rev,
  ROUND(100.0 * SUM(d.cargo_revenue)
    / NULLIF(SUM(d.ticket_revenue + d.cargo_revenue), 0), 2)
                                                  AS cargo_pct,
  MAX(b.cycle)                                    AS last_cycle
FROM balance_details d
JOIN balance b
  ON b.airline = d.airline AND b.cycle = d.cycle AND b.period = d.period
JOIN airline a ON a.id = d.airline
WHERE d.period = 0            -- 0 = WEEKLY
  AND d.cycle >= (SELECT cycle FROM cycle LIMIT 1) - 12  -- last ~3h on OptiPlex
GROUP BY a.id, a.name
ORDER BY cargo_pct DESC;
```

### Targets

| Airline type | Expected cargo % |
|-------------|-----------------|
| Mixed carrier (belly only) | 1 – 8% |
| Cargo-focused (heavy freighters) | 15 – 30% |
| Passenger-only (no belly routes) | 0% |

**Red flags:**
- `cargo_pct > 30%` for a non-freighter carrier → rate too high, cargo eclipsing passengers
- `cargo_pct = 0` for all airlines after many cycles → rate too low or cargo gate broken
- `cargo_rev` is NULL for every row → `cargo_revenue` column missing (run `ALTER TABLE` from `IncomeSource.ensureCargoSchema`)

---

## 3. Top Cargo Routes

### By Revenue

```sql
SELECT
  fa.iata AS from_iata,
  ta.iata AS to_iata,
  al.name AS airline,
  SUM(lc.cargo_carried)  AS total_carried,
  SUM(lc.cargo_revenue)  AS total_cargo_rev,
  AVG(lc.cargo_capacity) AS avg_belly_cap,
  ROUND(100.0 * SUM(lc.cargo_carried) / NULLIF(SUM(lc.cargo_capacity), 0), 1)
                         AS fill_pct,
  COUNT(*)               AS cycles_observed
FROM link_consumption lc
JOIN airport fa ON fa.id = lc.from_airport
JOIN airport ta ON ta.id = lc.to_airport
JOIN airline al ON al.id = lc.airline
WHERE lc.cargo_revenue > 0
  AND lc.cycle >= (SELECT cycle FROM cycle LIMIT 1) - 50
GROUP BY lc.from_airport, lc.to_airport, lc.airline
ORDER BY total_cargo_rev DESC
LIMIT 20;
```

### By Unserved Demand (cargo gap)

Cargo unserved demand is not persisted separately (only passenger missed demand has its own
table). Use the belly-fill rate above as a proxy: routes with `fill_pct` near 100% have
unserved cargo demand. To inspect actual demand vs capacity on a specific pair:

```sql
-- Replace 3599 / 3600 with the airport IDs of interest
SELECT
  lc.cycle,
  lc.cargo_capacity,
  lc.cargo_carried,
  lc.cargo_revenue,
  ROUND(100.0 * lc.cargo_carried / NULLIF(lc.cargo_capacity, 0), 1) AS fill_pct
FROM link_consumption lc
WHERE lc.from_airport = 3599
  AND lc.to_airport   = 3600
  AND lc.cargo_capacity > 0
ORDER BY lc.cycle DESC
LIMIT 20;
```

**Interpretation:**
- `fill_pct` consistently 95-100% → bottleneck is capacity, not demand. Consider adding
  frequencies or a dedicated freighter.
- `fill_pct` < 20% → demand is thin or CargoDemandGenerator returned low for that pair.
  Check distance (< 400 km loses to trucking) and affinity.
- `cargo_revenue = 0` but `cargo_capacity > 0` → cargo allocation skipped this flight (may
  be a cargoEnabled=false moment, or zero demand).

---

## 4. Airport Asset Payback

### Active Assets

```sql
SELECT
  al.name         AS airline,
  ap.iata         AS airport,
  ap.size         AS airport_size,
  aa.asset_type,
  aa.level,
  aa.status,
  aa.completion_cycle,
  (SELECT cycle FROM cycle LIMIT 1) - aa.completion_cycle AS age_cycles
FROM airport_asset aa
JOIN airport ap ON ap.id = aa.airport
JOIN airline al ON al.id = aa.airline
ORDER BY al.name, ap.iata, aa.asset_type;
```

### Expected Payback (code-derived, no DB needed)

These are pure math from `AirportAsset.scala` at current multipliers (all 1.0):

| Asset | Category | Size 5 Build | Size 7 Build | Net/wk | Payback |
|-------|----------|-------------|-------------|--------|---------|
| Shopping Mall | REVENUE | $150M | $210M | +$300k / +$420k | 500 cycles |
| Grand Hotel | REVENUE | $120M | $168M | +$240k / +$336k | 500 cycles |
| Resort | ATTRACTION | $90M | $126M | −$270k / −$378k | Never (demand boost only) |
| Convention Center | ATTRACTION | $200M | $280M | −$600k / −$840k | Never (demand boost only) |
| Landmark | ATTRACTION | $250M | $350M | −$750k / −$1.05M | Never (demand boost only) |
| Metro | INFRASTRUCTURE | $200M | $280M | −$1.6M / −$2.24M | Never (population boost only) |
| Cargo Terminal | INFRASTRUCTURE | $180M | $252M | −$1.44M / −$2.02M | Never (cargo boost only) |

**Tuning knobs for payback:**
- Halve payback: `assetsUpkeepMultiplier=0.5` or `assetsIncomeMultiplier=2.0`
- Double payback: `assetsUpkeepMultiplier=2.0` or `assetsIncomeMultiplier=0.5`
- ATTRACTION/INFRASTRUCTURE become profitable: only via `assetsIncomeMultiplier` change
  (currently impossible by design — do not do this without revisiting game balance)

---

## 5. AI Activity (Route Opens / Drops / Base Expansions)

### Recent Activity from World News

```sql
SELECT
  wn.cycle,
  wn.message,
  wn.target_id
FROM world_news wn
WHERE wn.message LIKE '%opened%' OR wn.message LIKE '%dropped%' OR wn.message LIKE '%base%'
ORDER BY wn.cycle DESC
LIMIT 50;
```

### AI Activity Rate Summary

```sql
SELECT
  CASE
    WHEN message LIKE '%opened a base%'  THEN 'base_expansion'
    WHEN message LIKE '%opened%'         THEN 'route_open'
    WHEN message LIKE '%dropped%'        THEN 'route_drop'
    ELSE 'other'
  END                             AS event_type,
  COUNT(*)                        AS total,
  MIN(cycle)                      AS first_cycle,
  MAX(cycle)                      AS last_cycle,
  ROUND(COUNT(*) * 1.0 /
    NULLIF(MAX(cycle) - MIN(cycle) + 1, 0), 3)
                                  AS events_per_cycle
FROM world_news
WHERE cycle >= (SELECT cycle FROM cycle LIMIT 1) - 100
GROUP BY event_type
ORDER BY total DESC;
```

**Targets and red flags:**

| Metric | Healthy | Warning |
|--------|---------|---------|
| Route opens/cycle | 0.05 – 2.0 | > 5 → AI expanding too fast |
| Route drops/cycle | 0 – 1.0 | > 3 → economy destabilizing, many losers |
| Base expansions/cycle | 0 – 0.2 | > 0.5 → NPC network growing unchecked |
| Total NPC routes (see query below) | any | > `aiMaxNetworkSize` per airline → bug |

### NPC Network Sizes

```sql
SELECT
  al.name,
  COUNT(lk.id) AS route_count
FROM link lk
JOIN airline al ON al.id = lk.airline
WHERE al.is_ai = 1
GROUP BY al.id, al.name
ORDER BY route_count DESC
LIMIT 30;
```

---

## 6. Consultant Recommendation Quality

### Current Stored Recommendations

```sql
SELECT
  al.name                               AS airline,
  n.cycle,
  n.message,
  n.is_read,
  n.target_id
FROM notification n
JOIN airline al ON al.id = n.airline
WHERE n.category = 'CONSULTANT_ADVICE'
ORDER BY n.airline, n.cycle DESC;
```

### Market Overview Stored

```sql
SELECT
  al.name                               AS airline,
  n.cycle,
  n.message,
  n.is_read
FROM notification n
JOIN airline al ON al.id = n.airline
WHERE n.category = 'MARKET_OVERVIEW'
ORDER BY n.airline, n.cycle DESC;
```

**What to check:**
- Messages should contain `||{"r":[...], "x":...}` sidecar JSON. If the `||` is absent, the
  `buildRecSidecar` call failed silently — check sim log for `NullPointerException`.
- `is_read = 0` accumulating without bound → player is ignoring advice. Check if the
  Consultant panel is visible and the "Refresh" button is accessible.
- Recommendation routes should match airports where the player has bases. If you see routes
  from airports the player doesn't serve at all, check `ConsultantAdvisor.recommendations`
  filtering logic.
- Conversion is not tracked (no click-to-route log exists). Proxy: open the route planner
  for a recommended route manually and compare to consultant's suggested profit estimate.

### Route Forecast Accuracy (spot-check)

Route forecast predicted profit is in the notification sidecar; actual is in `link_consumption`.
Manual spot-check procedure:

```sql
-- 1. Find a recently recommended route (replace airline_id)
SELECT target_id, message
FROM notification
WHERE airline = <airline_id> AND category = 'CONSULTANT_ADVICE'
ORDER BY cycle DESC LIMIT 10;

-- 2. Find actual link consumption for that pair (replace from/to)
SELECT cycle, revenue, profit, cargo_revenue
FROM link_consumption
WHERE from_airport = <from_id> AND to_airport = <to_id> AND airline = <airline_id>
ORDER BY cycle DESC LIMIT 10;
```

Consultant estimates weekly profit via `estWeeklyProfit` in `ConsultantAdvisor.Recommendation`.
If actual profit is < 50% of estimated, the `captureRatio` (0.7) may be too optimistic.

---

## 7. Overall Revenue Composition

```sql
SELECT
  a.name,
  SUM(d.ticket_revenue)  AS pax,
  SUM(d.cargo_revenue)   AS cargo,
  SUM(d.lounge_revenue)  AS lounge,
  SUM(d.fuel)            AS fuel_cost,
  SUM(d.maintenance)     AS maint_cost,
  SUM(d.loan_interest)   AS interest,
  MAX(b.cash_on_hand)    AS cash
FROM balance_details d
JOIN balance b ON b.airline = d.airline AND b.cycle = d.cycle AND b.period = d.period
JOIN airline a  ON a.id = d.airline
WHERE d.period = 0
  AND d.cycle >= (SELECT cycle FROM cycle LIMIT 1) - 4
GROUP BY a.id, a.name
ORDER BY pax DESC;
```

---

## 8. Current Tuning Risk Assessment

| System | Risk | Issue | Action |
|--------|------|-------|--------|
| Cargo revenue rate | **MEDIUM** | R1 (0.01) deployed; belly contribution ~1-4%. Freighters still unviable without separate multiplier | Monitor via query §2 |
| Freighter viability | **HIGH** | No profitable rate that doesn't also make belly dominant. Need `cargoFreighterRevenueMultiplier` code change | Defer until R1 validated |
| REVENUE asset payback | **LOW** | 500-cycle payback (~10 days) is tight but correct for an always-on game | No action |
| ATTRACTION asset drain | **LOW** | Cash-negative by design; demand boost is the value prop | No action |
| AI growth rate | **LOW** | ≤3 opens/cycle, gated by profit threshold and cash cushion | Watch via query §5 |
| NPC base expansion | **LOW** | ≤1/cycle, `cashCushion=3.0` limits it to thriving carriers | Watch via query §5 |
| Consultant accuracy | **UNKNOWN** | No conversion tracking. Qualitative only | Spot-check via §6 |
| Demand tourist amplitude | **LOW** | Using 1.25 (upstream v5 value); less seasonal whiplash than 2.0 | No action |

---

## 9. Recommended Next Tuning Changes

### R2 — Freighter Revenue Multiplier (deferred, needs code)

Add `cargoFreighterRevenueMultiplier` (default 10.0) in `SoloConfig.scala` and apply in
`CargoAllocation.allocateGroup` when the link has zero pax capacity. This makes freighter routes
earn ~$130k/week JFK-LAX (vs ~$13k belly), approaching commercial viability. Implement only
after confirming R1 belly contributions feel right in playtest.

### R3 — Demand amplitude experiment (low risk)

If mid-tier cargo routes feel thin after more play time, raise `cargoDemandAmplitude` from 1.0
to 1.5 via deploy flag only. Proportional demand increase with no effect on revenue per unit.

### R4 — Increase `assetsMaxLevel` to 5 (low risk, additive)

Current cap of 3 limits large-hub investment. Raising to 5 extends total spend potential and
gives established airlines a reason to keep building. Payback stays 500 cycles; just more of it.
Deploy flag change only: `-Dsolo.airportAssets.maxLevel=5`.

---

## 10. Refresh Checklist

Run after each major code push or config change:

```bash
# On OptiPlex host:
eval "$(docker exec airline-db sh -c \
  'echo DB_USER=$MYSQL_USER DB_PASS=$MYSQL_PASSWORD DB_NAME=$MYSQL_DATABASE')"
MYSQL="docker exec airline-db mysql -u$DB_USER -p$DB_PASS $DB_NAME"

# Current cycle
$MYSQL -e "SELECT cycle FROM cycle LIMIT 1;"

# Cargo revenue share (last 12 cycles)
$MYSQL -e "
SELECT a.name, SUM(d.cargo_revenue) cargo_rev,
  ROUND(100.0*SUM(d.cargo_revenue)/NULLIF(SUM(d.ticket_revenue+d.cargo_revenue),0),2) cargo_pct
FROM balance_details d JOIN airline a ON a.id=d.airline
WHERE d.period=0 AND d.cycle>=(SELECT cycle FROM cycle LIMIT 1)-12
GROUP BY a.id,a.name ORDER BY cargo_pct DESC;"

# Top 10 cargo routes (last 50 cycles)
$MYSQL -e "
SELECT fa.iata,ta.iata,al.name,SUM(lc.cargo_revenue) total_rev,
  ROUND(100.0*SUM(lc.cargo_carried)/NULLIF(SUM(lc.cargo_capacity),0),1) fill_pct
FROM link_consumption lc JOIN airport fa ON fa.id=lc.from_airport
JOIN airport ta ON ta.id=lc.to_airport JOIN airline al ON al.id=lc.airline
WHERE lc.cargo_revenue>0 AND lc.cycle>=(SELECT cycle FROM cycle LIMIT 1)-50
GROUP BY lc.from_airport,lc.to_airport,lc.airline ORDER BY total_rev DESC LIMIT 10;"

# AI activity summary (last 100 cycles)
$MYSQL -e "
SELECT
  CASE WHEN message LIKE '%opened a base%' THEN 'base_expansion'
       WHEN message LIKE '%opened%' THEN 'route_open'
       WHEN message LIKE '%dropped%' THEN 'route_drop' ELSE 'other' END event_type,
  COUNT(*) total,
  ROUND(COUNT(*)*1.0/NULLIF(MAX(cycle)-MIN(cycle)+1,0),3) per_cycle
FROM world_news WHERE cycle>=(SELECT cycle FROM cycle LIMIT 1)-100
GROUP BY event_type ORDER BY total DESC;"

# Active airport assets
$MYSQL -e "
SELECT al.name,ap.iata,ap.size,aa.asset_type,aa.level,aa.status
FROM airport_asset aa JOIN airport ap ON ap.id=aa.airport JOIN airline al ON al.id=aa.airline
ORDER BY al.name,ap.iata;"
```

Paste the output as a comment in the relevant GitHub PR or commit, or append a dated snapshot
to `docs/balance-snapshots/YYYY-MM-DD.md`.
