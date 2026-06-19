package com.patson

import com.patson.model._
import com.patson.model.negotiation.LinkNegotiationDiscount
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class NegotiationReadyNotifierSpec extends AnyWordSpecLike with Matchers {
  private val from = Airport.fromId(1).copy(iata = "RDU", name = "Raleigh-Durham")
  private val to = Airport.fromId(2).copy(iata = "LHR", name = "Heathrow")
  private val player = Airline.fromId(10).copy(name = "Player Air", airlineType = LegacyAirline)
  private val npc = Airline.fromId(11).copy(name = "NPC Air", airlineType = NonPlayerAirline)

  "NegotiationReadyNotifier" should {
    "emit one notification per player route when no ready notification exists" in {
      val discount = LinkNegotiationDiscount(player, from, to, BigDecimal("0.25"), expiry = 432)

      val notifications = NegotiationReadyNotifier.notificationsToEmit(List(discount), (_, _) => false)

      notifications should have size 1
      notifications.head.category shouldBe NotificationCategory.NEGOTIATION_READY
      notifications.head.airlineId shouldBe player.id
      notifications.head.targetId shouldBe Some("1-2")
      notifications.head.cycle shouldBe 432
    }

    "dedupe route reminders and skip NPC airlines" in {
      val older = LinkNegotiationDiscount(player, from, to, BigDecimal("0.10"), expiry = 400)
      val newer = LinkNegotiationDiscount(player, from, to, BigDecimal("0.20"), expiry = 432)
      val npcDiscount = LinkNegotiationDiscount(npc, from, to, BigDecimal("0.20"), expiry = 432)

      val notifications = NegotiationReadyNotifier.notificationsToEmit(List(older, newer, npcDiscount), (_, _) => false)

      notifications should have size 1
      notifications.head.cycle shouldBe 432
    }

    "skip reminders that already exist" in {
      val discount = LinkNegotiationDiscount(player, from, to, BigDecimal("0.25"), expiry = 432)

      NegotiationReadyNotifier.notificationsToEmit(List(discount), (_, _) => true) shouldBe empty
    }
  }
}
