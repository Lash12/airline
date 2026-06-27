package com.patson

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

/**
 * Advisor: the pure depth helper that turns assigned consultants' levels into how many
 * recommendations to surface (defaults: base 3, +2/level of the best, +2 per extra consultant,
 * capped at 15).
 */
class ConsultantAdvisorSpec extends AnyWordSpecLike with Matchers {

  "adviceDepth".must {
    "be 0 with no consultant assigned".in {
      ConsultantAdvisor.adviceDepth(Nil) shouldBe 0
    }
    "give the base count for a single trainee (level 0)".in {
      ConsultantAdvisor.adviceDepth(Seq(0)) shouldBe 3
    }
    "scale with the best consultant's level".in {
      ConsultantAdvisor.adviceDepth(Seq(2)) shouldBe 7   // 3 + 2*2
      ConsultantAdvisor.adviceDepth(Seq(4)) shouldBe 11  // 3 + 4*2
    }
    "add a bonus per extra consultant, driven by the best level".in {
      ConsultantAdvisor.adviceDepth(Seq(1, 3)) shouldBe 11 // best 3 -> 3+6, +1 extra*2
    }
    "cap the total".in {
      ConsultantAdvisor.adviceDepth(Seq(4, 4, 4)) shouldBe 15 // 3 + 8 + 2*2 = 15
      ConsultantAdvisor.adviceDepth(Seq(4, 4, 4, 4)) shouldBe 15 // would be 17, capped
    }
  }

  "marketCount".must {
    "be 0 below the market level (or no consultant)".in {
      ConsultantAdvisor.marketCount(Nil) shouldBe 0
      ConsultantAdvisor.marketCount(Seq(0)) shouldBe 0
      ConsultantAdvisor.marketCount(Seq(1)) shouldBe 0 // default marketLevel = 2
    }
    "give the base count at the market level and +1 per level above".in {
      ConsultantAdvisor.marketCount(Seq(2)) shouldBe 5 // base
      ConsultantAdvisor.marketCount(Seq(3)) shouldBe 6
      ConsultantAdvisor.marketCount(Seq(4)) shouldBe 7
      ConsultantAdvisor.marketCount(Seq(2, 4)) shouldBe 7 // driven by the best
    }
  }

  "targetSeatsPerFlight".must {
    "right-size to ~daily capture of both-way demand".in {
      ConsultantAdvisor.targetSeatsPerFlight(1291) shouldBe 129 // 1291*0.7/7
      ConsultantAdvisor.targetSeatsPerFlight(700) shouldBe 70
    }
    "never be below 1".in {
      ConsultantAdvisor.targetSeatsPerFlight(0) shouldBe 1
      ConsultantAdvisor.targetSeatsPerFlight(5) shouldBe 1
    }
  }

  "demandReason".must {
    "label strong demand at 500+ pax/wk".in {
      ConsultantAdvisor.demandReason(500) should startWith("Strong demand")
      ConsultantAdvisor.demandReason(1000) should startWith("Strong demand")
    }
    "label moderate demand between 100 and 499".in {
      ConsultantAdvisor.demandReason(100) should startWith("Moderate demand")
      ConsultantAdvisor.demandReason(499) should startWith("Moderate demand")
    }
    "label thin market below 100".in {
      ConsultantAdvisor.demandReason(0) should startWith("Thin market")
      ConsultantAdvisor.demandReason(99) should startWith("Thin market")
    }
    "include the demand figure in the text".in {
      ConsultantAdvisor.demandReason(750) should include("750")
    }
  }

  "competitionReason".must {
    "say no competition when capacity is 0".in {
      ConsultantAdvisor.competitionReason(0) shouldBe "No direct competition"
    }
    "say low competition for small capacity (1–199)".in {
      ConsultantAdvisor.competitionReason(1) shouldBe "Low competition"
      ConsultantAdvisor.competitionReason(199) shouldBe "Low competition"
    }
    "say moderate competition for 200–999".in {
      ConsultantAdvisor.competitionReason(200) shouldBe "Moderate competition"
      ConsultantAdvisor.competitionReason(999) shouldBe "Moderate competition"
    }
    "say crowded lane at 1000+".in {
      ConsultantAdvisor.competitionReason(1000) shouldBe "Crowded lane"
      ConsultantAdvisor.competitionReason(5000) shouldBe "Crowded lane"
    }
  }

  "fleetReason".must {
    "report fleet commonality when the family is already in service".in {
      val reason = ConsultantAdvisor.fleetReason("Boeing 737", 3, "Boeing 737-800")
      reason should include("commonality")
      reason should include("3")
      reason should include("Boeing 737")
    }
    "report fleet expansion required when familyInFleet is 0".in {
      val reason = ConsultantAdvisor.fleetReason("Boeing 737", 0, "Boeing 737-800")
      reason should include("fleet expansion")
      reason should include("Boeing 737-800")
    }
  }

  "commonalityScore".must {
    "be 0 when the family is not in the fleet".in {
      ConsultantAdvisor.commonalityScore("A320", Map.empty) shouldBe 0.0
    }
    "grow with the number of that family's frames owned (default 0.03/frame)".in {
      ConsultantAdvisor.commonalityScore("A320", Map("A320" -> 4)) shouldBe (0.12 +- 0.0001)
    }
    "cap at the max bonus (default 0.25)".in {
      ConsultantAdvisor.commonalityScore("A320", Map("A320" -> 100)) shouldBe 0.25
    }
  }
}
