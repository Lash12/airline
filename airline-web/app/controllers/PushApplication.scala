package controllers

import com.patson.data.{CycleSource, NotificationSource, PushSubscriptionSource}
import com.patson.model.PushSubscription
import controllers.AuthenticationObject.AuthenticatedAirline
import javax.inject.Inject
import play.api.Configuration
import play.api.libs.json._
import play.api.mvc._

class PushApplication @Inject()(cc: ControllerComponents, configuration: Configuration) extends AbstractController(cc) {
  private def pushEnabled: Boolean =
    configuration.underlying.hasPath("solo.push.enabled") && configuration.underlying.getBoolean("solo.push.enabled")

  def status(airlineId: Int) = AuthenticatedAirline(airlineId) { _ =>
    val subscriptions = PushSubscriptionSource.loadByAirline(airlineId)
    Ok(Json.obj(
      "enabled" -> pushEnabled,
      "subscribed" -> subscriptions.nonEmpty,
      "subscriptionCount" -> subscriptions.size
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
}
