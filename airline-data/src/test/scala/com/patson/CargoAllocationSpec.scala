package com.patson

import com.patson.model._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class CargoAllocationSpec extends AnyWordSpecLike with Matchers {
  private val from = Airport("AAA", "KAAA", "AAA", 0, 0, "ZZ", "AAA", "North America", 5, 40000, 5_000_000, id = 1)
  private val to = Airport("BBB", "KBBB", "BBB", 0, 20, "ZZ", "BBB", "North America", 5, 35000, 4_000_000, id = 2)
  private val airline = Airline("Cargo Test", id = 1)

  private def details(id: Int, cargoCapacity: Int, distance: Int = 1000): LinkConsumptionDetails = {
    val link = Link(from, to, airline, LinkClassValues.empty, distance, LinkClassValues.empty, 0, 0, 1, id = id)
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
  }
}
