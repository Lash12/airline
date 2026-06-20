# Web Push Completeness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the already-shipped Web Push feature (single-player notifications, gated `solo.push.enabled`) pleasant to use day to day: clean status UX, a verified real (non-test-insert) negotiation-ready push, and an admin-gated way to trigger a test push and check push health without SSH.

**Architecture:** No new tables, no new send paths. Reuses the existing `AuthenticatedAirline` guard, `NotificationSource` insert path, and `PushNotificationScheduler` delivery loop. A new `solo.push.adminAirlineId` config value gates two new endpoints (`push-test`, `push-summary`) to a single configured airline ID. All changes are confined to `airline-web` — no `airline-data` changes, so no `publishLocal` round-trip is needed.

**Tech Stack:** Scala 2.13 / Play Framework (`airline-web`), ScalaTest (`AnyWordSpec` + `Matchers`, no Play test harness exists in this codebase), vanilla JS (`push.js`, no build step), GitHub Actions self-hosted deploy (`OptiPlex Deploy & Verify`).

**Spec:** `docs/superpowers/specs/2026-06-20-web-push-completeness-design.md`

---

## Before you start

This plan assumes the working tree is `C:\Users\logan\Desktop\Airline\airline` (confirm with `git remote -v` — `origin` must be `https://github.com/Lash12/airline.git`). Work directly on `master`, matching this repo's established practice (every commit in `git log` goes straight to master; there's no feature-branch/PR workflow here). Every `git push` to `master` automatically triggers both the `CI` and `OptiPlex Deploy & Verify` GitHub Actions workflows on the user's self-hosted OptiPlex box at `192.168.1.52` (SSH: `root` — credentials already available in this session, do not echo the password back into chat or write it to any file).

### Task 0: Verify the checkout builds

**Files:** none (verification only)

- [ ] **Step 1: Publish airline-data locally**

Run:
```
cd C:\Users\logan\Desktop\Airline\airline\airline-data && sbt publishLocal
```
Expected: `[success]` at the end. This plan makes no `airline-data` changes, but `airline-web` depends on its published artifact and this checkout has never been built (no `target/` directory existed before this task).

- [ ] **Step 2: Compile airline-web**

Run:
```
cd C:\Users\logan\Desktop\Airline\airline\airline-web && sbt compile
```
Expected: `[success]`. Existing warnings are non-blocking (matches `docs/current-development-state.md`).

---

## Phase A — Settings UX cleanup

### Task 1: Gate the diagnostic line and add permission-denied guidance

**Files:**
- Modify: `airline-web/public/javascripts/push.js`
- Modify: `airline-web/public/stylesheets/prompt.css` (no change needed in this task — included for context only, skip)

No automated test exists or is being added for this file (no JS test harness covers `push.js` in this codebase; verified manually against the live deploy in Task 3).

- [ ] **Step 1: Gate the raw diagnostic text behind `?pushDebug=1`**

In `airline-web/public/javascripts/push.js`, replace the `renderPushDiagnostic` function (currently lines 248–263):

```javascript
    function debugQueryEnabled() {
        return new URLSearchParams(window.location.search).get('pushDebug') === '1'
    }

    function renderPushDiagnostic(detail) {
        if (!detail) return
        if (!debugQueryEnabled()) {
            detail.textContent = ''
            return
        }
        var serverSubscribed = pushState.status && pushState.status.subscribed
        var permission = typeof Notification !== 'undefined' ? Notification.permission : 'unavailable'
        if (!pushState.attemptedSubscribe && !pushState.error && !window.pushLastError) {
            detail.textContent = PUSH_DEBUG_VERSION
            return
        }
        detail.textContent = 'diag ' + PUSH_DEBUG_VERSION +
            ' step=' + (pushState.lastAction || 'init') +
            ' perm=' + permission +
            ' browser=' + (!!pushState.subscription) +
            ' server=' + (!!serverSubscribed) +
            (pushState.error ? ' error=' + pushState.error : '') +
            (window.pushLastError ? ' raw=' + window.pushLastError.slice(0, 80) : '')
    }
```

- [ ] **Step 2: Proactively show permission-denied guidance**

In the same file, in `renderPushStatus` (currently starting at line 95), insert a new branch right after the existing `reason` block and before `if (row) { row.style.display = 'flex' }`. The function should read:

