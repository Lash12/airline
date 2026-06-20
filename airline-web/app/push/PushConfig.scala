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
