package com.patson

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class RouteForecastLogicSpec extends AnyWordSpecLike with Matchers {

  "RouteForecastService recommendation helpers" should {
    "summarize competition details in plain English" in {
      RouteForecastService.competitionSummary(0, 0) shouldBe "No direct competitors."
      RouteForecastService.competitionSummary(1, 7) should include("1 competitor with light frequency")
      RouteForecastService.competitionSummary(3, 42) should include("heavy frequency")
    }

    "map forecast economics into actionable recommendation labels" in {
      RouteForecastService.recommendationFor(10_000, "HIGH", "LOW", 500, blocked = false) shouldBe (("OPEN", "positive"))
      RouteForecastService.recommendationFor(10_000, "LOW", "LOW", 500, blocked = false) shouldBe (("OPEN_CAUTIOUSLY", "warning"))
      RouteForecastService.recommendationFor(-1, "LOW", "HIGH", 500, blocked = false) shouldBe (("AVOID", "negative"))
      RouteForecastService.recommendationFor(10_000, "HIGH", "LOW", 500, blocked = true) shouldBe (("BLOCKED", "blocked"))
    }

    "explain confidence and cargo revenue share" in {
      RouteForecastService.confidenceExplanation("LOW", 50, 0, "NONE") should include("passenger demand is thin")
      RouteForecastService.confidenceExplanation("MEDIUM", 180, 20, "LOW") should include("Medium confidence")
      RouteForecastService.cargoShareEstimate(25, 100) shouldBe 0.25
      RouteForecastService.cargoShareEstimate(25, 0) shouldBe 0.0
    }
  }
}
