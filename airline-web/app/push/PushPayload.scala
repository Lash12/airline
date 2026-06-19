package push

import com.patson.model.Notification
import play.api.libs.json.Json

object PushPayload {
  def urlFor(notification: Notification): String =
    notification.targetId match {
      case Some(targetId) if targetId.matches("\\d+-\\d+") =>
        val Array(fromId, toId) = targetId.split("-")
        s"/?planLinkFrom=$fromId&planLinkTo=$toId"
      case Some(targetId) if targetId.matches("\\d+") => s"/flights/$targetId"
      case Some(targetId) if targetId.startsWith("/") => targetId
      case _ => "/"
    }

  def titleFor(notification: Notification): String =
    notification.category match {
      case com.patson.model.NotificationCategory.NEGOTIATION_READY => "Negotiation ready"
      case com.patson.model.NotificationCategory.CASH_FLOW_WARNING => "Cash flow warning"
      case com.patson.model.NotificationCategory.MILESTONE_ACHIEVED => "Milestone achieved"
      case other => other.toString.replace('_', ' ').toLowerCase.capitalize
    }

  def json(notification: Notification): String =
    Json.stringify(Json.obj(
      "title" -> titleFor(notification),
      "body" -> notification.message,
      "data" -> Json.obj(
        "url" -> urlFor(notification),
        "notificationId" -> notification.id,
        "category" -> notification.category.toString
      )
    ))
}
