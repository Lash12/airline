package com.patson

import com.patson.data.{AirlineSource, LinkSource, SoloConfig}
import com.patson.model._
import com.patson.model.airplane.{Airplane, AirplaneConfiguration, LinkAssignment, Model}

/**
  * Route/fleet consultant engine (single-player QOL, Phase "Advisor"). Studies a player airline's
  * network and surfaces the most profitable route opportunities it isn't already serving — ranked by
  * a real-cost-model profit estimate — plus a suggested aircraft + cabin config. Advice-only: it
  * never opens or changes anything.
  *
  * Shares the same underlying primitives as the NPC growth path (DemandGenerator base demand,
  * Pricing, LinkSimulation cost model) but is independent of it, so the live NPC code is untouched.
  * How much advice is produced scales with the assigned consultant(s)' level (the leveling-manager
  * proficiency) and how many are assigned, capped for balance.
  */
object ConsultantAdvisor {
  private val SEAT_TARGET_MULTIPLIER = 1.5

  case class Recommendation(from : Airport, to : Airport, distance : Int, estWeeklyProfit : Long, model : Model, config : AirplaneConfiguration)

  /** How many recommendations to surface, from the assigned consultants' levels (pure; tested).
    * Empty → 0 (no consultant). Best level drives depth; extra consultants add a little; capped. */
  def adviceDepth(levels : Seq[Int]) : Int = {
    if (levels.isEmpty) 0
    else {
      val best = levels.max
      val extra = levels.size - 1
      Math.min(SoloConfig.consultantMaxRecs,
        SoloConfig.consultantBaseRecs + best * SoloConfig.consultantRecsPerLevel + extra * SoloConfig.consultantRecsPerExtraConsultant)
    }
  }

  /** Top route recommendations for the airline, using models it already owns. allAirports and
    * countryRelationships are loaded once by the caller. */
  def recommendations(airline : Airline,
                      levels : Seq[Int],
                      allAirports : List[Airport],
                      countryRelationships : Map[(String, String), Int],
                      ownedModels : List[Model],
                      currentCycle : Int) : List[Recommendation] = {
    val depth = adviceDepth(levels)
    if (depth <= 0 || ownedModels.isEmpty) return Nil

    val airportById = allAirports.map(a => (a.id, a)).toMap
    val baseAirports = AirlineSource.loadAirlineBasesByAirline(airline.id).flatMap(b => airportById.get(b.airport.id))
    if (baseAirports.isEmpty) return Nil

    val served : Set[(Int, Int)] = LinkSource.loadFlightLinksByAirlineId(airline.id)
      .flatMap(l => List((l.from.id, l.to.id), (l.to.id, l.from.id))).toSet

    val maxRange = ownedModels.map(_.range).max
    val minRunway = ownedModels.map(_.runwayRequirement).min

    val candidates = baseAirports.flatMap { home =>
      rankedDestinations(home, maxRange, minRunway, allAirports, served, countryRelationships, SoloConfig.consultantCandidateLimit)
        .map { case (to, demand) => (home, to, demand) }
    }

    val scored = candidates.flatMap { case (home, to, demand) =>
      bestForRoute(airline, home, to, demand, ownedModels, countryRelationships, currentCycle)
    }
    scored.filter(_.estWeeklyProfit > 0).sortBy(-_.estWeeklyProfit).take(depth)
  }

  private def rankedDestinations(home : Airport, maxRange : Int, minRunway : Int, allAirports : List[Airport],
                                 served : Set[(Int, Int)], countryRelationships : Map[(String, String), Int], limit : Int) : List[(Airport, DemandGenerator.Demand)] = {
    allAirports.iterator.filter { to =>
      to.id != home.id && to.runwayLength >= minRunway && !served.contains((home.id, to.id))
    }.flatMap { to =>
      val distance = Computation.calculateDistance(home, to)
      if (distance <= 0 || distance > maxRange || !DemandGenerator.canHaveDemand(home, to, distance)) None
      else {
        val rel = countryRelationships.getOrElse((home.countryCode, to.countryCode), 0)
        if (rel < 0) None
        else {
          val affinity = Computation.calculateAffinityValue(home.zone, to.zone, rel)
          val demand = DemandGenerator.computeBaseDemandBetweenAirports(home, to, affinity, distance)
          val total = demand.travelerDemand.total + demand.businessDemand.total + demand.touristDemand.total
          if (total <= 0) None else Some((to, demand, total))
        }
      }
    }.toList.sortBy(-_._3).take(Math.max(1, limit)).map { case (a, d, _) => (a, d) }
  }

