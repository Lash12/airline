package com.patson

import com.patson.data.{AirlineSource, AirplaneSource, CountrySource, LinkSource, SoloConfig}
import com.patson.model._
import com.patson.model.airplane.{Airplane, LinkAssignment}

/**
  * Phase H-1 of the living-world AI: lets an acting NPC OPEN one good route — the growth
  * counterpart to ComputerAirlineSimulation's drops. Deliberately bounded and self-limiting:
  *
  *  - only NonPlayerAirline carriers act (callers pass the same rotating subset as drops);
  *    players are never touched.
  *  - uses an existing SPARE (idle) owned airplane and never buys aircraft (that is H-3).
  *    Frames freed by the drop path are the natural supply, so the world reshapes rather than
  *    inflates.
  *  - opens at most `aiMaxOpensPerAirline` route(s) per acting airline per cycle.
  *  - the candidate set is the top `aiGrowthCandidateLimit` reachable airports by cached base
  *    demand from the airline's own bases — never a blind all-airport sweep.
  *  - opens only if the projected weekly profit (reusing LinkSimulation's real cost model on an
  *    estimated, capped load factor) clears `aiOpenProfitThreshold`, and only while the network
  *    is under `aiMaxNetworkSize`.
  *
  * Gated behind solo.ai.growth.enabled (default off) — a no-op otherwise, so default/multiplayer
  * deploys are byte-identical.
  */
object ComputerAirlineGrowth {
  private val SEAT_TARGET_MULTIPLIER = 1.5 // mirror AirlineGenerator: size frequency a bit above raw demand

  // ---- Pure decision helpers (no DB), unit-tested in ComputerAirlineGrowthSpec ----

  /** Frequency for a new route: sized to demand, then capped by the frame's spare flight-minutes
    * and the model's max frequency for the distance. 0 if the inputs can't support a flight. */
  def sizeFrequency(demandTotal : Int, seatsPerFlight : Int, spareMinutes : Int, flightMinutesPerFreq : Int, maxFreqPerPlane : Int) : Int = {
    if (seatsPerFlight <= 0 || flightMinutesPerFreq <= 0) return 0
    val targetSeats = (demandTotal * SEAT_TARGET_MULTIPLIER).toInt
    val freqForDemand = Math.max(1, Math.ceil(targetSeats.toDouble / seatsPerFlight).toInt)
    val freqByMinutes = spareMinutes / flightMinutesPerFreq
    Math.max(0, Math.min(Math.min(freqForDemand, freqByMinutes), maxFreqPerPlane))
  }

  /** Estimated sold seats for a hypothetical route: a fraction of base demand per class, capped by
    * the link's capacity. Conservative (a new entrant doesn't capture all demand). */
  def estimatedSeats(demandByClass : LinkClassValues, capacity : LinkClassValues, capture : Double) : LinkClassValues =
    LinkClassValues(
      Math.min(capacity.economyVal, (demandByClass.economyVal * capture).toInt),
      Math.min(capacity.businessVal, (demandByClass.businessVal * capture).toInt),
      Math.min(capacity.firstVal, (demandByClass.firstVal * capture).toInt))

  /** Choose the single best route to open: the most profitable candidate clearing the threshold,
    * but only while the network is under the ceiling. None enforces the bounded-growth guardrails. */
  def selectBestOpen[A](networkSize : Int, maxNetworkSize : Int, candidates : List[(A, Long)], profitThreshold : Long) : Option[A] = {
    if (networkSize >= maxNetworkSize) None
    else candidates.filter(_._2 > profitThreshold).sortBy(-_._2).headOption.map(_._1)
  }

  /**
    * Attempt to open routes for each acting airline. `allAirports` is loaded once by the caller
    * and shared; `playerIds` drives the (optional) news feed. Returns the number of routes opened.
    */
  def grow(acting : Seq[Airline], allAirports : List[Airport], cycle : Int) : Int = {
    if (!SoloConfig.aiGrowthEnabled || Math.max(0, SoloConfig.aiMaxOpensPerAirline) == 0) return 0

    val airportById = allAirports.map(a => (a.id, a)).toMap
    val countryRelationships = CountrySource.getCountryMutualRelationships()

    var opened = 0
    // Only a few of the acting NPCs grow per cycle, so the world changes slowly and legibly.
    acting.take(Math.max(0, SoloConfig.aiMaxGrowthAirlinesPerCycle)).foreach { airline =>
      try {
        opened += growAirline(airline, allAirports, airportById, countryRelationships, cycle)
      } catch {
        case e : Exception => println(s"[ai-growth] error processing airline ${airline.id}: ${e.getMessage}")
      }
    }
    opened
  }

