package com.patson

import com.patson.model.ExecutiveRole
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

/**
 * Executive (C-suite) Phase-2 leveling rules. Pure; uses the SoloConfig defaults: xpPerLevel 4,
 * maxLevel 5, COO on-time xp threshold 0.9, CCO load-factor xp threshold 0.75.
 */
class ExecutiveProgressionSpec extends AnyWordSpecLike with Matchers {
  import ExecutiveProgression.Kpi

  "earnsXp".must {
    "reward the CFO only on a profitable week".in {
      ExecutiveProgression.earnsXp(ExecutiveRole.CFO, Kpi(1, 0.0, 0.0)) shouldBe true
      ExecutiveProgression.earnsXp(ExecutiveRole.CFO, Kpi(0, 1.0, 1.0)) shouldBe false
      ExecutiveProgression.earnsXp(ExecutiveRole.CFO, Kpi(-5, 1.0, 1.0)) shouldBe false
    }
    "reward the COO at/above the on-time threshold".in {
      ExecutiveProgression.earnsXp(ExecutiveRole.COO, Kpi(-100, 0.9, 0.0)) shouldBe true
      ExecutiveProgression.earnsXp(ExecutiveRole.COO, Kpi(999, 0.89, 1.0)) shouldBe false
    }
    "reward the CCO at/above the load-factor threshold".in {
      ExecutiveProgression.earnsXp(ExecutiveRole.CCO, Kpi(0, 0.0, 0.75)) shouldBe true
      ExecutiveProgression.earnsXp(ExecutiveRole.CCO, Kpi(0, 1.0, 0.74)) shouldBe false
    }
    "never reward a seat not offered in Phase 1".in {
      ExecutiveProgression.earnsXp(ExecutiveRole.CMO, Kpi(999, 1.0, 1.0)) shouldBe false
    }
  }

  "levelForXp".must {
    "start at 1 and step up every xpPerLevel, capped at maxLevel".in {
      ExecutiveProgression.levelForXp(0) shouldBe 1
      ExecutiveProgression.levelForXp(3) shouldBe 1
      ExecutiveProgression.levelForXp(4) shouldBe 2
      ExecutiveProgression.levelForXp(8) shouldBe 3
      ExecutiveProgression.levelForXp(16) shouldBe 5
      ExecutiveProgression.levelForXp(100) shouldBe 5 // capped
    }
  }

  "xpForNextLevel".must {
    "give the cumulative xp for the next level, and None at the cap".in {
      ExecutiveProgression.xpForNextLevel(1) shouldBe Some(4)
      ExecutiveProgression.xpForNextLevel(2) shouldBe Some(8)
      ExecutiveProgression.xpForNextLevel(4) shouldBe Some(16)
      ExecutiveProgression.xpForNextLevel(5) shouldBe None
    }
  }
}
