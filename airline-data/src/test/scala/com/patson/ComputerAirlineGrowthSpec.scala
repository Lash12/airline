package com.patson

import com.patson.model.LinkClassValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

/**
 * Phase H-1: the pure decision helpers behind NPC route opening (no DB). These encode the
 * bounded-growth guardrails — frequency sizing, conservative demand capture, and the
 * "open the single best profitable route, only under the network ceiling" rule.
 */
class ComputerAirlineGrowthSpec extends AnyWordSpecLike with Matchers {

  "sizeFrequency".must {
    "size to demand when demand is the binding constraint".in {
      // 150 demand * 1.5 = 225 target seats / 150 seats-per-flight = 2 (ceil) frequency,
      // well under the minute/maxFreq caps.
      ComputerAirlineGrowth.sizeFrequency(demandTotal = 150, seatsPerFlight = 150, spareMinutes = 6480, flightMinutesPerFreq = 300, maxFreqPerPlane = 20) shouldBe 2
    }
    "cap at the frame's spare flight-minutes".in {
      // demand would want many frequencies, but only 900 spare minutes / 300 = 3 flights fit.
      ComputerAirlineGrowth.sizeFrequency(demandTotal = 100000, seatsPerFlight = 100, spareMinutes = 900, flightMinutesPerFreq = 300, maxFreqPerPlane = 50) shouldBe 3
    }
    "cap at the model's max frequency for the distance".in {
      ComputerAirlineGrowth.sizeFrequency(demandTotal = 100000, seatsPerFlight = 100, spareMinutes = 100000, flightMinutesPerFreq = 60, maxFreqPerPlane = 7) shouldBe 7
    }
    "return 0 for degenerate inputs (no seats or no flight time)".in {
      ComputerAirlineGrowth.sizeFrequency(150, seatsPerFlight = 0, 6480, 300, 20) shouldBe 0
      ComputerAirlineGrowth.sizeFrequency(150, 150, 6480, flightMinutesPerFreq = 0, 20) shouldBe 0
    }
    "return 0 when spare minutes can't fit even one flight".in {
      ComputerAirlineGrowth.sizeFrequency(150, 150, spareMinutes = 200, flightMinutesPerFreq = 300, maxFreqPerPlane = 20) shouldBe 0
    }
  }

  "estimatedSeats".must {
    val capacity = LinkClassValues(200, 20, 4)
    "capture a fraction of demand when demand is below capacity".in {
      val demand = LinkClassValues(100, 10, 2)
      ComputerAirlineGrowth.estimatedSeats(demand, capacity, capture = 0.5) shouldBe LinkClassValues(50, 5, 1)
    }
    "never exceed capacity even when demand is high".in {
      val demand = LinkClassValues(10000, 1000, 100)
      ComputerAirlineGrowth.estimatedSeats(demand, capacity, capture = 0.65) shouldBe capacity
    }
  }

  "selectBestOpen".must {
    val candidates = List(("A", 100L), ("B", 5000L), ("C", -200L))
    "pick the most profitable candidate clearing the threshold".in {
      ComputerAirlineGrowth.selectBestOpen(networkSize = 10, maxNetworkSize = 60, candidates, profitThreshold = 0L) shouldBe Some("B")
    }
    "open nothing when no candidate clears the threshold".in {
      ComputerAirlineGrowth.selectBestOpen(10, 60, candidates, profitThreshold = 10000L) shouldBe None
    }
    "open nothing when the network is at the ceiling (bounded growth)".in {
      ComputerAirlineGrowth.selectBestOpen(networkSize = 60, maxNetworkSize = 60, candidates, profitThreshold = 0L) shouldBe None
    }
    "treat the threshold as strict (profit must exceed it)".in {
      ComputerAirlineGrowth.selectBestOpen(10, 60, List(("only", 100L)), profitThreshold = 100L) shouldBe None
    }
  }
}