  /** Total base demand per class for one direction (traveler + business + tourist). */
  private def demandByClass(from : Airport, to : Airport, countryRelationships : Map[(String, String), Int]) : LinkClassValues = {
    val distance = Computation.calculateDistance(from, to)
    val relationship = countryRelationships.getOrElse((from.countryCode, to.countryCode), 0)
    val affinity = Computation.calculateAffinityValue(from.zone, to.zone, relationship)
    val d = DemandGenerator.computeBaseDemandBetweenAirports(from, to, affinity, distance)
    d.travelerDemand + d.businessDemand + d.touristDemand
  }

  private def growAirline(airline : Airline,
                          allAirports : List[Airport],
                          airportById : Map[Int, Airport],
                          countryRelationships : Map[(String, String), Int],
                          cycle : Int) : Int = {
    val maxOpens = Math.max(0, SoloConfig.aiMaxOpensPerAirline)

    // Network-size ceiling so an NPC cannot explode (also re-checked per open below).
    val existingLinks = LinkSource.loadFlightLinksByAirlineId(airline.id)
    val networkSize = existingLinks.size
    if (networkSize >= SoloConfig.aiMaxNetworkSize) return 0

    // Bases are the legal origins; resolve to full airport objects (needed for demand calc).
    val baseAirports = AirlineSource.loadAirlineBasesByAirline(airline.id).flatMap(b => airportById.get(b.airport.id))
    if (baseAirports.isEmpty) return 0
    val baseById = baseAirports.map(a => (a.id, a)).toMap

    // Spare frames: owned, in service, not for sale, with enough idle weekly flight-minutes.
    val assignmentsByAirplaneId = AirplaneSource.loadAirplaneLinkAssignmentsByOwner(airline.id)
    def availableMinutes(p : Airplane) : Int =
      Airplane.MAX_FLIGHT_MINUTES - assignmentsByAirplaneId.get(p.id).map(_.assignments.values.map(_.flightMinutes).sum).getOrElse(0)
    val spareFrames = AirplaneSource.loadAirplanesByOwner(airline.id)
      .filter(p => p.isReady && !p.isSold)
      .map(p => (p, availableMinutes(p)))
      .filter(_._2 >= SoloConfig.aiGrowthMinAvailableMinutes)
      .sortBy(-_._2)
    if (spareFrames.isEmpty) return 0

    // Already-served city pairs (either direction) so we never duplicate a route.
    val served : Set[(Int, Int)] = existingLinks.flatMap(l => List((l.from.id, l.to.id), (l.to.id, l.from.id))).toSet

    if (maxOpens <= 0) return 0

    // Evaluate the few most-idle frames against their best candidate routes and pick the globally
    // best (frame, route) pair. Matching frame size to route demand matters: forcing the single
    // largest spare frame onto a thin route flies it empty (unprofitable), so it would rarely open.
    val framesToConsider = spareFrames.take(Math.max(1, SoloConfig.aiGrowthFramesConsidered))
    val scoredAll : List[(Link, Long)] = framesToConsider.flatMap { case (airplane, minutes) =>
      val home = baseById.getOrElse(airplane.home.id, baseAirports.head)
      val candidates = bestCandidates(home, airplane.model, allAirports, served, countryRelationships, SoloConfig.aiGrowthCandidateLimit)
      candidates.flatMap { case (toAirport, fwdDemand) =>
        buildCandidateLink(airline, home, toAirport, airplane, minutes, fwdDemand).map { link =>
          // A link serves both directions, so estimate captured seats from outbound + return demand.
          val bothWays = demandByClass(home, toAirport, countryRelationships) + demandByClass(toAirport, home, countryRelationships)
          val estimatedProfit = estimateWeeklyProfit(link, bothWays, cycle)
          (link, ComputerAirlineStrategy.scoreProfit(airline.id, home, toAirport, bothWays, estimatedProfit))
        }
      }
    }
    selectBestOpen(networkSize, SoloConfig.aiMaxNetworkSize, scoredAll, SoloConfig.aiOpenProfitThreshold) match {
      case Some(link) =>
        val profit = scoredAll.find(_._1 eq link).map(_._2).getOrElse(0L)
        LinkSource.saveLink(link)
        WorldNews.post(s"${airline.name} opened its ${link.from.iata}-${link.to.iata} route", cycle, Some(s"rival_${airline.id}"))
        println(s"[ai-growth] ${airline.name} opened ${link.from.iata}-${link.to.iata} (est weekly profit $profit)")
        1
      case None => 0
    }
  }

