# Asset Decision Support — design spec

Date: 2026-06-20. Status: design approved, pending spec review → implementation plan.

## Context

Airport Assets shipped (`docs/airport-assets.md`), but playtest feedback is that a player "doesn't
know what any of these assets will do for me" from the current UI, and has no way to judge whether a
market justifies an asset. This feature makes the airport screen a **decision-support surface**: it
explains each asset's benefit/economics, and shows the traffic analytics (transfer share +
passenger demographics) a player needs to judge whether to build. The two halves reinforce each
other and ship together (one combined feature, per the user).

Most of the data already exists (confirmed by exploration), so this is largely "surface it," not
"compute it."

## Part A — Airport Traffic Analytics (new airport-panel section, always-on)

Read-only visibility of already-computed data; **not** gated behind a `solo.*` flag (it changes no
simulation behavior and is useful in any deploy).

- **Airport summary headline:** total recent weekly passengers, split **% terminating (origin/
  destination) vs % transferring (transit)**, plus an overall **demographic mix** (business /
  tourist / traveler / elite shares).
- **Per-route table (whole market, all airlines):** one row per destination served from/into the
  airport, with weekly passengers, **transfer % vs direct %**, and **passenger-type mix**. Sortable
  by volume.

**Data sources (no schema change):**
- Transfer/direct + volumes per route → `link_statistics` (`is_departure`/`is_destination` flags,
  persisted per cycle) via `LinkStatisticsSource.loadLinkStatisticsBy{From,To}Airport`. Cheap.
- Demographic (pax-type) mix → aggregate `passenger_route_history` (+ `passenger_link_history` to
  attribute legs to this airport) by `passenger_type`, via a new query in `ConsumptionHistorySource`.
  Richer; these tables rotate ~30 weeks, so it is "recent" data. If empty (new world / just rotated),
  the UI shows "no data yet."
- The existing airport detail endpoint (`Application.computeAirportDetailJson`) already computes
  `departure/destination/transit` passenger totals — the summary reuses that logic.
- **New on-demand endpoint** `GET /airports/:airportId/traffic-analytics` so the main airport payload
  stays lean (analytics load when the section opens).

## Part B — Asset benefit / ROI tooltips + imagery (enhance the existing Assets section)

- **Imagery:** each `AirportAssetType` gains an `image` filename; the catalog/owned rows and tooltips
  show the asset art. Art is copied from `patsonluk/airline` (Apache 2.0) — only the 6 we use:
  `SHOPPING_MALL.png`, `GRAND_HOTEL_BUSINESS.png` (Grand Hotel), `BEACH_RESORT.png` (Resort),
  `CONVENTION_CENTER.png`, `LANDMARK.png`, `SUBWAY.png` (Metro) → `airline-web/public/images/airport-assets/`.
  Add a top-level `NOTICE` crediting patsonluk/airline for the asset art; keep our `LICENSE`.
- **Benefit/economics tooltip:** plain-language description of the boost (e.g. "Resort: +4 Vacation
  Hub strength → raises inbound **tourist** demand here"; "Shopping Mall: +3,000 airport income →
  lifts overall demand"), the weekly upkeep, and for **revenue** assets the weekly income, **net**,
  and **payback period** (cost ÷ net). For **attraction/infrastructure** (net-negative by design):
  state upkeep + the boost magnitude, and point to the Traffic Analytics section to gauge demand.
- Backend: extend the asset catalog JSON in `AirportAssetApplication` with `description`, `image`,
  `netWeekly`, `paybackCycles`; descriptions/imagery live on `AirportAssetType`.
- Frontend: reuse the existing `.tooltip`/`.tooltiptext` span pattern.

## Components / boundaries

- **Backend:** `AirportAnalyticsApplication` (new) or a method on `Application` for the analytics
  endpoint, backed by `LinkStatisticsSource` + a new aggregation in `ConsumptionHistorySource`.
  Asset-catalog enrichment in `AirportAssetApplication` + `AirportAssetType`.
- **Frontend:** `airport.js` (render analytics section + asset tooltips/images) and
  `airport_canvas.scala.html` (markup).
- **Pure helpers (unit-tested):** percentage/share math (transfer % and demographic mix from raw
  counts) and asset payback math (`netWeekly`, `paybackCycles`). Mirror the `AirportAssetSpec` /
  `ComputerAirlineGrowthSpec` pure-helper style.

## Data flow

Open airport panel → main detail loads as today → opening the Traffic Analytics section triggers
`GET /airports/:id/traffic-analytics` → render summary + per-route table. Assets section catalog now
carries benefit/payback/image fields → tooltips + art render inline.

## Error handling

History tables empty/rotated → "no data yet" rather than an error. Analytics endpoint is read-only
and independent of the asset flag. Asset tooltips appear only where the Assets section already shows
(flag-gated).

## Testing / verification

- Unit: share/percentage and payback helpers.
- Compile gate: `airline-data publishLocal` → `airline-web compile`.
- Live (OptiPlex): open a busy airport → analytics summary + per-route transfer%/demographics render;
  asset tooltips show benefit/ROI and art. Confirm a low-traffic airport shows sensible "no data yet"
  where applicable.

## Out of scope

- New persisted analytics (we read existing tables); historical charts/trends over time.
- Per-route demographics for routes with no recent history rows.
- Reworking the broader airport panel layout beyond adding these sections.

## Licensing note

Asset art reused from `patsonluk/airline` under Apache 2.0; compliance via retained `LICENSE` + a new
`NOTICE` attributing the art. Caveat: Apache 2.0 covers only what the licensor owns; acceptable for a
private, non-commercial, self-hosted instance.
