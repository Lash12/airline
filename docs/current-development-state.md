# Current Development State

Last updated: 2026-06-20

## Repository / Environment

- Main repo path: `C:\Users\logan\OneDrive\Desktop\Airline\airline`.
- LAN deployment target: OptiPlex at `192.168.1.52`.
- SSH is configured from the Windows/Codex environment:
  - `ssh airline-dev "hostname && docker ps --format '{{.Names}} {{.Status}}'"`
  - Expected containers: `airline-cloudflared`, `airline-app`, `airline-db`.
- Cloudflare Tunnel + Access exposes the app at `https://airline.ashhome.org`.
- OptiPlex deploys are handled by GitHub Actions workflow `OptiPlex Deploy & Verify`.

## Local Tooling

The Windows host now has the core development tools installed:

- Java 17: `C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot`
- sbt: `C:\Program Files (x86)\sbt\bin\sbt.bat`
- Node/npm: verified with Node `v24.17.0`, npm `11.13.0`
- ripgrep: verified
- Playwright package in `e2e`: verified at `1.58.0`

This Codex session had an old PATH, so Java/sbt needed to be injected manually. Future sessions
should see them normally after restart. If not:

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
$env:Path="$env:JAVA_HOME\bin;C:\Program Files (x86)\sbt\bin;$env:Path"
```

Local Scala compile sequence verified:

```powershell
cd C:\Users\logan\OneDrive\Desktop\Airline\airline\airline-data
sbt publishLocal

cd C:\Users\logan\OneDrive\Desktop\Airline\airline\airline-web
sbt compile
```

`airline-data publishLocal` and `airline-web compile` both passed locally after setting the
tool paths. Existing warnings are non-blocking.

## Web Push Notifications

Web Push is implemented, deployed, and end-to-end validated for Lash Air on Firefox Android.

Important commits from the validation/fix sequence:

- `f78ca6ce fix(web): reload open clients on new deploy`
- `036e9009 chore(web): add web push validation workflow`
- `f8c9725a fix(web): start push subscriptions at current notification watermark`
- `473c86d6 ci: keep build targets out of optiplex source cleanup`
- `c1aa6460 chore(web): log failed web push responses`
- `c20241e9 ci: collect web log in push validation`
- `ea0d45c8 fix(web): encode VAPID ECDSA signatures correctly`

Key fixes:

- Rotated malformed VAPID keypair in GitHub secrets.
- Fixed mobile browser cache/deploy refresh via `/build-info`.
- Fixed new push subscriptions to start at the current notification high-water mark, avoiding
  a backlog of historical pushable notifications.
- Fixed VAPID ECDSA DER-to-JOSE signature conversion. Before this, Mozilla autopush returned
  `401 InvalidSignature`.
- Added failed push response logging in `PushNotificationScheduler`.
- Added manual workflow `Validate Web Push` for end-to-end production validation.

Validated evidence:

- OptiPlex deploy workflow passed after the final fixes.
- `Validate Web Push` workflow run `27856841823` passed.
- The validation inserted notification `10136`.
- Subscription `2` advanced to `last_pushed_notification_id = 10136` with `failure_count = 0`.
- User confirmed the Android Firefox device received the test notifications.

Manual validation workflow:

```powershell
gh workflow run "Validate Web Push" --repo Lash12/airline --ref master -f airline_id=34
gh run watch <run-id> --repo Lash12/airline --exit-status
```

Direct OptiPlex checks now possible over SSH:

```powershell
ssh airline-dev "docker exec airline-app sh -c 'grep -ai \"\\[push\\]\" /home/airline/web.log | tail -40'"
ssh airline-dev "docker exec airline-db mysql -u\"\$(docker exec airline-db printenv MYSQL_USER)\" -p\"\$(docker exec airline-db printenv MYSQL_PASSWORD)\" \"\$(docker exec airline-db printenv MYSQL_DATABASE)\" -e 'SELECT id, airline, last_pushed_notification_id, failure_count FROM push_subscription ORDER BY id;'"
```

## Deployment Guardrails

- Do not run destructive Docker volume operations against the OptiPlex database.
- MySQL persistence must remain mounted at `/bitnami/mysql` for the Bitnami legacy image.
- Prefer `scripts/optiplex-deploy.sh` / `OptiPlex Deploy & Verify` for deploys.
- SSH can be used for read-only checks, logs, DB inspection, and one-off validation.
- If changing Scala locally, run `airline-data publishLocal` before compiling `airline-web`.

## Known Worktree Notes

The local worktree had pre-existing unrelated dirty files before the web-push validation work.
Do not revert or commit unrelated changes unless the user explicitly asks. Check `git status`
before each commit and stage only the files relevant to the current task.

Untracked `.sbt-boot/` may appear under `airline-web` from local sbt boot-cache testing and
should not be committed.

## Web Push Completeness Phase — shipped 2026-06-20

Followed `docs/superpowers/specs/2026-06-20-web-push-completeness-design.md` /
`docs/superpowers/plans/2026-06-20-web-push-completeness.md`. Deployed and verified live on
the OptiPlex in two pushes:

- Settings UX cleanup: the push status line no longer shows raw developer diagnostics by
  default (now gated behind `?pushDebug=1`); a denied browser permission now shows actionable
  guidance ("enable in your browser's site settings, then reload") instead of just "Permission
  denied". Verified the deployed `push.js` asset contains the new code; full visual/permission-flow
  behavior still needs a one-time spot-check on a real device (Firefox Android) next time the
  user is on their phone.
- Admin-gated test-send + observability: new `solo.push.adminAirlineId` config (set to `34`,
  Lash Air, in the OptiPlex deploy workflow) gates a "Send test notification" button in the
  drawer (`POST /airlines/:id/push-test`) and a `GET /airlines/:id/push-summary` JSON endpoint
  (per-subscription failure/watermark state plus in-process scheduler counters). Verified both
  routes are registered and correctly return 401 without a session; verified the push sender
  started cleanly post-deploy (`[push] sender enabled for categories NEGOTIATION_READY,...`).
  The actual button-click-to-phone-notification round trip still needs the user to try it from
  a logged-in session — not yet exercised end-to-end.
- `NEGOTIATION_READY` live-cycle verification (the original item: confirming a real
  cooldown-driven push, not just a test insert) was **not completed** in this pass. See the
  session report for why. `AirlineSimulation.scala:40` already calls
  `NegotiationReadyNotifier.emit(cycle)` every cycle and `PushPayload.urlFor` already builds the
  correct deep link, so this is believed to already work; it just hasn't been observed firing
  from a real (non-test) cooldown expiry yet.

## Suggested Next Feature Phase

With Web Push now product-complete, the next natural candidates (pick one, don't combine):

- Finish the `NEGOTIATION_READY` live-cycle spot-check noted above.
- Resume the performance roadmap's Phase 3 (cheaper cycles): demand memoization and
  `PassengerSimulation` early-exit-on-zero-capacity are still open per
  `docs/single-player-performance-roadmap.md`.
