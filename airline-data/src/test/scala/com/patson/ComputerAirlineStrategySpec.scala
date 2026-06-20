package com.patson

import com.patson.model.{Airport, LinkClassValues}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ComputerAirlineStrategySpec extends AnyWordSpecLike with Matchers {
  private val largeUs = Airport.fromId(1).copy(countryCode = "US", size = 8, latitude = 40, longitude = -73)
  private val smallUs = Airport.fromId(2).copy(countryCode = "US", size = 4, latitude = 41, longitude = -74)
  private val largeGb = Airport.fromId(3).copy(countryCode = "GB", size = 8, latitude = 51, longitude = 0)

  "ComputerAirlineStrategy" should {
    "prefer regional routes for regional strategy" in {
      val demand = LinkClassValues(1000, 80, 10)
      val aligned = ComputerAirlineStrategy.multiplier(ComputerAirlineStrategy.Strategy.RegionalFocus, largeUs, smallUs, demand, 0.25)
      val offStrategy = ComputerAirlineStrategy.multiplier(ComputerAirlineStrategy.Strategy.RegionalFocus, largeUs, largeGb, demand, 0.25)

      aligned should be > offStrategy
      aligned shouldBe 1.25
      offStrategy shouldBe 0.875
    }

    "prefer premium-heavy demand for premium strategy" in {
      val premiumDemand = LinkClassValues(600, 180, 20)
      val economyDemand = LinkClassValues(1000, 80, 10)

      ComputerAirlineStrategy.multiplier(ComputerAirlineStrategy.Strategy.PremiumFocus, largeUs, largeGb, premiumDemand, 0.2) shouldBe 1.2
      ComputerAirlineStrategy.multiplier(ComputerAirlineStrategy.Strategy.PremiumFocus, largeUs, largeGb, economyDemand, 0.2) shouldBe 0.9
    }
  }
}