  /** Best owned model for this route, with its profit estimate. */
  private def bestForRoute(airline : Airline, from : Airport, to : Airport, demand : DemandGenerator.Demand,
                           models : List[Model], countryRelationships : Map[(String, String), Int], currentCycle : Int) : Option[Recommendation] = {
    val distance = Computation.calculateDistance(from, to)
    val fitting = models.filter(m => m.range >= distance && to.runwayLength >= m.runwayRequirement && from.runwayLength >= m.runwayRequirement)
    if (fitting.isEmpty) return None
    val bothWays = demandByClass(from, to, countryRelationships) + demandByClass(to, from, countryRelationships)
    fitting.flatMap { model =>
      buildLink(airline, from, to, model, demand, distance, currentCycle).map { case (link, config) =>
        Recommendation(from, to, distance, estimateWeeklyProfit(link, bothWays, currentCycle), model, config)
      }
    }.sortBy(-_.estWeeklyProfit).headOption
  }

  private def buildLink(airline : Airline, from : Airport, to : Airport, model : Model, demand : DemandGenerator.Demand, distance : Int, currentCycle : Int) : Option[(Link, AirplaneConfiguration)] = {
    val flightMinutesPerFreq = Computation.calculateFlightMinutesRequired(model, distance)
    if (flightMinutesPerFreq <= 0) return None
    val config = AirplaneConfiguration.default(airline, model)
    val seatsPerFlight = config.economyVal + config.businessVal + config.firstVal
    if (seatsPerFlight <= 0) return None
    val demandTotal = demand.travelerDemand.total + demand.businessDemand.total + demand.touristDemand.total
    val targetSeats = (demandTotal * SEAT_TARGET_MULTIPLIER).toInt
    val freqForDemand = Math.max(1, Math.ceil(targetSeats.toDouble / seatsPerFlight).toInt)
    val freqByMinutes = Airplane.MAX_FLIGHT_MINUTES / flightMinutesPerFreq
    val frequency = Math.min(Math.min(freqForDemand, freqByMinutes), Computation.calculateMaxFrequency(model, distance))
    if (frequency <= 0) return None

    val priceMod = if (from.popMiddleIncome < 100_000 || to.popMiddleIncome < 100_000) 1.2 else 1.0
    val cat = Computation.getFlightCategory(from, to)
    val econ = (priceMod * Pricing.computeStandardPrice(distance, cat, ECONOMY, PassengerType.TRAVELER, from.baseIncome)).toInt
    val biz = (priceMod * Pricing.computeStandardPrice(distance, cat, BUSINESS, PassengerType.BUSINESS, from.baseIncome)).toInt
    val first = (priceMod * Pricing.computeStandardPrice(distance, cat, FIRST, PassengerType.BUSINESS, from.baseIncome)).toInt
    val duration = Computation.calculateDuration(model, distance)
    val airplane = Airplane(model, airline, currentCycle, currentCycle, Airplane.MAX_CONDITION, model.price, isSold = false, configuration = config, home = from, isReady = true)
    val assignment = LinkAssignment(frequency, frequency * flightMinutesPerFreq)
    val capacity = LinkClassValues(config.economyVal, config.businessVal, config.firstVal) * frequency
    val link = Link(from, to, airline, LinkClassValues(econ, biz, first), distance, capacity, 40, duration = duration, frequency = frequency)
    link.setAssignedAirplanes(Map(airplane -> assignment))
    Some((link, config))
  }

  private def estimateWeeklyProfit(link : Link, demandByClass : LinkClassValues, currentCycle : Int) : Long = {
    val capture = SoloConfig.consultantCaptureRatio
    val cap = link.capacity
    val est = LinkClassValues(
      Math.min(cap.economyVal, (demandByClass.economyVal * capture).toInt),
      Math.min(cap.businessVal, (demandByClass.businessVal * capture).toInt),
      Math.min(cap.firstVal, (demandByClass.firstVal * capture).toInt))
    link.addSoldSeats(est)
    val profit = LinkSimulation.computeFlightLinkConsumptionDetail(link, currentCycle).profit
    link.soldSeats = LinkClassValues.getInstance()
    profit.toLong
  }

  private def demandByClass(from : Airport, to : Airport, countryRelationships : Map[(String, String), Int]) : LinkClassValues = {
    val distance = Computation.calculateDistance(from, to)
    val rel = countryRelationships.getOrElse((from.countryCode, to.countryCode), 0)
    val affinity = Computation.calculateAffinityValue(from.zone, to.zone, rel)
    val d = DemandGenerator.computeBaseDemandBetweenAirports(from, to, affinity, distance)
    d.travelerDemand + d.businessDemand + d.touristDemand
  }
}
