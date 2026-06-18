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

  case class Recommendation(from : Airport, to : Airport, distance : Int, estWeeklyProfit : Long, model : Model, config : AirplaneConfiguration, familyKey : String, familyInFleet : Int)

  /** Family key for fleet-commonality: the model family if set, else the model name. */
  def familyKeyOf(model : Model) : String = if (model.family.nonEmpty) model.family else model.name

  /** Fractional ranking bonus (0..maxBonus) for a model whose family the player already operates,
    * growing with how many of that family are in the fleet. Pure; unit-tested. */
  def commonalityScore(model : Model, fleetByFamily : Map[String, Int]) : Double = {
    val count = fleetByFamily.getOrElse(familyKeyOf(model), 0)
    Math.min(SoloConfig.consultantCommonalityMaxBonus, count * SoloConfig.consultantCommonalityPerFrame)
  }

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
                      fleetByFamily : Map[String, Int],
                      currentCycle : Int) : List[Recommendation] = {
    val depth = adviceDepth(levels)
    if (depth <= 0 || ownedModels.isEmpty) return Nil
    // Fleet-commonality bias only applies once the consultant is experienced enough.
    val considerCommonality = levels.nonEmpty && levels.max >= SoloConfig.consultantCommonalityLevel

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

    // Each candidate yields (recommendation, rankScore); rankScore folds in the commonality bonus
    // when applicable, so commonality affects both the per-route plane choice and the overall order,
    // while the displayed estWeeklyProfit stays the honest operating figure.
    val scored = candidates.flatMap { case (home, to, demand) =>
      bestForRoute(airline, home, to, demand, ownedModels, countryRelationships, currentCycle, fleetByFamily, considerCommonality)
    }
    scored.filter(_._1.estWeeklyProfit > 0).sortBy(-_._2).take(depth).map(_._1)
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

  /** Best model for this route as (recommendation, rankScore). rankScore = est profit, scaled by the
    * fleet-commonality bonus when considerCommonality — so a slightly-less-profitable plane from a
    * family the player already flies can win, and the displayed profit stays honest. */
  private def bestForRoute(airline : Airline, from : Airport, to : Airport, demand : DemandGenerator.Demand,
                           models : List[Model], countryRelationships : Map[(String, String), Int], currentCycle : Int,
                           fleetByFamily : Map[String, Int], considerCommonality : Boolean) : Option[(Recommendation, Double)] = {
    val distance = Computation.calculateDistance(from, to)
    val fitting = models.filter(m => m.range >= distance && to.runwayLength >= m.runwayRequirement && from.runwayLength >= m.runwayRequirement)
    if (fitting.isEmpty) return None
    val bothWays = demandByClass(from, to, countryRelationships) + demandByClass(to, from, countryRelationships)
    fitting.flatMap { model =>
      buildLink(airline, from, to, model, demand, distance, currentCycle).map { case (link, config) =>
        val profit = estimateWeeklyProfit(link, bothWays, currentCycle)
        val key = familyKeyOf(model)
        val count = fleetByFamily.getOrElse(key, 0)
        val rankScore = profit.toDouble * (if (considerCommonality) 1.0 + commonalityScore(model, fleetByFamily) else 1.0)
        (Recommendation(from, to, distance, profit, model, config, key, count), rankScore)
      }
    }.sortBy(-_._2).headOption
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