  /** Top reachable destinations from `from` by cached base demand, cheaply pre-filtered. */
  private def bestCandidates(from : Airport,
                             model : com.patson.model.airplane.Model,
                             allAirports : List[Airport],
                             served : Set[(Int, Int)],
                             countryRelationships : Map[(String, String), Int],
                             limit : Int) : List[(Airport, DemandGenerator.Demand)] = {
    allAirports.iterator.filter { to =>
      to.id != from.id &&
        to.runwayLength >= model.runwayRequirement &&
        !served.contains((from.id, to.id))
    }.flatMap { to =>
      val distance = Computation.calculateDistance(from, to)
      if (distance <= 0 || distance > model.range || !DemandGenerator.canHaveDemand(from, to, distance)) {
        None
      } else {
        val relationship = countryRelationships.getOrElse((from.countryCode, to.countryCode), 0)
        if (relationship < 0) {
          None
        } else {
          val affinity = Computation.calculateAffinityValue(from.zone, to.zone, relationship)
          val demand = DemandGenerator.computeBaseDemandBetweenAirports(from, to, affinity, distance)
          val total = demand.travelerDemand.total + demand.businessDemand.total + demand.touristDemand.total
          if (total <= 0) None else Some((to, demand, total))
        }
      }
    }.toList.sortBy(-_._3).take(Math.max(1, limit)).map { case (airport, demand, _) => (airport, demand) }
  }

  /** Construct a viable flight link for `airplane` on from->to, sizing frequency to demand and
    * the frame's spare minutes. Mirrors AirlineGenerator.createLink's pricing/duration, but reuses
    * the existing spare airplane rather than minting a new one. None if not flyable. */
  private def buildCandidateLink(airline : Airline,
                                 from : Airport,
                                 to : Airport,
                                 airplane : Airplane,
                                 spareMinutes : Int,
                                 demand : DemandGenerator.Demand) : Option[Link] = {
    val model = airplane.model
    val distance = Computation.calculateDistance(from, to)
    if (distance <= 0 || model.range < distance || to.runwayLength < model.runwayRequirement) return None

    val flightMinutesPerFreq = Computation.calculateFlightMinutesRequired(model, distance)
    if (flightMinutesPerFreq <= 0) return None

    val seatsPerFlight = airplane.configuration.economyVal + airplane.configuration.businessVal + airplane.configuration.firstVal
    val demandTotal = demand.travelerDemand.total + demand.businessDemand.total + demand.touristDemand.total
    val maxFreqPerPlane = Computation.calculateMaxFrequency(model, distance)
    val frequency = sizeFrequency(demandTotal, seatsPerFlight, spareMinutes, flightMinutesPerFreq, maxFreqPerPlane)
    if (frequency <= 0) return None

    val priceMod = if (from.popMiddleIncome < 100_000 || to.popMiddleIncome < 100_000) 1.2 else 1.0
    val flightCategory = Computation.getFlightCategory(from, to)
    val econPrice = (priceMod * Pricing.computeStandardPrice(distance, flightCategory, ECONOMY, PassengerType.TRAVELER, from.baseIncome)).toInt
    val bizPrice = (priceMod * Pricing.computeStandardPrice(distance, flightCategory, BUSINESS, PassengerType.BUSINESS, from.baseIncome)).toInt
    val firstPrice = (priceMod * Pricing.computeStandardPrice(distance, flightCategory, FIRST, PassengerType.BUSINESS, from.baseIncome)).toInt

    val duration = Computation.calculateDuration(model, distance)
    val assignment = LinkAssignment(frequency, frequency * flightMinutesPerFreq)
    val capacity = LinkClassValues(airplane.configuration.economyVal, airplane.configuration.businessVal, airplane.configuration.firstVal) * frequency

    val link = Link(from, to, airline, LinkClassValues(econPrice, bizPrice, firstPrice), distance, capacity, SoloConfig.aiGrowthRawQuality, duration = duration, frequency = frequency)
    link.setAssignedAirplanes(Map(airplane -> assignment))
    Some(link)
  }

  /** Projected weekly profit for a fresh link: seed an estimated, capacity-capped load factor
    * from cached base demand (both directions, conservative capture), then run the real cost model. */
  private def estimateWeeklyProfit(link : Link, demandByClass : LinkClassValues, cycle : Int) : Long = {
    val estSeats = estimatedSeats(demandByClass, link.capacity, SoloConfig.aiGrowthCaptureRatio)
    link.addSoldSeats(estSeats)
    val profit = LinkSimulation.computeFlightLinkConsumptionDetail(link, cycle).profit
    // reset so the link we persist carries no runtime consumption state
    link.soldSeats = LinkClassValues.getInstance()
    profit.toLong
  }
}
