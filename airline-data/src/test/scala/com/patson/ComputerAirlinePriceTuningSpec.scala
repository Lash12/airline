package com.patson

import com.patson.model.LinkClassValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

/**
 * Phase H-2: pure decision helpers for NPC price tuning (no DB). Encodes the bounded,
 * load-factor-driven nudge and the clamp to a band around the standard price.
 */
class ComputerAirlinePriceTuningSpec extends AnyWordSpecLike with Matchers {

  "priceNudgeFactor".must {
    "nudge price up when the route is persistently full".in {
      ComputerAirlinePriceTuning.priceNudgeFactor(0.95, lowLF = 0.5, highLF = 0.85, step = 0.05) shouldBe 1.05
    }
    "nudge price down when the route is persistently empty".in {
      ComputerAirlinePriceTuning.priceNudgeFactor(0.30, 0.5, 0.85, 0.05) shouldBe 0.95
    }
    "leave price unchanged in the comfortable middle band".in {
      ComputerAirlinePriceTuning.priceNudgeFactor(0.70, 0.5, 0.85, 0.05) shouldBe 1.0
    }
    "treat the thresholds as inclusive".in {
      ComputerAirlinePriceTuning.priceNudgeFactor(0.85, 0.5, 0.85, 0.05) shouldBe 1.05
      ComputerAirlinePriceTuning.priceNudgeFactor(0.50, 0.5, 0.85, 0.05) shouldBe 0.95
    }
  }

  "nudgedPrice".must {
    val standard = LinkClassValues(200, 500, 1000)
    "scale each class by the factor when within the band".in {
      val current = LinkClassValues(200, 500, 1000)
      ComputerAirlinePriceTuning.nudgedPrice(current, standard, factor = 1.05, floorRatio = 0.6, ceilRatio = 1.5) shouldBe LinkClassValues(210, 525, 1050)
    }
    "never exceed the ceiling band even after repeated raises".in {
      val current = LinkClassValues(295, 740, 1480) // already near 1.5x standard
      val out = ComputerAirlinePriceTuning.nudgedPrice(current, standard, factor = 1.05, floorRatio = 0.6, ceilRatio = 1.5)
      out shouldBe LinkClassValues(300, 750, 1500) // clamped to 1.5x
    }
    "never drop below the floor band even after repeated cuts".in {
      val current = LinkClassValues(125, 310, 620) // near 0.6x standard
      val out = ComputerAirlinePriceTuning.nudgedPrice(current, standard, factor = 0.95, floorRatio = 0.6, ceilRatio = 1.5)
      out shouldBe LinkClassValues(120, 300, 600) // clamped to 0.6x
    }
  }
}
