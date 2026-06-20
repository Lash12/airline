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
