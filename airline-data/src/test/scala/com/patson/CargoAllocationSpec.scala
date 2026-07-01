package com.patson

import com.patson.model._
import com.patson.data.SoloConfig
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class CargoAllocationSpec extends AnyWordSpecLike with Matchers {
  private val from = Airport("AAA", "KAAA", "AAA", 0, 0, "ZZ", "AAA", "North America", 5, 40000, 5_000_000, id = 1)
  private val to = Airport("BBB", "KBBB", "BBB", 0, 20, "ZZ", "BBB", "North America", 5, 35000, 4_000_000, id = 2)
  private val airline = Airline("Cargo Test", id = 1)

  private case class TestCargoTransport(from: Airport,
                                        to: Airport,
                                        airline: Airline,
                                        distance: Int,
                                        var capacity: LinkClassValues,
                                        duration: Int,
                                        var frequency: Int,
                                        price: LinkClassValues,
                                        var id: Int) extends Transport {
    override val transportType = TransportType.CARGO_FLIGHT
    override val cost = price
    override val frequencyByClass = (_: LinkClass) => 0
    override var minorDelayCount = 0
    override var majorDelayCount = 0
    override var cancellationCount = 0
    override def computedQuality(): Int = 0
  }

  private def details(id: Int, cargoCapacity: Int, distance: Int = 1000): LinkConsumptionDetails = {
    val link = Link(from, to, airline, LinkClassValues.empty, distance, LinkClassValues.empty, 0, 0, 1, id = id)
    LinkConsumptionDetails(link, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, cargoCapacity = cargoCapacity)
  }

  private def cargoDetails(id: Int, cargoCapacity: Int, distance: Int = 1000): LinkConsumptionDetails = {
    val link = TestCargoTransport(from, to, airline, distance, LinkClassValues.empty, 0, 1, LinkClassValues.empty, id)
    LinkConsumptionDetails(link, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, cargoCapacity = cargoCapacity)
  }

  "allocateGroup" must {
    "return zero carried cargo when demand is zero".in {
      val result = CargoAllocation.allocateGroup(Seq(details(1, 10), details(2, 20)), 0)

      result.map(_.carried) shouldBe Seq(0, 0)
      result.map(_.revenue) shouldBe Seq(0, 0)
    }

    "return zero carried cargo when capacity is zero".in {
      val result = CargoAllocation.allocateGroup(Seq(details(1, 0), details(2, 0)), 100)

      result.map(_.carried) shouldBe Seq(0, 0)
      result.map(_.revenue) shouldBe Seq(0, 0)
    }

    "share bounded demand by cargo capacity".in {
      val resultById = CargoAllocation.allocateGroup(Seq(details(1, 25), details(2, 75)), 40).map(r => r.linkId -> r).toMap

      resultById(1).carried shouldBe 10
      resultById(2).carried shouldBe 30
      resultById.values.map(_.carried).sum shouldBe 40
    }

    "give larger freighter-like capacity more cargo than belly capacity".in {
      val resultById = CargoAllocation.allocateGroup(Seq(details(1, 8), details(2, 72)), 40).map(r => r.linkId -> r).toMap

      resultById(2).carried should be > resultById(1).carried
      resultById.values.map(_.carried).sum shouldBe 40
    }

    "break rounding remainder by capacity desc then link id asc".in {
      val result = CargoAllocation.allocateGroup(Seq(details(2, 10), details(1, 10)), 1)

      result.find(_.linkId == 1).get.carried shouldBe 1
      result.find(_.linkId == 2).get.carried shouldBe 0
    }

    "leave belly cargo revenue on passenger links at the base cargo rate".in {
      val result = CargoAllocation.allocateGroup(Seq(details(1, 100, distance = 1000)), 100).head

      result.carried shouldBe 100
      result.revenue shouldBe Math.round(100 * 1000 * SoloConfig.cargoRevenuePerUnitKm).toInt
    }

    "apply the freighter-only revenue multiplier to cargo links".in {
      val result = CargoAllocation.allocateGroup(Seq(cargoDetails(1, 100, distance = 1000)), 100).head

      result.carried shouldBe 100
      result.revenue shouldBe Math.round(100 * 1000 * SoloConfig.cargoRevenuePerUnitKm * SoloConfig.cargoFreighterRevenueMultiplier).toInt
    }

    "apply the multiplier only to the freighter share in mixed cargo allocation".in {
      val resultById = CargoAllocation.allocateGroup(Seq(details(1, 50, distance = 1000), cargoDetails(2, 50, distance = 1000)), 100).map(r => r.linkId -> r).toMap

      resultById(1).revenue shouldBe Math.round(50 * 1000 * SoloConfig.cargoRevenuePerUnitKm).toInt
      resultById(2).revenue shouldBe Math.round(50 * 1000 * SoloConfig.cargoRevenuePerUnitKm * SoloConfig.cargoFreighterRevenueMultiplier).toInt
    }
  }
}
