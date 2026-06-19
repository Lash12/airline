package com.patson

import com.patson.data.{NegotiationSource, NotificationSource, SoloConfig}
import com.patson.model.{NonPlayerAirline, Notification, NotificationCategory}
import com.patson.model.negotiation.LinkNegotiationDiscount

object NegotiationReadyNotifier {
  def targetId(discount : LinkNegotiationDiscount) : String =
    s"${discount.fromAirport.id}-${discount.toAirport.id}"

  def message(discount : LinkNegotiationDiscount) : String =
    s"Negotiation ready: ${discount.fromAirport.displayText} -> ${discount.toAirport.displayText} can be attempted again."

  def notificationsToEmit(discounts : List[LinkNegotiationDiscount],
                          alreadyExists : (Int, String) => Boolean) : List[Notification] = {
    discounts
      .filter(_.airline.airlineType != NonPlayerAirline)
      .groupBy(discount => (discount.airline.id, targetId(discount)))
      .values
      .flatMap(_.sortBy(-_.expiry).headOption)
      .filterNot(discount => alreadyExists(discount.airline.id, targetId(discount)))
      .map { discount =>
        Notification(discount.airline.id, NotificationCategory.NEGOTIATION_READY, message(discount), discount.expiry, targetId = Some(targetId(discount)))
      }
      .toList
  }

  def emit(cycle : Int) : Int = {
    if (!SoloConfig.negotiationReadyEnabled) return 0
    val notifications = notificationsToEmit(
      NegotiationSource.loadExpiredLinkDiscounts(cycle),
      (airlineId, id) => NotificationSource.existsByTargetId(airlineId, id, NotificationCategory.NEGOTIATION_READY)
    )
    NotificationSource.insertNotificationsBulk(notifications)
    if (notifications.nonEmpty) println(s"[negotiation-ready] emitted ${notifications.size} notification(s)")
    notifications.size
  }
}
