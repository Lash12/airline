# Web Push Notifications Plan (Tier 2)

## Context

The game has a mature **in-app notification system** (the `notification` table,
`NotificationCategory` enum, `NotificationSource.insertNotification`, the bell drawer in
`notification.js`), extended several times (financial alerts, Phase G milestones, Phase N
World News). Notifications already carry an `expiryCycle`, and events like a failed
negotiation already emit one (`NEGOTIATION_LOSS`, keyed `targetId = "<fromId>-<toId>"`,
`expiryCycle = cycle + LinkNegotiationDiscount.DURATION (30)`), so time-based triggers such
as **"negotiation cooldown expired — ready to negotiate again"** are cheap to detect.

What does **not** exist yet is any way to reach the player when the game is **closed**. The
in-app bell only updates when the tab is open. "Tier 2" is real **Web Push**: the phone gets
a system notification even with the browser/app closed.

Implementation status: **P-0 through P-4 code is built, deployed on the OptiPlex behind
`solo.push.enabled`, and end-to-end validated on Firefox Android. HTTPS is provided by
Cloudflare Tunnel at `https://airline.ashhome.org`.** The motivating use case is a QOL nudge
("your RDU–LHR negotiation is off cooldown"), but the mechanism is generic: any configured
notification category can opt into being pushed.

Design rule (as with every solo feature): gate behind a `solo.*` flag defaulting off, so
default/multiplayer deploys are unchanged.

---

## ⚠️ Hard prerequisite: HTTPS (secure context)

Service workers and the Web Push API **only work in a secure context** — i.e. **HTTPS**, with
the sole exception of `localhost`. The LAN app still serves plain HTTP internally at
`http://192.168.1.52:9000`; external HTTPS terminates at Cloudflare and reaches the app through
the `airline-optiplex` tunnel.

1. **Reverse proxy with a real cert** — put Caddy/nginx in front of the Play app on LXC 202,
   terminate TLS with a Let's Encrypt cert for a real domain (needs a domain pointed at the
   host, or DNS-01 if no public ingress). Cleanest long-term.
2. **Tunnel with managed HTTPS** — expose via a Cloudflare Tunnel or Tailscale Funnel, both of
   which give a valid HTTPS hostname without opening ports. Low-friction; good fit for a
   self-hosted single-player box reached remotely.
3. **Trusted cert on a LAN hostname** — a cert (e.g. via an internal CA or mkcert) for a LAN
   name, trusted on the Samsung S24 Ultra. Works but fiddly to provision per-device.

Current choice: **Cloudflare Tunnel** with Cloudflare Access in front of
`airline.ashhome.org`.

---

## Goal

When a selected notification is created for the player's airline, deliver it as a **system
push** to the player's subscribed device(s), even with the app closed — opt-in, reliable, and
not spammy.

---

## Target environment (the user's setup)

- **Firefox on Samsung S24 Ultra (Android).** Firefox Android **does** support Web Push via
  Mozilla's autopush service (no Google/FCM account needed) — so this will work for the user.