```javascript
    function renderPushStatus(statusOverride) {
        appendPushToggle()
        var row = document.getElementById('pushNotificationSetting')
        var toggle = document.getElementById('pushNotificationToggle')
        var label = document.getElementById('pushNotificationStatus')
        var detail = document.getElementById('pushNotificationDetail')
        if (!toggle || !label) return

        var reason = pushUnavailableReason()
        if (reason) {
            if (row) {
                row.style.display = reason === 'disabled' || reason === 'not-configured' ? 'none' : 'flex'
            }
            toggle.checked = false
            toggle.disabled = true
            label.textContent = reason === 'disabled' ? 'Off on this server' :
                reason === 'insecure-context' ? 'Requires HTTPS' :
                reason === 'not-configured' ? 'Needs VAPID keys' : 'Unsupported browser'
            renderPushDiagnostic(detail)
            return
        }

        if (typeof Notification !== 'undefined' && Notification.permission === 'denied') {
            if (row) row.style.display = 'flex'
            toggle.checked = false
            toggle.disabled = true
            label.textContent = "Blocked - enable in your browser's site settings, then reload"
            renderPushDiagnostic(detail)
            return
        }

        if (row) {
            row.style.display = 'flex'
        }
        toggle.disabled = false
        var saving = statusOverride === 'saving' || (!statusOverride && pushState.saving)
        toggle.checked = saving ? true : !!pushState.subscription || !!(pushState.status && pushState.status.subscribed)
        label.textContent = saving ? 'Saving...' :
            statusOverride === 'error' ? pushState.error || 'Could not enable' :
            toggle.checked ? 'Enabled on this device' :
            pushState.attemptedSubscribe ? 'Off - not saved' : 'Off'
        renderPushDiagnostic(detail)
        updatePushDebugState()
    }
```

- [ ] **Step 3: Give `userFacingPushError` the same actionable wording**

In the same file, in `userFacingPushError` (currently lines 219–229), change the permission line:

```javascript
        if (/permission/i.test(message)) return "Blocked - enable in your browser's site settings, then reload"
```
(leave the other three `if` lines and the function signature unchanged)

- [ ] **Step 4: Sanity-check the file parses**

Run:
```
cd C:\Users\logan\Desktop\Airline\airline\airline-web && node --check public/javascripts/push.js
```
Expected: no output (silent success means valid syntax). This is the fastest available check — there's no JS test runner wired up for this file.

- [ ] **Step 5: Commit**

```
cd C:\Users\logan\Desktop\Airline\airline && git add airline-web/public/javascripts/push.js && git commit -m "fix(web): clean up push status UX and add permission-denied guidance"
```

### Task 2: Ship Phase A and verify live

**Files:** none (deploy + verification only)

- [ ] **Step 1: Push to master**

```
cd C:\Users\logan\Desktop\Airline\airline && git push origin master
```

- [ ] **Step 2: Watch CI**

```
gh run list --repo Lash12/airline --branch master --limit 2
```
Take the run ID for `CI` from this push and:
```
gh run watch <ci-run-id> --repo Lash12/airline --exit-status
```
Expected: exit code 0.

- [ ] **Step 3: Watch the OptiPlex deploy**

```
gh run list --repo Lash12/airline --branch master --limit 2
```
Take the run ID for `OptiPlex Deploy & Verify` from this push and:
```
gh run watch <deploy-run-id> --repo Lash12/airline --exit-status
```
Expected: exit code 0 (this also runs the Playwright e2e suite against the live container as part of the workflow).

- [ ] **Step 4: Confirm the new code is actually being served**

```
ssh root@192.168.1.52 "docker exec airline-app sh -c 'curl -s http://localhost:9000/assets/javascripts/push.js | grep -c debugQueryEnabled'"
```
Note: if the asset is fingerprinted/versioned by Play's asset pipeline, adjust the path by checking `ssh root@192.168.1.52 "docker exec airline-app sh -c 'find /home/airline -name push.js'"` first. Expected: the grep finds the new `debugQueryEnabled` function name in the served file (count ≥ 1), confirming the deploy picked up the change.

- [ ] **Step 5: Note the limitation**

This confirms the deploy is healthy and serving the new code. It does **not** confirm the visual/permission-flow behavior — that requires a real browser with the Notification permission API (Firefox Android, per the target device). Leave a one-line note for the user to spot-check next time they're on their phone: open the notification drawer and confirm the status line no longer shows raw `diag ...` text, and (only if they've previously denied the permission) confirm the new "enable in your browser's site settings" message appears.

---

