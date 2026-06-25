package com.patson

import com.patson.model.ExecutiveRole
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

/**
 * Executive (C-suite) buff math. These cover the pure, level-keyed helpers and the disabled
 * short-circuit. The airline-keyed lookups (which read the roster cache / DB) and the per-cycle
 * salary aggregation are exercised by the controller/sim paths, not here.
 *
 * Assertions use the Phase-1 SoloConfig defaults: salary base/per-level 30000; fuel & maintenance
 * discount 0.04/level capped at 0.12; CCO advice +1/level capped at 5; rep thresholds CFO 0 / COO 200
 * / CCO 400. The feature flag (solo.exec.enabled) defaults off in the test config.
 */
class ExecutiveBuffsSpec extends AnyWordSpecLike with Matchers {

  "fuelCostMultiplierAtLevel".must {
    "be neutral (1.0) at level 0".in {
      ExecutiveBuffs.fuelCostMultiplierAtLevel(0) shouldBe 1.0
    }
    "discount per level".in {
      ExecutiveBuffs.fuelCostMultiplierAtLevel(1) shouldBe (0.96 +- 0.0001) // 1 - 1*0.04
      ExecutiveBuffs.fuelCostMultiplierAtLevel(2) shouldBe (0.92 +- 0.0001)
    }
    "floor at the cap".in {
      ExecutiveBuffs.fuelCostMultiplierAtLevel(3) shouldBe (0.88 +- 0.0001) // 1 - 0.12 (cap reached)
      ExecutiveBuffs.fuelCostMultiplierAtLevel(9) shouldBe (0.88 +- 0.0001) // capped
    }
  }

  "maintenanceCostMultiplierAtLevel".must {
    "discount per level and floor at the cap".in {
      ExecutiveBuffs.maintenanceCostMultiplierAtLevel(0) shouldBe 1.0
      ExecutiveBuffs.maintenanceCostMultiplierAtLevel(1) shouldBe (0.96 +- 0.0001)
      ExecutiveBuffs.maintenanceCostMultiplierAtLevel(3) shouldBe (0.88 +- 0.0001)
      ExecutiveBuffs.maintenanceCostMultiplierAtLevel(9) shouldBe (0.88 +- 0.0001)
    }
  }

  "adviceDepthBonusAtLevel".must {
    "be 0 at level 0 and add per level, capped".in {
      ExecutiveBuffs.adviceDepthBonusAtLevel(0) shouldBe 0
      ExecutiveBuffs.adviceDepthBonusAtLevel(1) shouldBe 1
      ExecutiveBuffs.adviceDepthBonusAtLevel(5) shouldBe 5
      ExecutiveBuffs.adviceDepthBonusAtLevel(8) shouldBe 5 // capped
    }
  }

  "salaryForLevel".must {
    "be base at level 1 and add per level".in {
      ExecutiveBuffs.salaryForLevel(1) shouldBe 30000
      ExecutiveBuffs.salaryForLevel(2) shouldBe 60000
      ExecutiveBuffs.salaryForLevel(3) shouldBe 90000
    }
  }

  "repThreshold".must {
    "match the configured Phase-1 thresholds".in {
      ExecutiveBuffs.repThreshold(ExecutiveRole.CFO) shouldBe 0.0
      ExecutiveBuffs.repThreshold(ExecutiveRole.COO) shouldBe 200.0
      ExecutiveBuffs.repThreshold(ExecutiveRole.CCO) shouldBe 400.0
    }
    "be unreachable for seats not offered in Phase 1".in {
      ExecutiveBuffs.repThreshold(ExecutiveRole.CMO) shouldBe Double.MaxValue
    }
  }

  "isUnlocked".must {
    "gate on reputation, inclusive at the threshold".in {
      ExecutiveBuffs.isUnlocked(0, ExecutiveRole.CFO) shouldBe true
      ExecutiveBuffs.isUnlocked(199, ExecutiveRole.COO) shouldBe false
      ExecutiveBuffs.isUnlocked(200, ExecutiveRole.COO) shouldBe true
      ExecutiveBuffs.isUnlocked(644, ExecutiveRole.CCO) shouldBe true
      ExecutiveBuffs.isUnlocked(399, ExecutiveRole.CCO) shouldBe false
    }
  }

  "the airline-keyed lookups when the feature is disabled".must {
    "return neutral values without touching the roster".in {
      ExecutiveBuffs.fuelCostMultiplier(99999) shouldBe 1.0
      ExecutiveBuffs.maintenanceCostMultiplier(99999) shouldBe 1.0
      ExecutiveBuffs.adviceDepthBonus(99999) shouldBe 0
      ExecutiveBuffs.totalWeeklySalary(99999) shouldBe 0L
    }
  }

  "ConsultantAdvisor.effectiveDepth".must {
    "equal the base depth when no CCO bonus applies (feature off)".in {
      ConsultantAdvisor.effectiveDepth(Seq(2), 99999) shouldBe ConsultantAdvisor.adviceDepth(Seq(2))
    }
  }
}
