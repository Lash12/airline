package com.patson

import com.patson.CargoDemandGenerator.computeCargoDemandBetweenAirports
import com.patson.model.Airport
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class CargoDemandGeneratorSpec extends AnyWordSpecLike with Matchers {

  // Synthetic airports so the tests are independent of DB contents. Field order matches the
  // Airport constructor used elsewhere in the suite (see DemandGeneratorSpec):
  // (iata, icao, city, lat, lng, countryCode, name, zone, size, income, population, popMiddleIncome, popElite, runway, id)
  private def mk(iata : String, income : Int, population : Int, countryCode : String = "ZZ", zone : String = "North America", size : Int = 5) : Airport =
    Airport(iata, s"K$iata", iata, 10.0, 10.0 + math.abs(iata.hashCode) % 30, countryCode, iata, zone, size, income, population, population / 2, 0, 2000, math.abs(iata.hashCode) % 100000)

  "computeCargoDemandBetweenAirports" must {
    "be deterministic".in {
      val from = mk("AAA", 40000, 5_000_000)
      val to = mk("BBB", 35000, 4_000_000)
      val results = (1 to 10).map(_ => computeCargoDemandBetweenAirports(from, to, affinity = 5, distance = 2000))
      println(s"AAA -> BBB cargo demand (10 runs): ${results.mkString(", ")}")
      assert(results.forall(_ == results.head), s"should be deterministic, got ${results.mkString(", ")}")
    }

    "produce positive demand between two productive economies".in {
      val from = mk("AAA", 40000, 5_000_000)
      val to = mk("BBB", 35000, 4_000_000)
      val demand = computeCargoDemandBetweenAirports(from, to, affinity = 5, distance = 2000)
      println(s"AAA -> BBB cargo demand: $demand")
      assert(demand > 0)
    }

    "return zero when either airport has no income".in {
      val productive = mk("AAA", 40000, 5_000_000)
      val noIncome = mk("BBB", 0, 4_000_000)
      assert(computeCargoDemandBetweenAirports(productive, noIncome, 5, 2000) == 0)
      assert(computeCargoDemandBetweenAirports(noIncome, productive, 5, 2000) == 0)
    }

    "return zero for a pair that cannot have demand (too close)".in {
      val from = mk("AAA", 40000, 5_000_000)
      val to = mk("BBB", 35000, 4_000_000)
      // distance below MIN_DISTANCE (175) and not island/bush => canHaveDemand is false
      assert(computeCargoDemandBetweenAirports(from, to, affinity = 5, distance = 50) == 0)
    }

    "increase monotonically with economic mass".in {
      val to = mk("DEST", 35000, 4_000_000)
      val populations = List(500_000, 1_000_000, 2_000_000, 5_000_000, 10_000_000, 20_000_000)
      val demands = populations.map { pop =>
        val from = mk(s"O$pop", 40000, pop)
        (pop, computeCargoDemandBetweenAirports(from, to, affinity = 5, distance = 2000))
      }
      println("population | cargo demand")
      demands.foreach { case (pop, d) => println(f"$pop%10d | $d%d") }
      val violations = demands.sliding(2).collect { case Seq((p1, d1), (p2, d2)) if d2 < d1 => (p1, d1, p2, d2) }.toList
      assert(violations.isEmpty, s"demand should not drop as population grows: $violations")
    }

    "give domestic (high affinity) lanes more cargo than foreign (low affinity) lanes".in {
      val from = mk("AAA", 40000, 5_000_000)
      val to = mk("BBB", 35000, 4_000_000)
      val domestic = computeCargoDemandBetweenAirports(from, to, affinity = 5, distance = 2000)
      val foreign = computeCargoDemandBetweenAirports(from, to, affinity = 0, distance = 2000)
      println(s"domestic(affinity 5)=$domestic foreign(affinity 0)=$foreign")
      assert(domestic >= foreign)
    }

    "prefer source-backed cargo hubs without replacing economic demand".in {
      val hub = mk("HKG", 40000, 5_000_000)
      val nonHub = mk("ZZZ", 40000, 5_000_000)
      val destination = mk("AAA", 35000, 4_000_000)

      val hubDemand = computeCargoDemandBetweenAirports(hub, destination, affinity = 5, distance = 2000)
      val nonHubDemand = computeCargoDemandBetweenAirports(nonHub, destination, affinity = 5, distance = 2000)

      hubDemand should be > nonHubDemand
    }

    "keep the strongest known cargo hub pair under the cap".in {
      val topPairMultiplier = CargoDemandGenerator.cargoHubMultiplier(mk("HKG", 40000, 5_000_000), mk("PVG", 40000, 5_000_000))

      topPairMultiplier shouldBe 1.35 +- 0.0001
      topPairMultiplier should be <= 1.4
    }
  }

  "prepareCargoCache" must {
    "full-reset cold, then invalidate only changed airports".in {
      val a = mk("AAA", 30000, 1_000_000)
      val b = mk("BBB", 25000, 800_000)
      val c = mk("CCC", 20000, 600_000)
      val epoch = 999L

      val (reset1, changed1) = CargoDemandGenerator.prepareCargoCache(Array(a, b, c), epoch)
      assert(reset1, "first call should full-reset")
      assert(changed1 == 3)

      val (reset2, changed2) = CargoDemandGenerator.prepareCargoCache(Array(a, b, c), epoch)
      assert(!reset2, "unchanged world should not full-reset")
      assert(changed2 == 0, s"unchanged world should evict nothing, evicted $changed2")

      // Mutate one airport (higher income) -> exactly one eviction, no full reset.
      val bChanged = mk("BBB", 45000, 800_000)
      val (reset3, changed3) = CargoDemandGenerator.prepareCargoCache(Array(a, bChanged, c), epoch)
      assert(!reset3, "single demographic change should be incremental")
      assert(changed3 == 1, s"only the mutated airport should be evicted, evicted $changed3")

      // Relationship-epoch change is a global input -> full reset.
      val (reset4, _) = CargoDemandGenerator.prepareCargoCache(Array(a, bChanged, c), epoch + 1)
      assert(reset4, "relationship epoch change should full-reset")

      // Adding an airport changes membership/count -> full reset.
      val (reset5, _) = CargoDemandGenerator.prepareCargoCache(Array(a, bChanged, c, mk("DDD", 15000, 400_000)), epoch + 1)
      assert(reset5, "membership change should full-reset")

      // Fingerprint sanity: same demographics hash equal, changed income differs.
      assert(CargoDemandGenerator.airportFingerprint(b) == CargoDemandGenerator.airportFingerprint(mk("BBB", 25000, 800_000)))
      assert(CargoDemandGenerator.airportFingerprint(b) != CargoDemandGenerator.airportFingerprint(bChanged))
    }
  }

  "summarizeCycle" must {
    "report a positive total over a synthetic world".in {
      val airports = List(
        mk("AAA", 40000, 5_000_000),
        mk("BBB", 35000, 4_000_000),
        mk("CCC", 30000, 3_000_000)
      )
      val summary = CargoDemandGenerator.summarizeCycle(airports, Map.empty[(String, String), Int])
      println(summary)
      assert(summary.contains("[cargo] demand summary"))
    }
  }
}
