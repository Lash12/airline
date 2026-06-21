# Airport Page Mobile UX — Design Spec

Date: 2026-06-21
Status: Approved (pending implementation)

## Context

The airport page (`#airportCanvas`) is dense with multi-column data tables, small
action buttons, and tiny (24px) asset images. On mobile (≤640px) the generic
`mobile.css` rule forces every `.table.data .cell` to `width:auto;
font-size:10px; word-break:anywhere`, so wide tables collapse and values stack
vertically and become hard to read. Action buttons sit in narrow `%`-width cells
with long labels (`Upgrade to 3`, `Sell ($1,234,567)`) and don't scale to a
portrait screen. Asset images are too small to convey anything, and the asset
catalog's benefit/ROI text is hidden inside an `img title=` tooltip that is
unreachable on touch.

This is an incremental mobile-usability pass over the whole airport page. No new
features, no backend changes. Reuse existing CSS tokens and the existing modal
pattern.

## Goals

- Wide airport tables stay readable on mobile via horizontal scroll instead of
  collapsing into stacked text.
- Asset build/sell decisions are made through a detail modal that shows a large
  image, full benefit/ROI/payback info, and one large, readable action button.
- Reclaim horizontal width with abbreviated money on mobile.
- Larger, easier tap targets.

## Non-goals

- No page-level navigation (no collapsible sections, no sticky tabs).
- No changes to other pages' tables (the global mobile rule and the flights-list
  opt-out stay untouched).
- No backend / API / data-model changes.
- No cargo-demand or analytics data additions (tracked separately).

## Design

### 1. Wide tables → horizontal scroll (scoped to `#airportCanvas`)

Override the generic collapse behaviour **only** for tables inside
`#airportCanvas`, in `public/stylesheets/mobile.css`:

- Do **not** force `.table.data .cell { width:auto }` for airport tables; keep
  each cell's declared `%` width.
- Give each airport `.table.data` a `min-width` large enough that its columns
  keep their intended proportions, so the table overflows its container.
- The table's scroll container scrolls horizontally (`overflow-x:auto`;
  `-webkit-overflow-scrolling:touch`).
- Add a right-edge fade affordance (a CSS gradient overlay on the scroll
  container) hinting there is more to swipe. Optional, low-risk; drop if it
  fights existing layout.

Scope via a selector like `#airportCanvas .table.data` / `#airportCanvas
.table.data .cell` so the rules are more specific than the generic mobile rule
and win the cascade without `!important` where possible. The existing global
rule (`mobile.css` ~166-179) and the `#linksCanvas` opt-out are not modified.

Affected tables: built-assets list (`#airportDetailsAssetList`), build/upgrade
catalog (`#airportDetailsAssetCatalog`), traffic-analytics route list
(`#airportTrafficRouteList`), the airport statistics tables, and the
rankings/headquarters/bases/lounges tables in `airport_canvas.scala.html`.

### 2. Asset detail modal (new) — primary action surface

Add `#airportAssetDetailsModal` to `app/views/fragments/modals.scala.html`,
following the existing `<div class="modal"><div class="modal-content">` pattern
and the existing open/close helpers used by other modals.

Trigger: clicking any row in the built-assets list **or** the build/upgrade
catalog opens the modal (desktop and mobile). Rows become the interaction
surface; the inline Build/Sell buttons are removed from both tables.

Modal content:

- Prominent image (~200px; reuse `/assets/images/airport-assets/<image>`), with
  a graceful fallback when `image` is absent.
- Label / asset name.
- Boost type.
- Benefit description text (currently buried in the `title=` tooltip).
- Size requirement, level / maxLevel, status (Active / Building (N cycles)).
- Cost (next-level), build time, and for income-generating assets: weekly
  income, weekly net, payback (cycles). Mirrors the fields already computed in
  `renderAirportAssets`.
- One large, full-width primary button:
  - Catalog row: `Build` (when `ownedLevel === 0`) or `Upgrade to N`.
  - Built-asset row: `Sell ($X)` with the existing confirm prompt.
  - Disabled states with reason text: `Max level`, `Airport too small`,
    `Not enough cash`, or "build a base here first" when `!hasBase`. Reuse the
    exact predicates already in `renderAirportAssets`
    (`meetsSize`, `canUpgrade`, affordability, `hasBase`).

Actions call the existing `buildAirportAsset(airportId, assetType)` and
`sellAirportAsset(airportId, assetId)` handlers; the modal closes and the list
refreshes as those handlers already do.

### 3. Money abbreviation on mobile

Add an `abbreviateMoney(value)` helper (e.g. in `gadgets.js` next to
`commaSeparateNumber`) returning `$1.2M`, `$340K`, `$950` style strings. Use it
for the money cells in the airport tables when on a mobile viewport; the modal
shows full precision via `toLocaleString()` as today. Keep the helper pure and
unit-tested (mirrors existing `commaSeparateNumber` usage).

### 4. Tap targets

- Modal primary button: full width, min-height ~44px, using existing `--button-*`
  tokens.
- Airport table rows: enough row height to tap comfortably; add a `clickable`
  affordance (cursor/hover) consistent with other clickable rows.

## Affected files

- `public/stylesheets/mobile.css` — scoped airport horizontal-scroll rules,
  edge-fade, tap-target sizing.
- `app/views/fragments/modals.scala.html` — new `#airportAssetDetailsModal`.
- `public/javascripts/airport.js` — refactor `renderAirportAssets` (267-341) so
  rows attach `click → openAssetModal(...)`; add `openAssetModal()` to populate
  and show the modal and wire its action button; remove inline action buttons.
- `public/javascripts/gadgets.js` — `abbreviateMoney()` helper.
- (If needed) `app/views/fragments/airport_canvas.scala.html` — add a wrapping
  scroll container/class around tables that lack one, and a `min-width` hook.

## Verification

- **Unit:** Jest test for `abbreviateMoney()` (boundaries: <1K, K, M, B, exact
  thousands, zero/negative).
- **Mobile (Playwright, 390×844 viewport):** open the airport page; screenshot
  the assets, traffic-analytics, and statistics sections to confirm tables scroll
  horizontally rather than collapsing; tap an asset row → modal shows large image
  + full info + readable action button; verify a disabled/blocked state renders
  its reason.
- **Desktop regression:** confirm airport tables look unchanged except the
  removed inline action buttons, and the modal opens on row click.
- **Theme:** verify light and dark.
- Deploy path: push to master → OptiPlex deploy + Playwright (pre-authorized).
