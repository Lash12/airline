package com.patson

import com.patson.model._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class AirportTrafficStatsSpec extends AnyWordSpecLike with Matchers {
  private def ap(id: Int) = Airport.fromId(id)
  private val al = Airline.fromId(1)
  private def stat(from: Int, to: Int, isDest: Boolean, pax: Int, premium: Int) =
    LinkStatistics(LinkStatisticsKey(ap(from), ap(to), isDeparture = false, isDestination = isDest, al), pax, premium, 0)

  "arrivalsByOrigin" must {
    "aggregate per origin with terminating/connecting split and premium" in {
      val rows = List(
        stat(10, 99, isDest = true, pax = 80, premium = 8),
        stat(10, 99, isDest = false, pax = 20, premium = 2),
        stat(20, 99, isDest = true, pax = 50, premium = 0))
      val result = AirportTrafficStats.arrivalsByOrigin(rows).sortBy(-_.totalPax)
      result.map(_.airportId) shouldBe List(10, 20)
      val r10 = result.head
      r10.totalPax shouldBe 100
      r10.terminatingPax shouldBe 80
      r10.connectingPax shouldBe 20
      r10.premiumPax shouldBe 10
      r10.transferShare shouldBe 0.2 +- 0.0001
    }
  }

  "summary" must {
    "report overall transfer share across all arrival rows" in {
      val rows = List(
        stat(10, 99, isDest = true, pax = 75, premium = 0),
        stat(20, 99, isDest = false, pax = 25, premium = 0))
      val s = AirportTrafficStats.summary(rows)
      s.totalPax shouldBe 100
      s.connectingPax shouldBe 25
      s.transferShare shouldBe 0.25 +- 0.0001
    }
    "be safe on empty input" in {
      val s = AirportTrafficStats.summary(Nil)
      s.totalPax shouldBe 0
      s.transferShare shouldBe 0.0
    }
  }
}
