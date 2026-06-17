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
}