## Phase B — Admin-gated test-send + observability

### Task 3: `PushConfig.adminAirlineId` + `isAdmin` (TDD)

**Files:**
- Modify: `airline-web/app/push/PushConfig.scala`
- Create: `airline-web/test/push/PushConfigSpec.scala`

- [ ] **Step 1: Write the failing test**

Create `airline-web/test/push/PushConfigSpec.scala`:

```scala
package push

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration

class PushConfigSpec extends AnyWordSpec with Matchers {

  def configFrom(raw: String): Configuration = Configuration(ConfigFactory.parseString(raw))

  "PushConfig.from" should {
    "default adminAirlineId to None when unset" in {
      PushConfig.from(configFrom("")).adminAirlineId shouldBe None
    }

    "parse adminAirlineId when set" in {
      PushConfig.from(configFrom("solo.push.adminAirlineId = 34")).adminAirlineId shouldBe Some(34)
    }
  }

  "PushConfig.isAdmin" should {
    "be false when adminAirlineId is unset" in {
      val config = PushConfig.from(configFrom(""))
      PushConfig.isAdmin(34, config) shouldBe false
    }

    "be true only for the configured admin airline id" in {
      val config = PushConfig.from(configFrom("solo.push.adminAirlineId = 34"))
      PushConfig.isAdmin(34, config) shouldBe true
      PushConfig.isAdmin(35, config) shouldBe false
    }
  }
}
```

- [ ] **Step 2: Run it and confirm it fails to compile**

Run:
```
cd C:\Users\logan\Desktop\Airline\airline\airline-web && sbt "testOnly *PushConfigSpec"
```
Expected: compile error — `value adminAirlineId is not a member of push.PushConfig` (and `isAdmin is not a member of object push.PushConfig`).

- [ ] **Step 3: Implement**

Replace the full contents of `airline-web/app/push/PushConfig.scala`:

```scala
package push

import com.patson.model.NotificationCategory
import play.api.Configuration

import scala.jdk.CollectionConverters._

case class PushConfig(
  enabled: Boolean,
  vapidPublicKey: String,
  vapidPrivateKey: String,
  vapidSubject: String,
  categories: Seq[NotificationCategory.Value],
  maxPerSubscription: Int,
  intervalSeconds: Int,
  adminAirlineId: Option[Int]
)

object PushConfig {
  def from(configuration: Configuration): PushConfig = {
    val config = configuration.underlying
    def bool(path: String, default: Boolean): Boolean =
      if (config.hasPath(path)) config.getBoolean(path) else default
    def string(path: String, default: String): String =
      if (config.hasPath(path)) config.getString(path) else default
    def int(path: String, default: Int): Int =
      if (config.hasPath(path)) config.getInt(path) else default
    def intOpt(path: String): Option[Int] =
      if (config.hasPath(path)) Some(config.getInt(path)) else None

    val categories =
      if (config.hasPath("solo.push.categories")) {
        config.getStringList("solo.push.categories").asScala.toSeq.flatMap { name =>
          NotificationCategory.values.find(_.toString == name)
        }
      } else {
        Seq(
          NotificationCategory.NEGOTIATION_READY,
          NotificationCategory.CASH_FLOW_WARNING,
          NotificationCategory.MILESTONE_ACHIEVED
        )
      }

    PushConfig(
      enabled = bool("solo.push.enabled", false),
      vapidPublicKey = string("solo.push.vapidPublicKey", ""),
      vapidPrivateKey = string("solo.push.vapidPrivateKey", ""),
      vapidSubject = string("solo.push.vapidSubject", "mailto:admin@myfly.club"),
      categories = categories,
      maxPerSubscription = int("solo.push.maxPerSubscription", 3),
      intervalSeconds = int("solo.push.intervalSeconds", 60),
      adminAirlineId = intOpt("solo.push.adminAirlineId")
    )
  }

  def configuredForDelivery(config: PushConfig): Boolean =
    config.enabled && config.vapidPublicKey.nonEmpty && config.vapidPrivateKey.nonEmpty

  def isAdmin(airlineId: Int, config: PushConfig): Boolean =
    config.adminAirlineId.contains(airlineId)
}
```

- [ ] **Step 4: Run it and confirm it passes**

Run:
```
cd C:\Users\logan\Desktop\Airline\airline\airline-web && sbt "testOnly *PushConfigSpec"
```
Expected: `4 examples, 0 failure`.

- [ ] **Step 5: Commit**

