package com.patson

import com.patson.data.{AirlineSource, AirplaneSource, LinkSource, SoloConfig, CountrySource, CycleSource, AirportSource}
import com.patson.data.airplane.ModelSource
import com.patson.model._
import com.patson.model.airplane.{Airplane, AirplaneConfiguration, LinkAssignment, Model}
import scala.collection.mutable.ListBuffer

object RouteForecastService {

  case class AircraftCandidate(
    modelName: String,
    frequency: Int,
    weeklyPaxCapacity: Int,
    weeklyCargoCapacity: Int, // belly cargo units × freq; 0 if cargo disabled
    estimatedRevenue: Long,
    estimatedCost: Long,
    estimatedProfit: Long,
    youOwnThis: Boolean,
    note: String
  )

  case class RouteForecastResult(
    originAirportId: Int,
    destinationAirportId: Int,
    passengerDemandEstimate: Int,
    cargoDemandEstimate: Int,
    expectedRevenue: Long,
    expectedCost: Long,
    expectedProfit: Long,
    confidenceLevel: String, // "HIGH", "MEDIUM", "LOW"
    competitionLevel: String, // "NONE", "LOW", "MEDIUM", "HIGH"
    recommendedAircraftModels: List[String], // backward-compat: names from candidateAircraft
    recommendedFrequency: Option[Int],       // backward-compat: freq of primary candidate
    reasons: List[String],
    candidateAircraft: List[AircraftCandidate],
    competitorCount: Int = 0,
    competitorTotalFrequency: Int = 0,
    competitionSummary: String = "No direct competitors.",
    confidenceExplanation: String = "Low confidence: estimate is based on thin data or unusual route conditions.",
    recommendation: String = "WAIT",
    recommendationSeverity: String = "neutral",
    cargoShareEstimate: Double = 0.0,
    aircraftRecommendationReason: String = "No aircraft recommendation available."
  )

  def competitionSummary(competitorCount: Int, competitorTotalFrequency: Int): String = {
    if (competitorCount <= 0 || competitorTotalFrequency <= 0) "No direct competitors."
    else {
      val load = if (competitorTotalFrequency < 14) "light"
        else if (competitorTotalFrequency < 35) "moderate"
        else "heavy"
      val plural = if (competitorCount == 1) "competitor" else "competitors"
      val pressure = if (load == "heavy") "; expect pressure on price and load factor." else "."
      s"$competitorCount $plural with $load frequency$pressure"
    }
  }

  def confidenceExplanation(confidenceLevel: String,
                            passengerDemandEstimate: Int,
                            cargoDemandEstimate: Int,
                            competitionLevel: String): String = {
    confidenceLevel match {
      case "HIGH" =>
        "High confidence: both airports show strong demand signals and the forecast has enough traffic depth."
      case "MEDIUM" =>
        val caveat = if (competitionLevel == "MEDIUM") "competition is material"
          else if (passengerDemandEstimate < 200) "passenger demand is modest"
          else if (cargoDemandEstimate > passengerDemandEstimate) "cargo is a larger share of the opportunity"
          else "comparable traffic is limited"
        s"Medium confidence: demand exists, but $caveat."
      case _ =>
        val reason = if (competitionLevel == "HIGH") "competition is dense"
          else if (passengerDemandEstimate < 100) "passenger demand is thin"
          else "the economics are sensitive to small demand changes"
        s"Low confidence: $reason."
    }
  }

  def recommendationFor(expectedProfit: Long,
                        confidenceLevel: String,
                        competitionLevel: String,
                        passengerDemandEstimate: Int,
                        blocked: Boolean): (String, String) = {
    if (blocked) ("BLOCKED", "blocked")
    else if (expectedProfit < 0) ("AVOID", "negative")
    else if (expectedProfit == 0 || passengerDemandEstimate < 50) ("WAIT", "neutral")
    else if (confidenceLevel == "LOW" || competitionLevel == "HIGH" || passengerDemandEstimate < 150) ("OPEN_CAUTIOUSLY", "warning")
    else ("OPEN", "positive")
  }

