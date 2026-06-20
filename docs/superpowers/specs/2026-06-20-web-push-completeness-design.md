# Web Push Completeness — Design

Status: Approved 2026-06-20. Follows up on `docs/web-push-notifications-plan.md`
(Tier 2 push infra, already shipped/validated) and the "Suggested Next Feature
Phase" note in `docs/current-development-state.md`.

## Context

Web Push (P-0 through P-4) is implemented, deployed behind `solo.push.enabled`,
and was end-to-end validated on Firefox Android via a manually-inserted test
notification. Three gaps remain before it's pleasant to use day to day rather
than something that "works when manually tested":

1. The settings UI leaks raw developer diagnostics to every user and gives no
   actionable guidance when the browser denies notification permission.
2. The `NEGOTIATION_READY` push has only been exercised via a manual test
   insert, never observed firing from a real simulation cycle.
3. There's no way to check push health (sends/failures/pruned subscriptions)
   without SSH + a hand-typed `mysql` query.

This phase closes all three without adding new tables, new send paths, or new
UI surfaces beyond what's strictly needed.

## Phase 1 — Settings UX cleanup

File: `airline-web/public/javascripts/push.js`

- `renderPushDiagnostic` currently writes a raw debug string (`diag
  push-20260620-0102 step=... perm=... browser=... server=...`) into the
  visible `pushNotificationDetail` element for every user. Change it to show
  clean, user-facing copy by default ("Enabled on this device", "Off",
  "Could not enable"). The existing `window.pushDebugState` global remains the
  full diagnostic surface (console-accessible); additionally, show the raw
  diagnostic text in the UI when the page URL has `?pushDebug=1`.
- `userFacingPushError` collapses any permission issue to "Permission
  denied" with no remediation step. Detect `Notification.permission ===
  'denied'` proactively (not just after a failed subscribe attempt) and show:
  "Notifications blocked — enable them in your browser's site settings, then
  reload." Wording is generic (not Firefox-Android-specific) since the fix is
  always "browser site settings," just reached differently per browser.
- No change to the subscribe/unsubscribe control flow, service worker
  registration, or server endpoints.

## Phase 2 — Negotiation-ready verification

No code design — this is a verification task, not a build task.

`AirlineSimulation.scala:40` already calls `NegotiationReadyNotifier.emit(cycle)`
every real simulation cycle. `PushPayload.urlFor` already turns the
`"{fromAirportId}-{toAirportId}"` `targetId` into a working
`/?planLinkFrom=X&planLinkTo=Y` deep link, and `push.js`'s
`handlePushDeepLink` already consumes those query params on load. There's an
existing unit test (`NegotiationReadyNotifierSpec`).

After Phase 1 and 3 are deployed, observe a real (non-test-inserted)
`NEGOTIATION_READY` push fire from an actual negotiation cooldown expiry on
the OptiPlex. Confirm the notification copy and tap-to-deep-link behavior are
correct end to end. Fix anything that looks wrong; otherwise this phase is
just a confirmation, logged in `docs/current-development-state.md`.

## Phase 3 — Admin-gated test-send + observability

Files: `airline-data/src/main/scala/com/patson/data/SoloConfig.scala`,
`airline-web/app/push/PushConfig.scala`, `airline-web/app/push/
PushNotificationScheduler.scala`, `airline-web/app/controllers/
PushApplication.scala`, `airline-web/conf/routes`,
`airline-web/public/javascripts/push.js`.

### Config

New key `solo.push.adminAirlineId` (`Option[Int]`, unset by default). Lives
alongside the other `solo.push.*` keys in `PushConfig`. When unset, the new
endpoints below return `Forbidden` for everyone and the UI never shows the
test-send button — default/multiplayer-equivalent behavior is unchanged.

### Test-send

- New route: `POST /airlines/:airlineId/push-test`.
- New controller action in `PushApplication`, using the existing
  `AuthenticatedAirline(airlineId)` guard, then checking `airlineId ==
  adminAirlineId` (from config) — otherwise `Forbidden`.
- On success: insert one real notification via the existing
  `NotificationSource` insert path, category `NEGOTIATION_READY`, message
  "Test push — connectivity check", no `targetId` (so `PushPayload.urlFor`
  falls through to `/`). No direct call into `WebPushClient` — the existing
  `PushNotificationScheduler` tick (≤ ~60s later, per
  `solo.push.intervalSeconds`) picks it up and delivers it exactly like a
  real production notification. This means the "test" exercises the actual
  delivery pipeline rather than a synthetic shortcut.
- UI: in `push.js`, when `pushState.status` indicates the logged-in airline is
  the configured admin (the `/airlines/:id/push-subscription` status response
  gains an `isAdmin: Boolean` field for this purpose), render a "Send test
  notification" button in the drawer that POSTs to the new route and shows a
  brief "Sent — should arrive within a minute" acknowledgment.

### Observability

- New route: `GET /airlines/:airlineId/push-summary`, same
  `AuthenticatedAirline` + `adminAirlineId` gate as test-send.
- Returns JSON: `subscriptions` (array of `{id, lastPushedNotificationId,
  failureCount, userAgent, createdCycle}` from `PushSubscriptionSource.loadAll()`),
  plus `scheduler` (in-process counters on `PushNotificationScheduler` since
  the last process start: `sent`, `failed`, `pruned`, `lastTickAt`). The
  in-process counters exist because pruned subscriptions are hard-deleted
  today — there is no DB history of them otherwise — and adding persisted
  history is out of scope for this phase (counters resetting on restart is an
  accepted limitation, not a bug).
- No new UI page or drawer section for this — it's a bare JSON endpoint, hit
  directly via browser or curl, per the explicit choice to keep this lightweight.

## Out of scope

- Any new DB table or column (no persisted pruned-subscription history beyond
  in-process counters).
- iOS Safari push, rich/actionable push buttons, multi-device management UI —
  already out of scope per the original Tier 2 plan.
- A visual/in-game observability panel — explicitly declined in favor of the
  bare JSON endpoint.
- Browser-specific (e.g. Firefox-Android-specific) permission-denied copy —
  generic guidance only.

## Testing

- `NegotiationReadyNotifierSpec` already covers Phase 2's emission logic; no
  new data-side tests needed unless the live verification in Phase 2 surfaces
  a bug.
- New `PushApplication` controller tests (none currently exist for this
  controller) covering: test-send route returns `Forbidden` when
  `adminAirlineId` is unset or doesn't match the caller; returns success and
  inserts a notification when it matches. Same `Forbidden`/success shape for
  the summary route.
- `PushConfig` gains `adminAirlineId` parsing; extend its existing config
  parsing tests (mirroring how `categories`/`maxPerSubscription` are tested)
  to cover the unset/set cases.
- `push.js` UI changes (diagnostic gating, permission-denied copy, test-send
  button) are verified manually against a real deploy per the project's
  existing convention (no JS unit test scaffolding currently covers `push.js`
  specifically); Playwright `e2e/` is not extended for this, since these are
  account-gated, device-permission-dependent flows that don't fit the
  existing smoke-test style.

## Rollout

Each phase ships as its own commit/deploy to `master` (matches existing
practice — every push to `master` triggers `CI` + `OptiPlex Deploy & Verify`).
Verify each phase live on the OptiPlex (`192.168.1.52`) before moving to the
next, per the user's stated preference for testing on the real dev box over
local Docker. No flag changes affect default/multiplayer deploys since
`solo.push.adminAirlineId` defaults unset and `solo.push.enabled` defaults
false.
