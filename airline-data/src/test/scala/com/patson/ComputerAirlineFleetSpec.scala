package com.patson

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

/**
 * Phase H-3: the pure helper deciding which NPCs still need an airplane-renewal threshold seeded
 * (so the existing AirplaneSimulation.renewAirplanes path keeps their fleets alive). The renewal
 * economics themselves are the engine's existing, tested logic.
 */
class ComputerAirlineFleetSpec extends AnyWordSpecLike with Matchers {

  "renewalSeeds".must {
    "seed only NPCs that don't already have a threshold".in {
      ComputerAirlineFleet.renewalSeeds(Seq(1, 2, 3), alreadySet = Set(2), threshold = 40) should
        contain theSameElementsAs List((1, 40), (3, 40))
    }
    "seed nothing when every NPC already has one".in {
      ComputerAirlineFleet.renewalSeeds(Seq(1, 2), alreadySet = Set(1, 2, 99), threshold = 40) shouldBe empty
    }
    "dedupe repeated ids".in {
      ComputerAirlineFleet.renewalSeeds(Seq(5, 5, 6), alreadySet = Set.empty, threshold = 30) should
        contain theSameElementsAs List((5, 30), (6, 30))
    }
    "carry the configured threshold".in {
      ComputerAirlineFleet.renewalSeeds(Seq(9), alreadySet = Set.empty, threshold = 25) shouldBe List((9, 25))
    }
  }
}
