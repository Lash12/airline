package com.patson

import com.patson.model._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

/**
 * Airport assets: the pure, DB-free decision helpers — boost scaling, the typed income/upkeep model
 * (infrastructure earns nothing), construction completion, sell value, and build validation.
 */
class AirportAssetSpec extends AnyWordSpecLike with Matchers {
  import AirportAssetType._

  // size 5 => airportModifier 1.0, so unitCost == baseCost (already a round thousand).
  private def airport(size : Int = 5) = Airport("TST", "TSTX", "Test", 0, 0, "US", "Test City", "NA", size, 50000, 1_000_000)
  private val airline = Airline.fromId(1)

  "boostsAt".must {
    "scale the boost linearly with level".in {
      SHOPPING_MALL.boostsAt(2) shouldBe List(AirportBoost(AirportBoostType.INCOME, 6000))
      RESORT.boostsAt(3) shouldBe List(AirportBoost(AirportBoostType.VACATION_HUB, 12))
    }
    "contribute nothing at level 0".in {
      SHOPPING_MALL.boostsAt(0) shouldBe Nil
    }
    "map each asset to its declared boost type".in {
      METRO.boostType shouldBe AirportBoostType.POPULATION
      CONVENTION_CENTER.boostType shouldBe AirportBoostType.FINANCIAL_HUB
      LANDMARK.boostType shouldBe AirportBoostType.INTERNATIONAL_HUB
    }
  }

  "the typed income model".must {
    "earn nothing for infrastructure assets".in {
      METRO.generatesIncome shouldBe false
      METRO.weeklyIncome(airport(), 3) shouldBe 0L
    }
    "earn a modest income for revenue assets".in {
      SHOPPING_MALL.generatesIncome shouldBe true
      SHOPPING_MALL.weeklyIncome(airport(), 1) shouldBe Math.round(150_000_000L * INCOME_RATE)
    }
    "earn a smaller income for attraction assets than the equivalent revenue asset".in {
      // attraction income is halved (incomeFactor 0.5); compare at equal cost via the rate factors.
      RESORT.weeklyIncome(airport(), 1) shouldBe Math.round(90_000_000L * INCOME_RATE * 0.5)
    }
  }

  "cost, upkeep and sell value".must {
    "scale unit cost by the airport size modifier".in {
      SHOPPING_MALL.unitCost(airport(size = 5)) shouldBe 150_000_000L
      SHOPPING_MALL.unitCost(airport(size = 10)) shouldBe 300_000_000L
    }
    "charge upkeep proportional to level".in {
      SHOPPING_MALL.upkeep(airport(), 1) shouldBe Math.round(150_000_000L * UPKEEP_RATE)
      SHOPPING_MALL.upkeep(airport(), 2) shouldBe Math.round(150_000_000L * UPKEEP_RATE * 2)
    }
    "return half the invested cash as sell value".in {
      SHOPPING_MALL.totalInvested(airport(), 2) shouldBe 300_000_000L
      SHOPPING_MALL.sellValue(airport(), 2) shouldBe 150_000_000L
    }
  }

  "an AirportAsset instance".must {
    "expose boosts/income/upkeep only while operational".in {
      val building = AirportAsset(airline, airport(), SHOPPING_MALL, 1, AirportAssetStatus.UNDER_CONSTRUCTION, completionCycle = 50)
      building.currentBoosts shouldBe Nil
      building.weeklyIncome shouldBe 0L
      building.weeklyUpkeep shouldBe 0L

      val active = building.activated
      active.status shouldBe AirportAssetStatus.ACTIVE
      active.currentBoosts shouldBe List(AirportBoost(AirportBoostType.INCOME, 3000))
      active.weeklyIncome should be > 0L
      active.weeklyUpkeep should be > 0L
    }
    "complete only once the cycle reaches the completion cycle".in {
      val a = AirportAsset(airline, airport(), METRO, 1, AirportAssetStatus.UNDER_CONSTRUCTION, completionCycle = 100)
      a.isComplete(99) shouldBe false
      a.isComplete(100) shouldBe true
    }
  }

  "netWeekly / paybackCycles".must {
    "give a positive net and finite payback for revenue assets".in {
      SHOPPING_MALL.netWeekly(airport(), 1) should be > 0L
      SHOPPING_MALL.paybackCycles(airport(), 1) shouldBe defined
    }
    "give no payback for infrastructure (no income, net negative)".in {
      METRO.netWeekly(airport(), 1) should be < 0L
      METRO.paybackCycles(airport(), 1) shouldBe None
    }
    "map each asset to an image file that exists in the assets directory".in {
      AirportAssetType.values.foreach { t =>
        new java.io.File(s"../airline-web/public/images/airport-assets/${t.image}").exists() shouldBe true
      }
    }
  }

  "validateBuild".must {
    val a = airport(size = 6)
    "require a base at the airport".in {
      AirportAsset.validateBuild(hasBaseAtAirport = false, a.size, SHOPPING_MALL, 0, 1, balance = 10_000_000_000L, cost = 1L) shouldBe defined
    }
    "require the airport to meet the size requirement".in {
      AirportAsset.validateBuild(hasBaseAtAirport = true, airportSize = 5, LANDMARK, 0, 1, 10_000_000_000L, 1L) shouldBe defined
    }
    "reject skipping more than one level".in {
      AirportAsset.validateBuild(hasBaseAtAirport = true, a.size, SHOPPING_MALL, 1, 3, 10_000_000_000L, 1L) shouldBe defined
    }
    "reject exceeding the max level".in {
      // default max level is 3; upgrading from 3 -> 4 is one level but over the cap.
      AirportAsset.validateBuild(hasBaseAtAirport = true, a.size, SHOPPING_MALL, 3, 4, 10_000_000_000L, 1L) shouldBe defined
    }
    "reject when the airline cannot afford it".in {
      AirportAsset.validateBuild(hasBaseAtAirport = true, a.size, SHOPPING_MALL, 0, 1, balance = 1L, cost = 1_000_000L) shouldBe defined
    }
    "allow a valid build".in {
      AirportAsset.validateBuild(hasBaseAtAirport = true, a.size, SHOPPING_MALL, 0, 1, balance = 1_000_000_000L, cost = 150_000_000L) shouldBe None
    }
  }
}
