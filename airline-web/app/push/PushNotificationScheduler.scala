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