  def cargoShareEstimate(cargoRevenue: Long, expectedRevenue: Long): Double =
    if (expectedRevenue <= 0) 0.0 else BigDecimal(cargoRevenue.toDouble / expectedRevenue).setScale(2, BigDecimal.RoundingMode.HALF_UP).toDouble

  def aircraftRecommendationReason(candidate: Option[AircraftCandidate],
                                   passengerDemandEstimate: Int,
                                   cargoDemandEstimate: Int): String = {
    candidate match {
      case None => "No compatible aircraft could produce a usable schedule."
      case Some(c) if passengerDemandEstimate < 150 =>
        s"${c.modelName} keeps capacity conservative on a thin market; start around ${c.frequency}x weekly."
      case Some(c) if cargoDemandEstimate > 0 && c.weeklyCargoCapacity > 0 && cargoDemandEstimate >= passengerDemandEstimate =>
        s"${c.modelName} fits the passenger demand while belly cargo is a material part of the opportunity."
      case Some(c) if cargoDemandEstimate > 200 && c.weeklyCargoCapacity > 0 =>
        s"${c.modelName} fits the route and has useful belly cargo capacity for the cargo demand."
      case Some(c) =>
        s"${c.modelName} is the best current fit for range, runway, capacity, and weekly frequency."
    }
  }