```
cd C:\Users\logan\Desktop\Airline\airline && git add airline-web/app/push/PushConfig.scala airline-web/test/push/PushConfigSpec.scala && git commit -m "feat(push): add solo.push.adminAirlineId gate config"
```

### Task 4: `push-test` endpoint

**Files:**
- Modify: `airline-web/app/controllers/PushApplication.scala`
- Modify: `airline-web/conf/routes`

No automated test (no Play test harness in this codebase — see spec's Testing section). Verified live in Task 8.

- [ ] **Step 1: Add the route**

In `airline-web/conf/routes`, after line 140 (`DELETE   /airlines/:airlineId/push-subscription  controllers.PushApplication.unsubscribe(airlineId : Int)`), add:

```
POST     /airlines/:airlineId/push-test        controllers.PushApplication.testSend(airlineId : Int)
GET      /airlines/:airlineId/push-summary     controllers.PushApplication.summary(airlineId : Int)
```

- [ ] **Step 2: Implement `status` admin flag + `testSend` + `summary` actions**

Replace the full contents of `airline-web/app/controllers/PushApplication.scala`:

```scala
package controllers

import com.patson.data.{CycleSource, NotificationSource, PushSubscriptionSource}
import com.patson.model.{Notification, NotificationCategory, PushSubscription}
import controllers.AuthenticationObject.AuthenticatedAirline
import javax.inject.Inject
import play.api.Configuration
import play.api.libs.json._
import play.api.mvc._
import push.{PushConfig, PushNotificationScheduler}

class PushApplication @Inject()(cc: ControllerComponents, configuration: Configuration, scheduler: PushNotificationScheduler) extends AbstractController(cc) {
  private def pushEnabled: Boolean =
    configuration.underlying.hasPath("solo.push.enabled") && configuration.underlying.getBoolean("solo.push.enabled")

  private def pushConfig: PushConfig = PushConfig.from(configuration)

  def status(airlineId: Int) = AuthenticatedAirline(airlineId) { _ =>
    val subscriptions = PushSubscriptionSource.loadByAirline(airlineId)
    Ok(Json.obj(
      "enabled" -> pushEnabled,
      "subscribed" -> subscriptions.nonEmpty,
      "subscriptionCount" -> subscriptions.size,
      "isAdmin" -> PushConfig.isAdmin(airlineId, pushConfig)
    ))
  }

  def subscribe(airlineId: Int) = AuthenticatedAirline(airlineId) { request =>
    if (!pushEnabled) {
      BadRequest("Push notifications are disabled")
    } else {
      request.body.asJson match {
        case None => BadRequest("Subscription JSON body is required")
        case Some(json) =>
          val endpoint = (json \ "endpoint").asOpt[String].filter(_.nonEmpty)
          val keys = json \ "keys"
          val p256dh = (keys \ "p256dh").asOpt[String].filter(_.nonEmpty)
          val auth = (keys \ "auth").asOpt[String].filter(_.nonEmpty)

          (endpoint, p256dh, auth) match {
            case (Some(endpointValue), Some(p256dhValue), Some(authValue)) =>
              val saved = PushSubscriptionSource.upsert(PushSubscription(
                airlineId = airlineId,
                endpoint = endpointValue,
                p256dhKey = p256dhValue,
                authKey = authValue,
                createdCycle = CycleSource.loadCycle(),
                lastPushedNotificationId = NotificationSource.latestNotificationId(airlineId),
                userAgent = request.headers.get("User-Agent")
              ))
              Ok(Json.obj("subscribed" -> true, "id" -> saved.id))
            case _ =>
              BadRequest("Subscription endpoint and keys are required")
          }
      }
    }
  }

  def unsubscribe(airlineId: Int) = AuthenticatedAirline(airlineId) { request =>
    val endpoint = request.body.asJson.flatMap(json => (json \ "endpoint").asOpt[String])
    val deleted = endpoint.map(PushSubscriptionSource.deleteByEndpoint(airlineId, _)).getOrElse {
      PushSubscriptionSource.loadByAirline(airlineId).map(s => PushSubscriptionSource.delete(s.id)).sum
    }
    Ok(Json.obj("subscribed" -> false, "deleted" -> deleted))
  }

  def testSend(airlineId: Int) = AuthenticatedAirline(airlineId) { _ =>
    if (!PushConfig.isAdmin(airlineId, pushConfig)) {
      Forbidden("Not authorized for test sends")
    } else {
      NotificationSource.insertNotification(Notification(
        airlineId,
        NotificationCategory.NEGOTIATION_READY,
        "Test push - connectivity check",
        CycleSource.loadCycle()
      ))
      Ok(Json.obj("queued" -> true))
    }
  }

  def summary(airlineId: Int) = AuthenticatedAirline(airlineId) { _ =>
    if (!PushConfig.isAdmin(airlineId, pushConfig)) {
      Forbidden("Not authorized")
    } else {
      val subscriptions = PushSubscriptionSource.loadAll().map { s =>
        Json.obj(
          "id" -> s.id,
          "airlineId" -> s.airlineId,
          "lastPushedNotificationId" -> s.lastPushedNotificationId,
          "failureCount" -> s.failureCount,
          "userAgent" -> s.userAgent,
          "createdCycle" -> s.createdCycle
        )
      }
      Ok(Json.obj(
        "subscriptions" -> subscriptions,
        "scheduler" -> Json.obj(
          "sent" -> scheduler.sentCount,
          "failed" -> scheduler.failedCount,
          "pruned" -> scheduler.prunedCount,
          "lastTickAt" -> scheduler.lastTickAt
        )
      ))
    }
  }
}
```

(`scheduler.sentCount` etc. don't exist yet — that's Task 5. This file will not compile until Task 5 is done; that's expected, do both before running a build.)

- [ ] **Step 3: Commit happens at the end of Task 5** (these two tasks compile as a unit)

### Task 5: `PushNotificationScheduler` counters

**Files:**
- Modify: `airline-web/app/push/PushNotificationScheduler.scala`

No automated test (the class is constructed via `@Inject()` against a live actor system with no existing test coverage). Verified live in Task 8 via the `/push-summary` endpoint.

- [ ] **Step 1: Implement**

Replace the full contents of `airline-web/app/push/PushNotificationScheduler.scala`:

```scala
package push

import com.patson.data.{NotificationSource, PushSubscriptionSource}
import javax.inject.Inject
import org.apache.pekko.actor.Cancellable
import play.api.Configuration
import play.api.inject.ApplicationLifecycle

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class PushNotificationScheduler @Inject()(configuration: Configuration, lifecycle: ApplicationLifecycle)(implicit ec: ExecutionContext) {
  private val client = new WebPushClient()
  private var scheduled: Option[Cancellable] = None
  private val sent = new AtomicInteger(0)
  private val failed = new AtomicInteger(0)
  private val pruned = new AtomicInteger(0)
  @volatile private var lastTick: Option[Long] = None

  def sentCount: Int = sent.get()
  def failedCount: Int = failed.get()
  def prunedCount: Int = pruned.get()
  def lastTickAt: Option[Long] = lastTick

  start()
  lifecycle.addStopHook { () =>
    scheduled.foreach(_.cancel())
    scala.concurrent.Future.successful(())
  }

  private def start(): Unit = {
    val config = PushConfig.from(configuration)
    if (!PushConfig.configuredForDelivery(config)) {
      println("[push] sender disabled")
      return
    }
    scheduled = Some(controllers.actorSystem.scheduler.scheduleAtFixedRate(
      30.seconds,
      Math.max(15, config.intervalSeconds).seconds
    )(new Runnable {
      override def run(): Unit = tick()
    }))
    println(s"[push] sender enabled for categories ${config.categories.mkString(",")}")
  }

  private def tick(): Unit = {
    val config = PushConfig.from(configuration)
    if (!PushConfig.configuredForDelivery(config)) return
    lastTick = Some(System.currentTimeMillis())
    PushSubscriptionSource.loadAll().foreach { subscription =>
      val notifications = NotificationSource.loadNotificationsAfterId(
        subscription.airlineId,
        subscription.lastPushedNotificationId,
        config.categories,
        config.maxPerSubscription
      )
      notifications.foreach { notification =>
        try {
          val result = client.send(subscription, config, PushPayload.json(notification))
          if (result.permanentFailure) {
            PushSubscriptionSource.delete(subscription.id)
            pruned.incrementAndGet()
          } else if (result.status >= 200 && result.status < 300) {
            PushSubscriptionSource.markPushed(subscription.id, notification.id)
            sent.incrementAndGet()
          } else {
            println(s"[push] send returned ${result.status} for subscription ${subscription.id}: ${result.body.take(240)}")
            PushSubscriptionSource.markFailure(subscription.id)
            failed.incrementAndGet()
          }
        } catch {
          case e: Exception =>
            println(s"[push] send failed for subscription ${subscription.id}: ${e.getMessage}")
            PushSubscriptionSource.markFailure(subscription.id)
            failed.incrementAndGet()
        }
      }
    }
  }
}
```

- [ ] **Step 2: Compile both Task 4 and Task 5 together**

Run:
```
cd C:\Users\logan\Desktop\Airline\airline\airline-web && sbt compile
```
Expected: `[success]`.

- [ ] **Step 3: Commit**

```
cd C:\Users\logan\Desktop\Airline\airline && git add airline-web/app/controllers/PushApplication.scala airline-web/app/push/PushNotificationScheduler.scala airline-web/conf/routes && git commit -m "feat(push): add admin-gated test-send and push-summary endpoints"
```

### Task 6: Test-send button in the drawer

**Files:**
- Modify: `airline-web/public/javascripts/push.js`
- Modify: `airline-web/public/stylesheets/prompt.css`

No automated test (same rationale as Task 1). Verified live in Task 8.

- [ ] **Step 1: Add the button-creation function and CSS**

In `airline-web/public/javascripts/push.js`, add this new function right after `appendPushToggle` (which ends at line 93):

```javascript
    function appendTestSendButton() {
        var row = document.getElementById('pushNotificationSetting')
        if (!row || document.getElementById('pushTestSendButton')) return
        var button = document.createElement('button')
        button.id = 'pushTestSendButton'
        button.type = 'button'
        button.className = 'notification-push-test'
        button.textContent = 'Send test notification'
        button.addEventListener('click', function() {
            button.disabled = true
            button.textContent = 'Sending...'
            fetch('/airlines/' + activeAirline.id + '/push-test', {
                method: 'POST',
                credentials: 'same-origin'
            }).then(function(response) {
                button.textContent = response.ok ? 'Sent - should arrive within a minute' : 'Send failed'
            }).catch(function() {
                button.textContent = 'Send failed'
            }).finally(function() {
                setTimeout(function() {
                    button.disabled = false
                    button.textContent = 'Send test notification'
                }, 5000)
            })
        })
        row.insertAdjacentElement('afterend', button)
    }
```

In `airline-web/public/stylesheets/prompt.css`, add this after the `.notification-push-detail` block (currently ending at line 124):

```css
.notification-push-test {
    margin: 4px 10px 8px;
    padding: 6px 10px;
    font-size: 11px;
    border: 1px solid rgba(128, 128, 128, 0.35);
    border-radius: 4px;
    background: transparent;
    color: var(--text-color);
    cursor: pointer;
}

.notification-push-test:disabled {
    opacity: 0.5;
    cursor: default;
}
```

- [ ] **Step 2: Call it from `renderPushStatus` when the logged-in airline is admin**

In `airline-web/public/javascripts/push.js`, in `renderPushStatus`, in the main (non-early-return) branch — right after the `renderPushDiagnostic(detail)` call that precedes `updatePushDebugState()` — add:

```javascript
        if (pushState.status && pushState.status.isAdmin) {
            appendTestSendButton()
        }
```

So the tail of `renderPushStatus` reads:

```javascript
        renderPushDiagnostic(detail)
        if (pushState.status && pushState.status.isAdmin) {
            appendTestSendButton()
        }
        updatePushDebugState()
    }
```

- [ ] **Step 3: Sanity-check the file parses**

Run:
```
cd C:\Users\logan\Desktop\Airline\airline\airline-web && node --check public/javascripts/push.js
```
Expected: no output.

- [ ] **Step 4: Commit**

```
cd C:\Users\logan\Desktop\Airline\airline && git add airline-web/public/javascripts/push.js airline-web/public/stylesheets/prompt.css && git commit -m "feat(web): add admin-gated test-send button to push settings"
```

### Task 7: Wire the admin airline ID into the live deploy

**Files:**
- Modify: `.github/workflows/optiplex-deploy.yml`

- [ ] **Step 1: Confirm the target airline ID**

The docs (`docs/web-push-notifications-plan.md`, `docs/current-development-state.md`) consistently reference `airline_id=34` ("Lash Air") as the validated single-player airline on the OptiPlex. Confirm it's still correct:
```
ssh root@192.168.1.52 "docker exec airline-db mysql -u\"\$(docker exec airline-db printenv MYSQL_USER)\" -p\"\$(docker exec airline-db printenv MYSQL_PASSWORD)\" \"\$(docker exec airline-db printenv MYSQL_DATABASE)\" -e 'SELECT id, name FROM airline WHERE id = 34;'"
```
(this mirrors the exact quoting pattern already proven to work on this host, from `docs/current-development-state.md`'s own ops crib commands — don't substitute a different quoting style). Expected: one row, name `Lash Air` (or whatever the user's current airline is named). If the ID differs, use that ID in Step 2 instead of 34.

- [ ] **Step 2: Add the flag**

In `.github/workflows/optiplex-deploy.yml`, in the `WEB_SOLO_OPTS` block (the line ending `-Dsolo.push.vapidSubject=${{ secrets.SOLO_PUSH_VAPID_SUBJECT }}`), add one line directly after it:

```
            -Dsolo.push.vapidSubject=${{ secrets.SOLO_PUSH_VAPID_SUBJECT }}
            -Dsolo.push.adminAirlineId=34
```

(Replace `34` with the confirmed ID from Step 1 if different. This is not a secret — just an airline ID — so it's a plain `-D` flag like `-Dsolo.push.enabled=true` above it, not a GitHub secret.)

- [ ] **Step 3: Commit**

```
cd C:\Users\logan\Desktop\Airline\airline && git add .github/workflows/optiplex-deploy.yml && git commit -m "chore(deploy): set solo.push.adminAirlineId for the live OptiPlex deploy"
```

### Task 8: Ship Phase B and verify live

**Files:** none (deploy + verification only)

- [ ] **Step 1: Push to master**

```
cd C:\Users\logan\Desktop\Airline\airline && git push origin master
```

- [ ] **Step 2: Watch CI and the OptiPlex deploy**

Same as Phase A Task 2, Steps 2–3:
```
gh run list --repo Lash12/airline --branch master --limit 2
gh run watch <ci-run-id> --repo Lash12/airline --exit-status
gh run watch <deploy-run-id> --repo Lash12/airline --exit-status
```
Expected: both exit 0.

- [ ] **Step 3: Verify `push-summary` responds (will 401/403 without a session — that's expected from curl)**

```
ssh root@192.168.1.52 "curl -s -o /dev/null -w '%{http_code}\n' http://localhost:9000/airlines/34/push-summary"
```
Expected: `401` (Unauthorized — `AuthenticatedAirline` requires a real logged-in session cookie, which curl doesn't have). This confirms the route exists and the controller is wired correctly (a 404 here would mean the route didn't register; a 500 would mean a server error) — it does not fully exercise the admin gate, which needs a real browser session.

- [ ] **Step 4: Manually trigger and observe a full test-send round trip**

Ask the user (or do this yourself if you have an active session cookie) to:
1. Log into `https://airline.ashhome.org` as the airline configured in Task 7.
2. Open the notification drawer and confirm a "Send test notification" button now appears below the phone-notifications toggle.
3. Tap it.
4. Within ~60 seconds, check for a system push notification with body "Test push - connectivity check".

If you have shell access but not a logged-in browser session, you can still verify the backend half: insert the test notification directly and watch the scheduler log pick it up:
```
ssh root@192.168.1.52 "docker exec airline-app sh -c 'tail -f /home/airline/web.log | grep --line-buffered \"\\[push\\]\"'"
```
(leave this running, then have the user tap the button from their phone; expect a `[push] sender enabled...`-style log line and no `send failed`/`send returned` errors within the next tick)

- [ ] **Step 5: Verify the summary endpoint's actual content via SSH (bypassing browser auth)**

Since `push-summary` is gated by session auth (no API token exists), confirm its data shape is correct by checking the same underlying data it serves, directly:
```
ssh root@192.168.1.52 "docker exec airline-db mysql -u\"\$(docker exec airline-db printenv MYSQL_USER)\" -p\"\$(docker exec airline-db printenv MYSQL_PASSWORD)\" \"\$(docker exec airline-db printenv MYSQL_DATABASE)\" -e 'SELECT id, airline, last_pushed_notification_id, failure_count FROM push_subscription ORDER BY id;'"
```
Cross-check these values against what `GET /airlines/34/push-summary` returns when the user (or you, with their session) hits it from a logged-in browser tab — they should match.

---

## Phase C — Negotiation-ready live verification

### Task 9: Confirm a real cycle-driven push, not just a test insert

**Files:** none (verification only); fix forward in `airline-data/src/main/scala/com/patson/NegotiationReadyNotifier.scala` or `airline-web/app/push/PushPayload.scala` only if something is actually found broken.

- [ ] **Step 1: Find an in-flight negotiation cooldown for the admin airline**

```
ssh root@192.168.1.52 "docker exec airline-db mysql -u\"\$(docker exec airline-db printenv MYSQL_USER)\" -p\"\$(docker exec airline-db printenv MYSQL_PASSWORD)\" \"\$(docker exec airline-db printenv MYSQL_DATABASE)\" -e \"SELECT airline, target_id, expiry_cycle, category FROM notification WHERE category = 'NEGOTIATION_LOSS' AND airline = 34 ORDER BY expiry_cycle DESC LIMIT 5;\""
```
This shows upcoming `NEGOTIATION_LOSS` rows; their `expiry_cycle` is when `NegotiationReadyNotifier` should naturally fire a `NEGOTIATION_READY` notification for the same `target_id` (per `AirlineSimulation.scala:40`, called every cycle).

- [ ] **Step 2: Check the current cycle and wait for it to reach an expiry**

```
ssh root@192.168.1.52 "curl -s http://localhost:9000/current-cycle"
```
If no cooldown is close to expiring, this step may need to wait for a future cycle (cycles advance roughly every 29 minutes per `MainSimulation.CYCLE_DURATION`, faster if `pauseWhenIdle` is off and the box is actively simulating) — or skip ahead by checking again later in the same session rather than blocking on it; this doesn't need to happen synchronously with the rest of this plan. The in-game fast-forward control (`PUT /sim-control/fast-forward/:cycles`, max 52 cycles) can shorten this wait, but it's gated by a logged-in session — only the user can trigger it from the UI, not via SSH/curl.

- [ ] **Step 3: Confirm the notification and push fired correctly**

Once a cycle has passed the expiry:
```
ssh root@192.168.1.52 "docker exec airline-db mysql -u\"\$(docker exec airline-db printenv MYSQL_USER)\" -p\"\$(docker exec airline-db printenv MYSQL_PASSWORD)\" \"\$(docker exec airline-db printenv MYSQL_DATABASE)\" -e \"SELECT id, message, target_id FROM notification WHERE category = 'NEGOTIATION_READY' AND airline = 34 ORDER BY id DESC LIMIT 3;\""
```
Expected: a row with a message like "Negotiation ready: JFK -> LHR can be attempted again." and `target_id` matching `"{fromAirportId}-{toAirportId}"`. Confirm the user received a real phone push for it (not just a DB row) and that tapping it opened the route-planning view for that airport pair (via the `/?planLinkFrom=X&planLinkTo=Y` deep link).

- [ ] **Step 4: Record the result**

If everything checks out, this phase needs no code change — just record it. If something is wrong (e.g., message text reads oddly for a real `fromAirport.displayText`/`toAirport.displayText` combination, or the deep link doesn't actually open the planner), fix the specific issue found in `NegotiationReadyNotifier.scala` or `PushPayload.scala`, write a regression test in `NegotiationReadyNotifierSpec.scala` first (TDD), then redeploy following the same push/watch/verify pattern as Task 8.

---

## Final task: Update the docs

**Files:**
- Modify: `docs/current-development-state.md`
- Modify: `docs/web-push-notifications-plan.md`

- [ ] **Step 1: Update `docs/current-development-state.md`**

Replace the "Suggested Next Feature Phase" section (the final section of the file) with a short note that this phase is complete, listing what shipped (settings UX cleanup, admin-gated test-send + push-summary, negotiation-ready verified live) and what the new natural next phase is (defer to whoever picks this up next — don't invent one here).

- [ ] **Step 2: Update `docs/web-push-notifications-plan.md`**

Add a short "Phase 2 (admin tooling) — shipped" note under "Verified Production Result" summarizing the test-send button and `push-summary` endpoint, so a future reader doesn't have to dig through git log to find them.

- [ ] **Step 3: Commit**

```
cd C:\Users\logan\Desktop\Airline\airline && git add docs/current-development-state.md docs/web-push-notifications-plan.md && git commit -m "docs: record web push completeness phase as shipped"
```

- [ ] **Step 4: Push**

```
cd C:\Users\logan\Desktop\Airline\airline && git push origin master
```
This is docs-only but will still trigger `CI` + `OptiPlex Deploy & Verify` (no functional change, so no need to re-verify behavior — just confirm both report success per Phase A Task 2's pattern, since a failure here would be a process/infra problem worth catching).
