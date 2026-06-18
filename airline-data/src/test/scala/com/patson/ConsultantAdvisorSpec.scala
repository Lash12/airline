package com.patson

import com.patson.model.airplane.{Manufacturer, Model}
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

  "commonalityScore".must {
    val m = Model("A320neo", "A320", 180, 5, 0.1, 0.1, 800, 5000, 50000000, 30, 4, Manufacturer("Airbus", countryCode = "FR"), 2000)
    "be 0 when the family is not in the fleet".in {
      ConsultantAdvisor.commonalityScore(m, Map.empty) shouldBe 0.0
    }
    "grow with the number of that family's frames owned (default 0.03/frame)".in {
      ConsultantAdvisor.commonalityScore(m, Map("A320" -> 4)) shouldBe (0.12 +- 0.0001)
    }
    "cap at the max bonus (default 0.25)".in {
      ConsultantAdvisor.commonalityScore(m, Map("A320" -> 100)) shouldBe 0.25
    }
    "fall back to the model name when the family is blank".in {
      ConsultantAdvisor.commonalityScore(m.copy(family = ""), Map("A320neo" -> 3)) shouldBe (0.09 +- 0.0001)
    }
  }
}
