package com.patson

import com.patson.data.{AirlineSource, LinkSource, SoloConfig}
import com.patson.model.NonPlayerAirline

/**
  * Living-world dynamic AI (single-player). A cheap per-cycle phase that lets
  * computer-controlled (NonPlayerAirline) carriers react instead of sitting
  * static. MVP scope is deliberately drop-only: each acting NPC cancels its
  * worst persistently money-losing route. This is self-limiting (it can only
  * remove links, never add cost or explode the network) and bounded to a
  * rotating subset of NPCs per cycle, so it cannot destabilize the economy or
  * blow the cycle budget. Gated behind solo.ai.enabled (default off).
  *
  * Player airlines are never touched — only NonPlayerAirline carriers are loaded.
  */
object ComputerAirlineSimulation {
  def simulate(cycle : Int) : Unit = {
    if (!SoloConfig.aiEnabled) return

    try {
      val npcAirlines = AirlineSource.loadAirlinesByCriteria(List(("airline_type", NonPlayerAirline.id)))
      if (npcAirlines.isEmpty) return

      // Act on a rotating, bounded subset each cycle to keep the cost small.
      val perCycle = Math.max(1, SoloConfig.aiAirlinesPerCycle)
      val sorted = npcAirlines.sortBy(_.id)
      val offset = ((cycle.toLong * perCycle) % sorted.size).toInt
      val acting = (0 until Math.min(perCycle, sorted.size)).map(i => sorted((offset + i) % sorted.size))

      var totalDrops = 0
      acting.foreach { airline =>
        try {
          // Sum recent profit per link over the lookback window; drop the worst
          // links whose cumulative profit is below the (negative) threshold.
          val consumptions = LinkSource.loadLinkConsumptionsByAirline(airline.id, SoloConfig.aiLossLookbackCycles)
          if (consumptions.nonEmpty) {
            val profitByLink = consumptions.groupBy(_.link.id).map { case (linkId, list) => (linkId, list.map(_.profit.toLong).sum) }
            val losers = profitByLink.filter(_._2 < SoloConfig.aiDropProfitThreshold).toList.sortBy(_._2)
            losers.take(Math.max(0, SoloConfig.aiMaxDropsPerAirline)).foreach { case (linkId, totalProfit) =>
              LinkSource.deleteLink(linkId)
              totalDrops += 1
              println(s"[ai] ${airline.name} dropped losing link $linkId (profit $totalProfit over ${SoloConfig.aiLossLookbackCycles} cycles)")
            }
          }
        } catch {
          case e : Exception => println(s"[ai] error processing airline ${airline.id}: ${e.getMessage}")
        }
      }
      println(s"[ai] cycle $cycle: ${acting.size} NPC airline(s) acted, $totalDrops link(s) dropped")
    } catch {
      case e : Exception => println(s"[ai] ComputerAirlineSimulation failed (skipping): ${e.getClass.getSimpleName}: ${e.getMessage}")
    }
  }
}
