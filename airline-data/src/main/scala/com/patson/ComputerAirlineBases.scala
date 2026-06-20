package com.patson

import com.patson.data.{AirlineSource, AirplaneSource, CountrySource, LinkSource, SoloConfig}
import com.patson.model._
import com.patson.model.airplane.Airplane

/**
  * Phase H-4 of the living-world AI: let a thriving NPC occasionally open a NEW BASE, the most
  * conservative growth step. It exists because H-1 can only open routes from airports where the NPC
  * already has a base AND a spare frame is homed, so each NPC's reach is permanently capped by its
  * existing bases — a US carrier can never build an intra-Europe cluster. H-4 lifts that ceiling.
  *
  * To keep the new hub from being a stranded, upkeep-bleeding expense, opening a base is a single
  * self-contained action: promote a city the NPC ALREADY flies to (a destination of its existing
  * links) into a scale-1 base, re-home one fully-idle owned frame there, and launch that frame's best
  * profitable first route — reusing H-1's exact candidate/profit machinery
  * (ComputerAirlineGrowth.bestRouteFromBase). H-1 then grows the cluster around it on later cycles.
  *
  * Deliberately bounded and self-limiting:
  *  - only NonPlayerAirline carriers act (callers pass the same rotating subset as drops/growth);
  *    players are never touched.
  *  - at most `aiBasesMaxOpeningsPerCycle` base(s) open across ALL acting NPCs per cycle (rare,
  *    legible — "notice a change every few sessions").
  *  - a per-NPC `aiBasesMaxPerAirline` base ceiling prevents runaway expansion.
  *  - the real base construction cost is paid via the ledger and the NPC must hold cash >=
  *    cost * `aiBasesCashCushion` AND have a first route clearing `aiBasesOpenProfitThreshold`, so
  *    only genuinely thriving carriers expand. No aircraft purchases — re-homes an existing idle
  *    frame only (buying stays H-3's domain).
  *
  * Gated behind solo.ai.bases.enabled (default off) — a no-op otherwise, so default/multiplayer
  * deploys are byte-identical.
  */
object ComputerAirlineBases {

  // ---- Pure decision helpers (no DB), unit-tested in ComputerAirlineBasesSpec ----

  /** Candidate base sites: the airports the NPC already serves (weighted by how much it flies there)
    * that are not already bases, most-served first, capped to `limit`. Promoting a focus city keeps
    * expansion cheap (no global airport sweep) and legible. */
  def candidateBaseAirports(servedWeightByAirport : Map[Int, Int], baseAirportIds : Set[Int], limit : Int) : List[Int] =
    servedWeightByAirport.toList
      .filterNot { case (airportId, _) => baseAirportIds.contains(airportId) }
      .sortBy { case (airportId, weight) => (-weight, airportId) }
      .map(_._1)
      .take(Math.max(0, limit))

  /** A base is affordable only if it leaves a healthy cash cushion, so it never starves the NPC. */
  def canAfford(balance : Long, cost : Long, cushion : Double) : Boolean =
    balance.toDouble >= cost.toDouble * Math.max(0.0, cushion)

  /** Whether the NPC may add another base without breaching its hard ceiling. */
  def underBaseCeiling(baseCount : Int, max : Int) : Boolean = baseCount < Math.max(0, max)

  /**
    * Attempt to open one new base for the acting NPCs, stopping once `aiBasesMaxOpeningsPerCycle`
    * openings have happened this cycle. `allAirports` is loaded once by the caller and shared.
    * Returns the number of bases opened.
    */
  def expand(acting : Seq[Airline], allAirports : List[Airport], cycle : Int) : Int = {
    val maxOpenings = Math.max(0, SoloConfig.aiBasesMaxOpeningsPerCycle)
    if (!SoloConfig.aiBasesEnabled || maxOpenings == 0) return 0

    val airportById = allAirports.map(a => (a.id, a)).toMap
    val countryRelationships = CountrySource.getCountryMutualRelationships()

    var opened = 0
    acting.foreach { airline =>
      if (opened < maxOpenings) {
        try {
          opened += expandAirline(airline, allAirports, airportById, countryRelationships, cycle)
        } catch {
          case e : Exception => println(s"[ai-bases] error processing airline ${airline.id}: ${e.getMessage}")
        }
      }
    }
    opened
  }