  def getForecast(airlineId: Int, originAirportId: Int, destinationAirportId: Int): Either[String, RouteForecastResult] = {
    if (!SoloConfig.routeForecastEnabled) {
      return Left("FEATURE_DISABLED: Route forecast backend is disabled in solo configuration.")
    }

    val airlineOption = AirlineSource.loadAirlineById(airlineId, fullLoad = true)
    if (airlineOption.isEmpty) {
      return Left(s"Airline $airlineId not found.")
    }
    val airline = airlineOption.get

    val fromAirportOption = AirportSource.loadAirportById(originAirportId, fullLoad = true)
    val toAirportOption = AirportSource.loadAirportById(destinationAirportId, fullLoad = true)

    if (fromAirportOption.isEmpty || toAirportOption.isEmpty) {
      return Left("UNAVAILABLE_DATA: One or both airports not found.")
    }

    val fromAirport = fromAirportOption.get
    val toAirport = toAirportOption.get
    val currentCycle = CycleSource.loadCycle()

    val distance = Computation.calculateDistance(fromAirport, toAirport).toInt
    if (distance <= 0 || !DemandGenerator.canHaveDemand(fromAirport, toAirport, distance)) {
      return Right(RouteForecastResult(
        originAirportId = originAirportId,
        destinationAirportId = destinationAirportId,
        passengerDemandEstimate = 0,
        cargoDemandEstimate = 0,
        expectedRevenue = 0L,
        expectedCost = 0L,
        expectedProfit = 0L,
        confidenceLevel = "LOW",
        competitionLevel = "NONE",
        recommendedAircraftModels = Nil,
        recommendedFrequency = None,
        reasons = List("NO_DEMAND: Distance too short or no demand configuration possible between these airports."),
        candidateAircraft = Nil,
        confidenceExplanation = confidenceExplanation("LOW", 0, 0, "NONE"),
        recommendation = "AVOID",
        recommendationSeverity = "negative",
        aircraftRecommendationReason = "No aircraft recommendation because the route has no viable demand."
      ))
    }

    val relationship = CountrySource.getCountryMutualRelationship(fromAirport.countryCode, toAirport.countryCode)
    val affinity = Computation.calculateAffinityValue(fromAirport.zone, toAirport.zone, relationship)

    val demand = DemandGenerator.computeBaseDemandBetweenAirports(fromAirport, toAirport, affinity, distance)
    val paxDemandEst = demand.travelerDemand.total + demand.businessDemand.total + demand.touristDemand.total

    val cargoDemand = if (SoloConfig.cargoEnabled) {
      CargoDemandGenerator.computeCargoDemandBetweenAirports(fromAirport, toAirport, affinity, distance) +
        CargoDemandGenerator.computeCargoDemandBetweenAirports(toAirport, fromAirport, affinity, distance)
    } else 0

    val competitorLinks = (LinkSource.loadFlightLinksByAirports(fromAirport.id, toAirport.id) ++
      LinkSource.loadFlightLinksByAirports(toAirport.id, fromAirport.id)).filterNot(_.airline.id == airlineId)
    val totalCompetitorCapacity = competitorLinks.map(_.capacity.total).sum
    val competitorCount = competitorLinks.map(_.airline.id).distinct.size
    val competitorWeeklyFrequency = competitorLinks.map(_.frequency).sum
    val competitionSummaryText = competitionSummary(competitorCount, competitorWeeklyFrequency)
    val competitionLevel = if (totalCompetitorCapacity == 0) "NONE"
      else if (totalCompetitorCapacity < paxDemandEst * 0.2) "LOW"
      else if (totalCompetitorCapacity < paxDemandEst * 0.8) "MEDIUM"
      else "HIGH"

    val allModels = ModelSource.loadAllModels()
    val fittingModels = allModels.filter(m =>
      m.speed >= 300 &&
      m.range >= distance &&
      fromAirport.runwayLength >= m.runwayRequirement &&
      toAirport.runwayLength >= m.runwayRequirement
    )

    if (fittingModels.isEmpty) {
      return Right(RouteForecastResult(
        originAirportId = originAirportId,
        destinationAirportId = destinationAirportId,
        passengerDemandEstimate = paxDemandEst,
        cargoDemandEstimate = cargoDemand,
        expectedRevenue = 0L,
        expectedCost = 0L,
        expectedProfit = 0L,
        confidenceLevel = "LOW",
        competitionLevel = competitionLevel,
        recommendedAircraftModels = Nil,
        recommendedFrequency = None,
        reasons = List("UNSUITABLE_AIRCRAFT: Distance exceeds max range or runways too short for all aircraft models in database."),
        candidateAircraft = Nil,
        competitorCount = competitorCount,
        competitorTotalFrequency = competitorWeeklyFrequency,
        competitionSummary = competitionSummaryText,
        confidenceExplanation = confidenceExplanation("LOW", paxDemandEst, cargoDemand, competitionLevel),
        recommendation = "BLOCKED",
        recommendationSeverity = "blocked",
        aircraftRecommendationReason = "No aircraft in the database fits the range and runway requirements."
      ))
    }

    val suggestedModel = ConsultantAdvisor.suggestModel(
      distance,
      fromAirport.runwayLength.toInt,
      toAirport.runwayLength.toInt,
      if (paxDemandEst < 250) paxDemandEst else paxDemandEst * 2,
      allModels
    )

    if (suggestedModel.isEmpty) {
      return Right(RouteForecastResult(
        originAirportId = originAirportId,
        destinationAirportId = destinationAirportId,
        passengerDemandEstimate = paxDemandEst,
        cargoDemandEstimate = cargoDemand,
        expectedRevenue = 0L,
        expectedCost = 0L,
        expectedProfit = 0L,
        confidenceLevel = "LOW",
        competitionLevel = competitionLevel,
        recommendedAircraftModels = Nil,
        recommendedFrequency = None,
        reasons = List("UNSUITABLE_AIRCRAFT: No suitable aircraft model found that fits range/runway constraints."),
        candidateAircraft = Nil,
        competitorCount = competitorCount,
        competitorTotalFrequency = competitorWeeklyFrequency,
        competitionSummary = competitionSummaryText,
        confidenceExplanation = confidenceExplanation("LOW", paxDemandEst, cargoDemand, competitionLevel),
        recommendation = "BLOCKED",
        recommendationSeverity = "blocked",
        aircraftRecommendationReason = "No aircraft recommendation is available for this market."
      ))
    }

    val primaryModel = suggestedModel.get

    // Route-level ticket prices (shared across candidates since they depend on airports, not aircraft)
    val priceMod = if (fromAirport.popMiddleIncome < 100_000 || toAirport.popMiddleIncome < 100_000) 1.2 else 1.0
    val cat = Computation.getFlightCategory(fromAirport, toAirport)
    val econ  = (priceMod * Pricing.computeStandardPrice(distance, cat, ECONOMY,  PassengerType.TRAVELER, fromAirport.baseIncome)).toInt
    val biz   = (priceMod * Pricing.computeStandardPrice(distance, cat, BUSINESS, PassengerType.BUSINESS, fromAirport.baseIncome)).toInt
    val first = (priceMod * Pricing.computeStandardPrice(distance, cat, FIRST,    PassengerType.BUSINESS, fromAirport.baseIncome)).toInt

    val baseCapture = SoloConfig.consultantCaptureRatio
    val adjustedCapture = competitionLevel match {
      case "NONE"   => baseCapture
      case "LOW"    => baseCapture * 0.8
      case "MEDIUM" => baseCapture * 0.5
      case "HIGH"   => baseCapture * 0.25
    }

    val bothWaysDemand = demandByClass(fromAirport, toAirport, relationship) + demandByClass(toAirport, fromAirport, relationship)

    val ownedModelIds: Set[Int] = AirplaneSource.loadAirplanesByOwner(airlineId, isSold = false).map(_.model.id).toSet

    // Simulate one model and produce an AircraftCandidate.
    // Returns None if the model cannot sustain ≥1 frequency on this route.
    def simulateCandidate(m: Model, note: String): Option[AircraftCandidate] = {
      val flightMins = Computation.calculateFlightMinutesRequired(m, distance)
      if (flightMins <= 0) return None
      val conf = AirplaneConfiguration.default(airline, m)
      val seatsPerFlight = conf.economyVal + conf.businessVal + conf.firstVal
      if (seatsPerFlight <= 0) return None

      val freqForDemand = Math.max(1, Math.ceil((paxDemandEst * 1.5).toDouble / seatsPerFlight).toInt)
      val freqByMins    = Airplane.MAX_FLIGHT_MINUTES / flightMins
      val freq = Math.min(Math.min(freqForDemand, freqByMins), Computation.calculateMaxFrequency(m, distance))
      if (freq <= 0) return None

      val cap = LinkClassValues(conf.economyVal, conf.businessVal, conf.firstVal) * freq
      val dur = Computation.calculateDuration(m, distance)
      val plane = Airplane(
        model = m, owner = airline,
        constructedCycle = currentCycle, purchasedCycle = currentCycle,
        condition = Airplane.MAX_CONDITION, purchasePrice = m.price,
        isSold = false, configuration = conf, home = fromAirport, isReady = true
      )
      val assign = LinkAssignment(freq, freq * flightMins)
      val lnk = Link(
        from = fromAirport, to = toAirport, airline = airline,
        price = LinkClassValues(econ, biz, first), distance = distance,
        capacity = cap, rawQuality = 40, duration = dur, frequency = freq
      )
      lnk.setAssignedAirplanes(Map(plane -> assign))

      val estPax = LinkClassValues(
        Math.min(cap.economyVal,  (bothWaysDemand.economyVal  * adjustedCapture).toInt),
        Math.min(cap.businessVal, (bothWaysDemand.businessVal * adjustedCapture).toInt),
        Math.min(cap.firstVal,    (bothWaysDemand.firstVal    * adjustedCapture).toInt)
      )
      lnk.addSoldSeats(estPax)

      val det = LinkSimulation.computeFlightLinkConsumptionDetail(lnk, currentCycle)
      lnk.soldSeats = LinkClassValues.getInstance()

      var rev  = det.revenue.toLong
      val cost = (det.revenue - det.profit).toLong
      val cargoCap = if (SoloConfig.cargoEnabled) m.bellyCargoCapacity * freq else 0
      if (SoloConfig.cargoEnabled && cargoDemand > 0 && cargoCap > 0) {
        val cargoCarried = Math.min(cargoCap, (cargoDemand * SoloConfig.cargoCaptureRatio).toInt)
        rev += Math.round(cargoCarried * distance * SoloConfig.cargoRevenuePerUnitKm)
      }

      val effectiveNote = if (ownedModelIds.contains(m.id)) "You already operate this aircraft type." else note

      Some(AircraftCandidate(
        modelName          = m.name,
        frequency          = freq,
        weeklyPaxCapacity  = seatsPerFlight * freq,
        weeklyCargoCapacity = cargoCap,
        estimatedRevenue   = rev,
        estimatedCost      = cost,
        estimatedProfit    = rev - cost,
        youOwnThis         = ownedModelIds.contains(m.id),
        note               = effectiveNote
      ))
    }

    // Fuel-per-seat-km: lower is more efficient
    val effScore = (m: Model) => m.cruiseBurn.toDouble / Math.max(1, m.capacity * m.speed)

    val smallerModel = fittingModels
      .filter(m => m.id != primaryModel.id && m.capacity < (primaryModel.capacity * 0.82).toInt && m.capacity > 0)
      .sortBy(effScore)
      .headOption

    val largerModel = fittingModels
      .filter(m => m.id != primaryModel.id && m.capacity > (primaryModel.capacity * 1.3).toInt)
      .sortBy(effScore)
      .headOption

    val rawCandidates: List[AircraftCandidate] = List(
      simulateCandidate(primaryModel, "Best size and efficiency for this market."),
      smallerModel.flatMap(m => simulateCandidate(m, "Smaller option; lower seat count reduces risk on thin demand.")),
      largerModel.flatMap(m => simulateCandidate(m, "Larger option; handles high-demand growth or busy corridors."))
    ).flatten

    // On thin markets, prefer the smaller candidate as primary so the form pre-selects
    // the lower-risk aircraft rather than an over-sized one.
    val thinMarket = paxDemandEst < 150
    val candidateAircraft: List[AircraftCandidate] = if (thinMarket && rawCandidates.size >= 2 && smallerModel.isDefined) {
      val smallerName = smallerModel.get.name
      val smallerCand = rawCandidates.find(_.modelName == smallerName)
      val rest = rawCandidates.filterNot(_.modelName == smallerName)
      smallerCand.toList ++ rest
    } else rawCandidates

    // Primary candidate drives the top-level summary figures (backward compat)
    val primary = candidateAircraft.headOption

    if (primary.isEmpty || primary.get.frequency <= 0) {
      return Right(RouteForecastResult(
        originAirportId = originAirportId,
        destinationAirportId = destinationAirportId,
        passengerDemandEstimate = paxDemandEst,
        cargoDemandEstimate = cargoDemand,
        expectedRevenue = 0L,
        expectedCost = 0L,
        expectedProfit = 0L,
        confidenceLevel = "LOW",
        competitionLevel = competitionLevel,
        recommendedAircraftModels = List(primaryModel.name),
        recommendedFrequency = None,
        reasons = List("UNSUITABLE_AIRCRAFT: Required flight minutes per frequency exceed available airplane weekly limit."),
        candidateAircraft = Nil,
        competitorCount = competitorCount,
        competitorTotalFrequency = competitorWeeklyFrequency,
        competitionSummary = competitionSummaryText,
        confidenceExplanation = confidenceExplanation("LOW", paxDemandEst, cargoDemand, competitionLevel),
        recommendation = "BLOCKED",
        recommendationSeverity = "blocked",
        aircraftRecommendationReason = "A compatible aircraft exists, but it cannot sustain one weekly frequency."
      ))
    }

    val primaryCandidate = primary.get
    val expectedCargoRevenue =
      if (SoloConfig.cargoEnabled && cargoDemand > 0 && primaryCandidate.weeklyCargoCapacity > 0) {
        val cargoCarried = Math.min(primaryCandidate.weeklyCargoCapacity, (cargoDemand * SoloConfig.cargoCaptureRatio).toInt)
        Math.round(cargoCarried * distance * SoloConfig.cargoRevenuePerUnitKm)
      } else 0L
    val expectedRevenue  = primaryCandidate.estimatedRevenue
    val expectedCost     = primaryCandidate.estimatedCost
    val expectedProfit   = primaryCandidate.estimatedProfit

    // Confidence: thin markets (100–199 pax/wk) cap at MEDIUM even without competition,
    // since small absolute demand swings heavily affect viability.
    val strongAirportSignals = (fromAirport.size + toAirport.size) >= 10 &&
      (fromAirport.population + toAirport.population) >= 1_000_000
    val demandSignals = paxDemandEst >= 350 || (SoloConfig.cargoEnabled && cargoDemand >= 250)
    val confidenceLevel = if (competitionLevel == "HIGH" || paxDemandEst < 100 || expectedProfit < 0) "LOW"
      else if (paxDemandEst < 200 || competitionLevel == "MEDIUM" || primaryModel.range * 0.9 <= distance || fromAirport.runwayLength < primaryModel.runwayRequirement + 200 || !strongAirportSignals || !demandSignals) "MEDIUM"
      else "HIGH"
    val confidenceExplanationText = confidenceExplanation(confidenceLevel, paxDemandEst, cargoDemand, competitionLevel)
    val (recommendationText, recommendationSeverityText) =
      recommendationFor(expectedProfit, confidenceLevel, competitionLevel, paxDemandEst, blocked = false)
    val aircraftReason = aircraftRecommendationReason(primary, paxDemandEst, cargoDemand)

    val reasons = ListBuffer[String]()

    if (paxDemandEst > 500) reasons += "Strong passenger demand on this route."
    else if (paxDemandEst < 100) reasons += "Weak passenger demand on this route; high risk of empty flights."
    else reasons += "Moderate passenger demand. Plan schedule and capacity carefully."

    if (SoloConfig.cargoEnabled) {
      if (cargoDemand > 200) reasons += s"Strong cargo demand ($cargoDemand units) can support belly cargo revenue."
      else if (cargoDemand > 0) reasons += s"Light cargo demand ($cargoDemand units) available."
      else reasons += "No cargo demand exists between these airports."
    } else {
      reasons += "Cargo simulation is disabled."
    }

    competitionLevel match {
      case "HIGH"   => reasons += s"High competition: $competitorCount airline(s) with $competitorWeeklyFrequency flights/wk — expect strong market resistance."
      case "MEDIUM" => reasons += s"Moderate competition: $competitorCount airline(s), $competitorWeeklyFrequency flights/wk."
      case "LOW"    => reasons += s"Low competition: $competitorCount airline(s), $competitorWeeklyFrequency flights/wk. Market is mostly open."
      case "NONE"   => reasons += "No direct competition on this route — a monopoly opportunity."
    }

    if (thinMarket && paxDemandEst >= 100) {
      reasons += s"Thin market (~$paxDemandEst pax/wk). Start with one frame, watch load factors before adding frequency."
    }

    if (distance > primaryModel.range * 0.9) {
      reasons += s"Warning: ${primaryModel.name} operates near its maximum range of ${primaryModel.range} km on this route."
    }
    if (fromAirport.runwayLength < primaryModel.runwayRequirement + 200 || toAirport.runwayLength < primaryModel.runwayRequirement + 200) {
      reasons += "Warning: Runway length is close to aircraft minimum requirement."
    }
    if (expectedProfit < 0) reasons += "Projected operating cost exceeds expected ticket and cargo revenues. Not recommended."
    else reasons += "Healthy profit margins projected under typical load factors."

    Right(RouteForecastResult(
      originAirportId         = originAirportId,
      destinationAirportId    = destinationAirportId,
      passengerDemandEstimate = paxDemandEst,
      cargoDemandEstimate     = cargoDemand,
      expectedRevenue         = expectedRevenue,
      expectedCost            = expectedCost,
      expectedProfit          = expectedProfit,
      confidenceLevel         = confidenceLevel,
      competitionLevel        = competitionLevel,
      recommendedAircraftModels = candidateAircraft.map(_.modelName),
      recommendedFrequency    = Some(primaryCandidate.frequency),
      reasons                 = reasons.toList,
      candidateAircraft       = candidateAircraft,
      competitorCount         = competitorCount,
      competitorTotalFrequency = competitorWeeklyFrequency,
      competitionSummary      = competitionSummaryText,
      confidenceExplanation   = confidenceExplanationText,
      recommendation          = recommendationText,
      recommendationSeverity  = recommendationSeverityText,
      cargoShareEstimate      = cargoShareEstimate(expectedCargoRevenue, expectedRevenue),
      aircraftRecommendationReason = aircraftReason
    ))
  }

  private def demandByClass(from: Airport, to: Airport, relationship: Int): LinkClassValues = {
    val distance = Computation.calculateDistance(from, to)
    val affinity = Computation.calculateAffinityValue(from.zone, to.zone, relationship)
    val d = DemandGenerator.computeBaseDemandBetweenAirports(from, to, affinity, distance)
    d.travelerDemand + d.businessDemand + d.touristDemand
  }
}
