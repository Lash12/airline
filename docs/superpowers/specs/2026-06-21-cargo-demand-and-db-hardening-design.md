# Airport Cargo Demand Panel + DB Pool Hardening — Design Spec

Date: 2026-06-21
Status: Approved (pending implementation)

## Context

Three backlog items from the 2026-06-21 session (`docs/current-development-state.md`):

- **A — Airport Cargo Demand panel:** the airport page surfaces passenger demand
  (`/airports/:id/demand`) but no cargo demand, even though the cargo feature is
  live. Players scouting a base can't see freight potential.
- **B — DB connection-pool hardening:** a production incident was caused by
  `AirlineSource.loadAirlinesByQueryString` holding its read connection across
  enrichment sub-loaders that each opened another connection, exhausting the
  Hikari pool (`maxPoolSize=8`). One more confirmed instance of the same pattern
  exists; the pool is also small.
- **C — Catalog button label nit:** the airport asset catalog can render a
  button labelled "Max level" while the disabled-reason text says build-a-base-first
  (an unreachable combo, but inconsistent).

## A — Airport Cargo Demand panel

### Backend

New endpoint, mirroring the passenger demand endpoint
(`Application.getAirportDemand` / `computeAirportDemandJson`,
`app/controllers/Application.scala:817-887`) including its `ResponseCache` +
HTTP-304 ETag-by-cycle pattern:

- Route: `GET /airports/:airportId/cargo-demand` → `Application.getAirportCargoDemand`.
- If `!SoloConfig.cargoEnabled`, return `Json.arr()` (empty) — the frontend hides
  the section on empty.
- Otherwise:
  1. Load `fromAirport` via `AirportCache.getAirport(airportId)`.
  2. Iterate the airport list (reuse whatever `computeAirportDemandJson` /
     `ComputerAirlineGrowth.bestCandidates` use — `AirportSource.loadAllAirports`
     or the cached airport list). For each `toAirport != fromAirport`:
     - filter with `DemandGenerator.canHaveDemand(from, to, distance)` and a
       distance/sanity guard (mirror the passenger/candidate filters);
     - compute `distance` via `Computation.calculateDistance` and `affinity` the
       same way `LinkApplication.planLink` does (it already calls
       `CargoDemandGenerator.computeCargoDemandBetweenAirports(from, to, affinity,
       distance)` at `LinkApplication.scala:915-916` — reuse the identical
       affinity/distance derivation);
     - `val demand = CargoDemandGenerator.computeCargoDemandBetweenAirports(from, to, affinity, distance)`.
  3. Keep rows with `demand > 0`, sort descending by `demand`, take **top 15**.
  4. Serialize each row as:
     `{ toAirportId, toAirportName (city), toAirportIata, cargoDemand, served }`,
     where `served` is whether the airline already operates a cargo (or any) link
     on that pair from this airport — reuse the served-set the passenger endpoint
     builds if available; otherwise omit `served` for v1 rather than add a query.

This is O(N) per request (this airport vs ~N others; N≈few thousand) — acceptable
for an on-demand panel, and the per-cycle cache + 304 keeps repeat loads free. Do
NOT compute all-pairs (O(N²)).

Connection discipline: the endpoint must not hold a DB connection while looping
the cargo math. Load airports/served-set first (each via its own
source/cache call), release, then compute (pure math) — consistent with item B.

### Frontend

- `airport.js`: add `loadAirportCargoDemand(airportId)` (fetch
  `/airports/${airportId}/cargo-demand`, If-None-Match etag like
  `loadAirportDemand`) and `renderCargoDemandCards(data)`. Call
  `loadAirportCargoDemand` from `showAirportDetails` alongside the existing
  passenger demand load.
- `app/views/fragments/airport_canvas.scala.html`: add a `#airportCargoDemandCards`
  section adjacent to `#airportDemandCards` (~line 401-405), with a heading
  ("Cargo Demand"). Hide the whole section when the response is empty (cargo off
  or no demand).
- Cards reuse the existing demand-card visual style (already mobile-friendly from
  the airport-mobile pass): header `toAirportIata` (bold) + `toAirportName`;
  body the cargo demand figure (units); a served/unserved badge. No new CSS
  tokens — reuse existing demand-card classes.

## B — DB connection-pool hardening

- `airline-data/src/main/resources/application.conf:32`: `hikari.maxPoolSize = 8`
  → `16`. (MySQL default `max_connections` ≈ 151; web + sim tiers each open a
  pool, so 16+16 is well within budget.)
- `airline-data/.../data/AllianceSource.scala` `loadAlliancesByQueryString`
  (~65-90): currently reads alliances inside `Using.resource(Meta.getConnection())`
  and, still holding it, calls `loadAllianceMembersByAllianceId` (line ~79) which
  opens a second connection. Refactor to the same shape as the `AirlineSource`
  fix: read the alliance rows into a list with the connection scoped to just the
  query, release it, then call `loadAllianceMembersByAllianceId` for enrichment.

Out of scope (noted follow-up): the cache-fault nesting in
`LinkSource.loadLinksByQueryString` / `loadLinkConsumptionsByQuery`,
`AirlineSource.loadAirlineBasesByQueryString`, `AirportAssetSource.loadByCriteria`
— these call in-memory caches mid-iteration that only open a connection on a cold
miss; refactoring them to two-pass cache resolution is more invasive and is
deferred.

## C — Catalog button label

`airport.js` asset catalog row builder (the `renderAirportAssets` catalog loop):
derive `actionLabel` from the same precedence as the disabled `reason` so the two
never contradict. Specifically, when `!data.hasBase`, keep the Build/Upgrade
label (the action is still build), rather than letting `!entry.canUpgrade` force
"Max level". Net: `!hasBase` → label `Build`/`Upgrade to N` (reason explains the
block); otherwise `!canUpgrade` → `Max level`; else normal.

## Affected files

- `app/controllers/Application.scala` — new `getAirportCargoDemand` + JSON builder.
- `conf/routes` — `GET /airports/:airportId/cargo-demand`.
- `public/javascripts/airport.js` — cargo demand load/render; catalog label fix (C).
- `app/views/fragments/airport_canvas.scala.html` — `#airportCargoDemandCards` section.
- `airline-data/src/main/resources/application.conf` — pool size (B).
- `airline-data/.../data/AllianceSource.scala` — connection scoping (B).

## Verification

- **Unit (B):** existing `airline-data` compiles; if a pure helper is extracted,
  add a focused test. The connection refactor is behavior-preserving — assert via
  compile + the live pool not leaking under load.
- **Backend (A):** `sbt compile` (airline-data if CargoDemandGenerator usage
  changes; airline-web for the controller/route). Hit
  `/airlines/.../` flow then `GET /airports/<hqId>/cargo-demand` in an authed
  Playwright request: expect 200 + an array (≤15) sorted by `cargoDemand`, or
  `[]` when cargo disabled.
- **Frontend (A, C):** Playwright on the airport page (mobile + desktop): cargo
  demand section renders cards when cargo enabled; section hidden when empty;
  catalog button label matches its reason for a no-base airport.
- **B live check:** after deploy, watch `/home/airline/web.log` under load for
  absence of `Connection is not available` / `Apparent connection leak`.
- Deploy: push to master → OptiPlex deploy + Playwright (pre-authorized).