  private def expandAirline(airline : Airline,
                            allAirports : List[Airport],
                            airportById : Map[Int, Airport],
                            countryRelationships : Map[(String, String), Int],
                            cycle : Int) : Int = {
    // Per-NPC base ceiling so an NPC cannot sprawl without bound.
    val bases = AirlineSource.loadAirlineBasesByAirline(airline.id)
    if (!underBaseCeiling(bases.size, SoloConfig.aiBasesMaxPerAirline)) return 0
    val baseAirportIds = bases.map(_.airport.id).toSet

    // Candidate sites come from the airline's own network — cities it already flies to.
    val existingLinks = LinkSource.loadFlightLinksByAirlineId(airline.id)
    if (existingLinks.isEmpty) return 0
    val servedWeight = existingLinks
      .flatMap(link => List(link.from.id, link.to.id).map(id => (id, link.frequency)))
      .groupBy(_._1)
      .map { case (airportId, pairs) => (airportId, pairs.map(_._2).sum) }
    val candidates = candidateBaseAirports(servedWeight, baseAirportIds, SoloConfig.aiBasesCandidateLimit)
      .flatMap(airportById.get)
    if (candidates.isEmpty) return 0

    // Fully-idle frames (no assigned links) — required so we can re-home one to the new base
    // (re-homing is forbidden while a frame has assigned links).
    val assignmentsByAirplaneId = AirplaneSource.loadAirplaneLinkAssignmentsByOwner(airline.id)
    val idleFrames = AirplaneSource.loadAirplanesByOwner(airline.id)
      .filter(p => p.isReady && !p.isSold)
      .filter(p => assignmentsByAirplaneId.get(p.id).forall(_.assignments.isEmpty))
    if (idleFrames.isEmpty) return 0

    // Already-served city pairs (either direction) so the seeded first route never duplicates one.
    val served : Set[(Int, Int)] = existingLinks.flatMap(l => List((l.from.id, l.to.id), (l.to.id, l.from.id))).toSet
    val framesToConsider = idleFrames.take(Math.max(1, SoloConfig.aiGrowthFramesConsidered))
    val balance = airline.getBalance()

    // For each affordable, title-allowed candidate base, find the best profitable first route from
    // it (an idle frame flown at full availability). Keep the globally best (base, frame, link).
    val evaluated = candidates.flatMap { airport =>
      val prospective = AirlineBase(airline, airport, airport.countryCode, scale = 1, foundedCycle = cycle, headquarter = false)
      // allowAirline returns Left(requiredTitle) when the NPC lacks the country title for this airport.
      if (prospective.allowAirline(airline).isLeft) {
        None
      } else {
        val cost = prospective.calculateUpgradeCost()
        if (!canAfford(balance, cost, SoloConfig.aiBasesCashCushion)) {
          None
        } else {
          framesToConsider.flatMap { frame =>
            ComputerAirlineGrowth.bestRouteFromBase(airline, airport, frame, Airplane.MAX_FLIGHT_MINUTES, allAirports, served, countryRelationships, cycle)
              .map { case (link, score) => (prospective, cost, frame, link, score) }
          }.sortBy(-_._5).headOption
        }
      }
    }

    evaluated.filter(_._5 > SoloConfig.aiBasesOpenProfitThreshold).sortBy(-_._5).headOption match {
      case Some((prospective, cost, frame, link, score)) =>
        AirlineSource.saveAirlineBase(prospective)
        AirlineSource.saveLedgerEntry(AirlineLedgerEntry(airline.id, cycle, LedgerType.BASE_CONSTRUCTION, -cost, Some(s"${prospective.airport.iata} Lv1")))
        // Re-home the chosen idle frame to the new base, then open its first route.
        frame.home = prospective.airport
        AirplaneSource.updateAirplanesDetails(List(frame))
        LinkSource.saveLink(link)
        WorldNews.post(s"${airline.name} opened a base at ${prospective.airport.iata} and launched its ${link.from.iata}-${link.to.iata} route", cycle, Some(s"rival_${airline.id}"))
        println(s"[ai-bases] ${airline.name} opened base at ${prospective.airport.iata}, launched ${link.from.iata}-${link.to.iata} (est weekly profit $score, base cost $cost)")
        1
      case None => 0
    }
  }
}
