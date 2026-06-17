package com.patson

import com.patson.data.{AirlineSource, SoloConfig}
import com.patson.model.Airline

/**
  * Phase H-3 of the living-world AI: keep NPC fleets from quietly decaying to nothing over long
  * games. The sim already renews worn aircraft in place — `AirplaneSimulation.renewAirplanes` buys a
  * replacement, sells the old frame via the ledger, resets condition, and keeps the same airplane id
  * so link assignments are preserved — BUT only for airlines that have an "airplane renewal"
  * threshold set. Players get one at sign-up (40); NPCs never do, so their planes decay to condition
  * 0 and get scrapped, shrinking the network over a long game.
  *
  * H-3 simply seeds that threshold for NPCs, so the existing, battle-tested renewal path keeps their
  * fleets alive. It is financially self-limiting (renewAirplanes already respects each airline's
  * balance and minimum renewal balance, renewing lowest-condition frames first). No new
  * purchase/ledger/assignment logic is introduced.
  *
  * Gated behind solo.ai.fleet.enabled (default off) — a no-op otherwise.
  */
object ComputerAirlineFleet {
  /** Which NPCs still need a renewal threshold seeded, as (airlineId, threshold) pairs. Pure;
    * unit-tested in ComputerAirlineFleetSpec. */
  def renewalSeeds(npcIds : Seq[Int], alreadySet : Set[Int], threshold : Int) : List[(Int, Int)] =
    npcIds.distinct.filterNot(alreadySet.contains).map(id => (id, threshold)).toList

  /** Ensure every NPC has an airplane-renewal threshold so the sim renews their aging fleet.
    * Idempotent — only seeds airlines that don't already have one. Returns the number seeded. */
  def ensureRenewal(npcAirlines : Seq[Airline]) : Int = {
    if (!SoloConfig.aiFleetEnabled) return 0
    val alreadySet = AirlineSource.loadAirplaneRenewals().keySet
    val seeds = renewalSeeds(npcAirlines.map(_.id), alreadySet, SoloConfig.aiFleetRenewalThreshold)
    seeds.foreach { case (airlineId, threshold) => AirlineSource.saveAirplaneRenewal(airlineId, threshold) }
    if (seeds.nonEmpty) println(s"[ai-fleet] seeded renewal threshold ${SoloConfig.aiFleetRenewalThreshold} for ${seeds.size} NPC airline(s)")
    seeds.size
  }
}
