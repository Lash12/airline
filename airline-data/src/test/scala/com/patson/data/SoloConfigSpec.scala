package com.patson.data

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
  * With no solo.* keys set, every SoloConfig value must equal the upstream
  * constant it replaced, so default/multiplayer behavior is unchanged.
  */
class SoloConfigSpec extends AnyFlatSpec with Matchers {
  "SoloConfig" should "default to the original upstream constants" in {
    SoloConfig.startingBalance shouldBe 0L
    SoloConfig.minRenewalBalance shouldBe 300000L
    SoloConfig.apGenerationRate shouldBe 0.1
    SoloConfig.apMaxCyclesStored shouldBe (24 * 4)
    SoloConfig.loanDefaultAnnualRate shouldBe 0.11
    SoloConfig.bankruptcyCashThreshold shouldBe -10000000
    SoloConfig.bankruptcyAssetsThreshold shouldBe -100000000
    SoloConfig.negotiationReadyEnabled shouldBe false
  }
}