- iOS Safari only supports Web Push for **installed PWAs**; out of scope (not the user's case),
  but the manifest below makes "Add to Home Screen" work if ever wanted.

---

## Architecture

```
[Play web app]                         [Browser on phone]
  serves sw.js + manifest.json  ───────▶ registers service worker
  exposes VAPID public key      ───────▶ PushManager.subscribe(applicationServerKey)
  POST /push-subscription       ◀─────── sends subscription (endpoint, p256dh, auth)
  store in push_subscription tbl
  ...later, on a new notification...
  web-push send (VAPID-signed)  ───────▶ Mozilla autopush ───▶ SW 'push' event ─▶ showNotification
```

- **Notifications are written by both the sim (airline-data) and the web (airline-web)** into
  the same `notification` table. The **push sender lives in airline-web** (it runs
  continuously, holds the VAPID private key, and can use a JVM web-push library). A scheduled
  task scans for new pushable notifications and sends them — the sim stays push-agnostic.

---

## Approach (implemented behind feature gate)

Everything gates behind **`solo.push.enabled`** (default `false`). New config in
`SoloConfig` / web config. Assumes the HTTPS prerequisite is met.

### P-0 — In-app trigger first (Tier 1; prerequisite content)
Land the **negotiation-cooldown-ready** in-app notification (new `NEGOTIATION_READY` category +
a per-cycle check that finds negotiation discounts/cooldowns expiring this cycle for player
airlines, emitting one notification). This is small, useful on its own, and gives Tier 2 its
first real thing to push. (Tracked separately; do this regardless of Tier 2.)

### P-1 — PWA shell (service worker + manifest)
- Add `airline-web/public/sw.js` (service worker) handling `push` and `notificationclick`
  events: on `push`, `self.registration.showNotification(title, { body, data, icon })`; on
  click, focus/open the app at the relevant page (e.g. the route/negotiation).
- Add `airline-web/public/manifest.json` (name, icons, `start_url`, display) and link it from
  `index.scala.html`.
- Register the SW from client JS only when `solo.push.enabled` and in a secure context.

Status: implemented in `sw.js`, `manifest.json`, `push.js`, and the manifest/static routes.

### P-2 — Subscription flow + storage
- **VAPID keys**: generate once; **public** key exposed to the client via a config endpoint or
  injected constant; **private** key kept server-side as a secret (env/`application.conf`,
  **never committed** — same discipline as the DB password).
- Client: a **settings toggle** ("Enable phone notifications") — must be user-initiated —
  requests `Notification.requestPermission()`, then `PushManager.subscribe({ userVisibleOnly:
  true, applicationServerKey: <VAPID public> })`, and POSTs the subscription JSON.
- New endpoint `POST /airlines/:airlineId/push-subscription` (+ DELETE to unsubscribe) in a
  controller; new table **`push_subscription(id, airline, endpoint, p256dh_key, auth_key,
  created_cycle)`** with `*Source` load/save (mirror `NotificationSource`). Created in
  `Meta.scala` like other tables.

Status: implemented with `PushApplication`, `PushSubscriptionSource`, and lazy
`CREATE TABLE IF NOT EXISTS` protection for existing databases.

### P-3 — Push sender
- Add a JVM push sender that signs with VAPID, encrypts payloads with Web Push `aes128gcm`,
  and posts to the endpoint.
- A **scheduled task** (Play Akka scheduler) every ~1 min: for each subscribed airline, load
  notifications created since a per-airline **push watermark** whose category is **pushable**;
  send one push each (or a small batch); advance the watermark. Persist the watermark (a column
  on `push_subscription` or a tiny table) so a restart doesn't double-send or miss.
- **Pushable categories are a allowlist** (config), defaulting to actionable ones
  (`NEGOTIATION_READY`, `CASH_FLOW_WARNING`, `MILESTONE_ACHIEVED`) — **not** `WORLD_NEWS`
  (too frequent → spam). Each pushed item carries `data.url` so the SW can deep-link.

Status: implemented with `PushNotificationScheduler`, `WebPushClient`, `PushPayload`, and
`solo.push.categories`.

### P-4 — Hardening / housekeeping
- On send, handle **410 Gone / 404** from the push service by deleting the dead subscription
  (browsers rotate endpoints). Back off on transient errors.
- Cap pushes per cycle per airline (anti-spam), and de-dupe by notification id.
- Settings UI shows subscribed/again-prompt state; allow disabling (DELETE subscription +
  unsubscribe client-side).

Status: implemented for 404/410 pruning, per-subscription notification watermarks,
`solo.push.maxPerSubscription`, and device unsubscribe.

---

## Critical files / new pieces

- **Infra (prereq):** HTTPS origin (reverse proxy or tunnel) — see prerequisite section.
- `airline-web/public/sw.js`, `airline-web/public/manifest.json` — NEW (PWA shell).
- `airline-web/app/views/index.scala.html` — link manifest; bootstrap SW registration.
- `airline-web/public/javascripts/notification.js` (or a new `push.js`) — permission +
  subscribe/unsubscribe + settings toggle.
- `airline-web/conf/routes` + a controller — `POST/DELETE /airlines/:id/push-subscription`,
  and a VAPID-public-key/config endpoint.
- `airline-data/.../data/PushSubscriptionSource.scala` + `Meta.scala` table — NEW storage.
- `airline-web/app/.../PushSender.scala` + scheduled task — NEW sender (web-push + VAPID).
- `airline-data/.../SoloConfig.scala` (or web config) — `solo.push.enabled`, pushable-category
  allowlist, VAPID keys (private from secret store).
- Reuse: `NotificationSource` (read new notifications), `NotificationCategory` (+
  `NEGOTIATION_READY` from P-0).

## Verified Production Result

Validated on 2026-06-20 against Lash Air (`airline_id=34`) on the OptiPlex deployment.

- Firefox Android successfully subscribed through the notification drawer.
- `https://airline.ashhome.org` served a valid 65-byte uncompressed P-256 VAPID public key.
- Manual workflow `Validate Web Push` inserted a `NEGOTIATION_READY` test notification and the
  push sender delivered it through Mozilla autopush.
- Passing workflow run: `27856841823`.
- Validation evidence: inserted notification `10136`; subscription `2` advanced to
  `last_pushed_notification_id = 10136` with `failure_count = 0`.
- User confirmed the phone received the test notifications.

## Phase 2 (admin tooling) — shipped 2026-06-20

`solo.push.adminAirlineId=34` (Lash Air) gates two additions, both reusing the existing
`AuthenticatedAirline` guard and delivery pipeline (no new send path):

- **Test-send button**: `POST /airlines/:id/push-test` inserts a real `NEGOTIATION_READY`
  notification ("Test push - connectivity check"); the existing `PushNotificationScheduler`
  tick delivers it within its normal interval (~60s), so the button exercises the actual
  production pipeline. Shown in the drawer only when `status` reports `isAdmin: true`.
- **`GET /airlines/:id/push-summary`**: bare JSON (no UI) returning per-subscription
  `{id, lastPushedNotificationId, failureCount, userAgent, createdCycle}` plus in-process
  scheduler counters (`sent`/`failed`/`pruned`/`lastTickAt`, reset on JVM restart — pruned
  subscriptions are hard-deleted, so there's no persisted history beyond these counters).

See `docs/superpowers/specs/2026-06-20-web-push-completeness-design.md` for the full design
and `docs/current-development-state.md` for what's been verified live vs. still needs a
device/session-based spot-check.

Issues found and fixed during validation:

- Initial VAPID public key was rejected by Firefox as an invalid raw ECDSA P-256 key; the keypair
  was rotated in GitHub secrets.
- New subscriptions originally started with `last_pushed_notification_id = 0`, causing historical
  pushable notifications to be sent before the fresh test event. New subscriptions now start at
  the current notification high-water mark.
- Mozilla autopush rejected server sends with `401 InvalidSignature`; root cause was incorrect
  DER-to-JOSE conversion of ECDSA signatures in `WebPushClient`.
- The validation workflow now tails `/home/airline/web.log` inside `airline-app` for push sender
  diagnostics.

## Verification Checklist

- **Secure context**: confirm the app loads over HTTPS on the phone and the SW registers
  (`navigator.serviceWorker.controller` non-null).
- **Subscription round-trip**: toggle on → permission granted → subscription row stored;
  toggle off → row removed + client unsubscribed.
- **End-to-end push**: trigger a `NEGOTIATION_READY` notification (or a test endpoint) →
  phone shows a system notification with the app **closed**; tapping deep-links to the route.
- **Resilience**: kill/rotate a subscription → sender prunes it on 410; sim/web restart does
  not double-send (watermark holds); default deploy with `solo.push.enabled=false` is
  byte-identical (no SW registered, no table writes, no scheduled sends).
- **Anti-spam**: a burst of `WORLD_NEWS` produces **no** pushes (not on the allowlist); cap
  respected.

Manual production validation:

```powershell
gh workflow run "Validate Web Push" --repo Lash12/airline --ref master -f airline_id=34
gh run watch <run-id> --repo Lash12/airline --exit-status
```

Direct OptiPlex diagnostics:

```powershell
ssh airline-dev "docker exec airline-app sh -c 'grep -ai \"\\[push\\]\" /home/airline/web.log | tail -40'"
ssh airline-dev "docker exec airline-db mysql -u\"\$(docker exec airline-db printenv MYSQL_USER)\" -p\"\$(docker exec airline-db printenv MYSQL_PASSWORD)\" \"\$(docker exec airline-db printenv MYSQL_DATABASE)\" -e 'SELECT id, airline, last_pushed_notification_id, failure_count FROM push_subscription ORDER BY id;'"
```

## Out of scope (future)

- iOS Safari push (needs installed PWA) — revisit only if an iOS device is used.
- Rich/actionable push buttons (e.g. "Negotiate now" action) — nice-to-have after the basics.
- Multi-device fan-out beyond a couple of subscriptions — already supported by storing N rows,
  but no UI to manage devices initially.
- Replacing the in-app bell — Web Push augments it, never replaces it.

## Notes / risks

- **HTTPS is the real gate** — without it, none of P-1..P-4 function. Settle the origin first.
- **VAPID private key is a secret** — store like the DB password (env/config, never in git or
  in files pushed to the host).
- **Permission is one-shot-ish** — if the user denies, the browser won't re-prompt easily;
  the settings toggle should explain how to re-enable in site settings.
- Firefox Android push is reliable but **endpoints rotate** — the 410-prune in P-4 is not
  optional for long-term reliability.
