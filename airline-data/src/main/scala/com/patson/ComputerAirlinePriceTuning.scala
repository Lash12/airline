package com.patson

import com.patson.data.{LinkSource, SoloConfig}
import com.patson.model._

/**
  * Phase H-2 of the living-world AI: lets an acting NPC nudge prices on a few of its existing
  * links toward equilibrium, so competition feels adaptive rather than static — the missing
  * middle between opening routes (H-1) and dropping them. Deliberately bounded:
  *
  *  - only NonPlayerAirline carriers (callers pass the same rotating subset); players untouched.
  *  - a persistently FULL link (load factor >= high) edges its price UP to capture revenue; a
  *    persistently EMPTY one (load factor <= low) edges DOWN to fill seats / undercut a rival.
  *    Between the thresholds, nothing changes.
  *  - one small step per cycle (default ±5%), clamped to a band around the standard price so
  *    prices can never run away; at most `maxLinksPerAirline` links touched per airline per cycle.
  *  - changes existing links only (a targeted price-only DB update) — no fleet/route churn, no
  *    news spam (price nudges are too granular to surface).
  *
  * Gated behind solo.ai.pricetune.enabled (default off) — a no-op otherwise.
  */
object ComputerAirlinePriceTuning {

  // ---- Pure decision helpers (no DB), unit-tested in ComputerAirlinePriceTuningSpec ----

  /** Price multiplier for one cycle from recent load factor: up a step when persistently full,
    * down a step when persistently empty, unchanged in the comfortable middle band. */
  def priceNudgeFactor(loadFactor : Double, lowLF : Double, highLF : Double, step : Double) : Double = {
    if (loadFactor >= highLF) 1.0 + step
    else if (loadFactor <= lowLF) 1.0 - step
    else 1.0
  }

  /** Apply the factor to each class price, clamped to [floorRatio, ceilRatio] * standard price so
    * a price can never run away from the route's fair value over many cycles. */
  def nudgedPrice(current : LinkClassValues, standard : LinkClassValues, factor : Double, floorRatio : Double, ceilRatio : Double) : LinkClassValues = {
    def clampClass(cur : Int, std : Int) : Int = {
      val floor = (std * floorRatio).toInt
      val ceil = Math.max(floor, (std * ceilRatio).toInt)
      Math.max(floor, Math.min(ceil, (cur * factor).toInt))
    }
    LinkClassValues(clampClass(current.economyVal, standard.economyVal), clampClass(current.businessVal, standard.businessVal), clampClass(current.firstVal, standard.firstVal))
  }

  /** Tune prices for each acting airline. Returns the number of links adjusted. */
  def tune(acting : Seq[Airline], cycle : Int) : Int = {
    if (!SoloConfig.aiPriceTuneEnabled) return 0
    var adjusted = 0
    acting.take(Math.max(0, SoloConfig.aiPriceTuneMaxAirlinesPerCycle)).foreach { airline =>
      try {
        adjusted += tuneAirline(airline)
      } catch {
        case e : Exception => println(s"[ai-pricetune] error processing airline ${airline.id}: ${e.getMessage}")
      }
    }
    adjusted
  }

  private def tuneAirline(airline : Airline) : Int = {
    val lookback = Math.max(1, SoloConfig.aiPriceTuneLookbackCycles)
    val consumptions = LinkSource.loadLinkConsumptionsByAirline(airline.id, lookback)
    if (consumptions.isEmpty) return 0

    val lowLF = SoloConfig.aiPriceTuneLowLoadFactor
    val highLF = SoloConfig.aiPriceTuneHighLoadFactor
    val midLF = (lowLF + highLF) / 2

    // Average load factor per link over the window; only links that are out of the comfort band
    // and whose price would actually move are candidates.
    val candidates = consumptions.filter(_.link.getTotalCapacity > 0).groupBy(_.link.id).flatMap { case (linkId, entries) =>
      val avgLF = entries.map(_.link.getLoadFactor).sum / entries.size
      val factor = priceNudgeFactor(avgLF, lowLF, highLF, SoloConfig.aiPriceTuneStep)
      if (factor == 1.0) None
      else entries.maxBy(_.cycle).link match {
        case link : Link =>
          val std = standardPrice(link.from, link.to, link.distance)
          val newPrice = nudgedPrice(link.price, std, factor, SoloConfig.aiPriceTuneFloorRatio, SoloConfig.aiPriceTuneCeilRatio)
          if (newPrice == link.price) None else Some((linkId, link, avgLF, newPrice))
        case _ => None
      }
    }.toList

    // Touch only the few most out-of-equilibrium links per cycle.
    val chosen = candidates.sortBy(c => -Math.abs(c._3 - midLF)).take(Math.max(0, SoloConfig.aiPriceTuneMaxLinksPerAirline))
    chosen.foreach { case (linkId, link, avgLF, newPrice) =>
      LinkSource.updateLinkPrice(linkId, newPrice)
      println(f"[ai-pricetune] ${airline.name} ${link.from.iata}-${link.to.iata} LF=$avgLF%.2f econ ${link.price.economyVal}->${newPrice.economyVal}")
    }
    chosen.size
  }

  private def standardPrice(from : Airport, to : Airport, distance : Int) : LinkClassValues = {
    val category = Computation.getFlightCategory(from, to)
    LinkClassValues(
      Pricing.computeStandardPrice(distance, category, ECONOMY, PassengerType.TRAVELER, from.baseIncome),
      Pricing.computeStandardPrice(distance, category, BUSINESS, PassengerType.BUSINESS, from.baseIncome),
      Pricing.computeStandardPrice(distance, category, FIRST, PassengerType.BUSINESS, from.baseIncome))
  }
}
