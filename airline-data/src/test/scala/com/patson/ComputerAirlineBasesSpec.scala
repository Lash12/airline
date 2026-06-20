package com.patson

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

/**
 * Phase H-4: the pure decision helpers behind NPC base expansion (no DB). These encode the
 * conservative guardrails — promote an already-served city (never a global sweep), require a real
 * cash cushion, and respect the per-NPC base ceiling.
 */
class ComputerAirlineBasesSpec extends AnyWordSpecLike with Matchers {

  "candidateBaseAirports".must {
    "promote the most-served non-base cities, most-served first".in {
      val served = Map(10 -> 5, 20 -> 30, 30 -> 12)
      ComputerAirlineBases.candidateBaseAirports(served, baseAirportIds = Set.empty, limit = 10) shouldBe List(20, 30, 10)
    }
    "exclude airports the airline already has a base at".in {
      val served = Map(10 -> 5, 20 -> 30, 30 -> 12)
      ComputerAirlineBases.candidateBaseAirports(served, baseAirportIds = Set(20), limit = 10) shouldBe List(30, 10)
    }
    "respect the candidate limit".in {
      val served = Map(10 -> 5, 20 -> 30, 30 -> 12)
      ComputerAirlineBases.candidateBaseAirports(served, baseAirportIds = Set.empty, limit = 1) shouldBe List(20)
    }
    "break ties on weight deterministically by airport id".in {
      val served = Map(30 -> 7, 10 -> 7, 20 -> 7)
      ComputerAirlineBases.candidateBaseAirports(served, baseAirportIds = Set.empty, limit = 10) shouldBe List(10, 20, 30)
    }
    "return nothing when every served city is already a base".in {
      val served = Map(10 -> 5, 20 -> 30)
      ComputerAirlineBases.candidateBaseAirports(served, baseAirportIds = Set(10, 20), limit = 10) shouldBe Nil
    }
  }

  "canAfford".must {
    "allow a base only when the cash cushion is met".in {
      ComputerAirlineBases.canAfford(balance = 30_000_000L, cost = 10_000_000L, cushion = 3.0) shouldBe true
    }
    "reject when the balance is below cost * cushion".in {
      ComputerAirlineBases.canAfford(balance = 29_999_999L, cost = 10_000_000L, cushion = 3.0) shouldBe false
    }
    "treat an exact match as affordable".in {
      ComputerAirlineBases.canAfford(balance = 30_000_000L, cost = 10_000_000L, cushion = 3.0) shouldBe true
    }
    "treat a negative cushion as zero (only require non-negative balance vs free)".in {
      ComputerAirlineBases.canAfford(balance = 0L, cost = 10_000_000L, cushion = -1.0) shouldBe true
    }
  }

  "underBaseCeiling".must {
    "allow another base while below the ceiling".in {
      ComputerAirlineBases.underBaseCeiling(baseCount = 5, max = 6) shouldBe true
    }
    "block a new base at the ceiling".in {
      ComputerAirlineBases.underBaseCeiling(baseCount = 6, max = 6) shouldBe false
    }
    "block when already above the ceiling".in {
      ComputerAirlineBases.underBaseCeiling(baseCount = 7, max = 6) shouldBe false
    }
  }
}
