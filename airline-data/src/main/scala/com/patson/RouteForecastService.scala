package com.patson

import com.patson.data.{AirlineSource, LinkSource, SoloConfig, CountrySource, CycleSource, AirportSource}
import com.patson.data.airplane.ModelSource
import com.patson.model._
import com.patson.model.airplane.{Airplane, AirplaneConfiguration, LinkAssignment, Model}
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

object RouteForecastService {

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
    recommendedAircraftModels: List[String],
    recommendedFrequency: Option[Int],
    reasons: List[String]
  )

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
        reasons = List("NO_DEMAND: Distance too short or no demand configuration possible between these airports.")
      ))
    }

    val relationship = CountrySource.getCountryMutualRelationship(fromAirport.countryCode, toAirport.countryCode)
    val affinity = Computation.calculateAffinityValue(fromAirport.zone, toAirport.zone, relationship)

    // Calculate passenger demand estimate
    val demand = DemandGenerator.computeBaseDemandBetweenAirports(fromAirport, toAirport, affinity, distance)
    val paxDemandEst = demand.travelerDemand.total + demand.businessDemand.total + demand.touristDemand.total

    // Calculate cargo demand estimate
    val cargoDemand = if (SoloConfig.cargoEnabled) {
      CargoDemandGenerator.computeCargoDemandBetweenAirports(fromAirport, toAirport, affinity, distance) +
        CargoDemandGenerator.computeCargoDemandBetweenAirports(toAirport, fromAirport, affinity, distance)
    } else {
      0
    }

    // Determine competition level
    val competitorLinks = LinkSource.loadFlightLinksByAirports(fromAirport.id, toAirport.id) ++
      LinkSource.loadFlightLinksByAirports(toAirport.id, fromAirport.id)
    val totalCompetitorCapacity = competitorLinks.map(_.capacity.total).sum
    val competitionLevel = if (totalCompetitorCapacity == 0) {
      "NONE"
    } else if (totalCompetitorCapacity < paxDemandEst * 0.2) {
      "LOW"
    } else if (totalCompetitorCapacity < paxDemandEst * 0.8) {
      "MEDIUM"
    } else {
      "HIGH"
    }

    // Suggest recommended aircraft models
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
        reasons = List("UNSUITABLE_AIRCRAFT: Distance exceeds max range or runways too short for all aircraft models in database.")
      ))
    }

    // Choose the best suggested model
    val suggestedModel = ConsultantAdvisor.suggestModel(
      distance,
      fromAirport.runwayLength.toInt,
      toAirport.runwayLength.toInt,
      paxDemandEst * 2, // both directions base demand estimate
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
        reasons = List("UNSUITABLE_AIRCRAFT: No suitable aircraft model found that fits range/runway constraints.")
      ))
    }

    val model = suggestedModel.get
    val flightMinutesPerFreq = Computation.calculateFlightMinutesRequired(model, distance)
    val config = AirplaneConfiguration.default(airline, model)
    val seatsPerFlight = config.economyVal + config.businessVal + config.firstVal

    val targetSeats = (paxDemandEst * 1.5).toInt
    val freqForDemand = Math.max(1, Math.ceil(targetSeats.toDouble / seatsPerFlight).toInt)
    val freqByMinutes = Airplane.MAX_FLIGHT_MINUTES / flightMinutesPerFreq
    val frequency = Math.min(Math.min(freqForDemand, freqByMinutes), Computation.calculateMaxFrequency(model, distance))

    if (frequency <= 0) {
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
        recommendedAircraftModels = List(model.name),
        recommendedFrequency = None,
        reasons = List("UNSUITABLE_AIRCRAFT: Required flight minutes per frequency exceed available airplane weekly limit.")
      ))
    }

    // Build the expected Link object to run simulator calculations
    val priceMod = if (fromAirport.popMiddleIncome < 100_000 || toAirport.popMiddleIncome < 100_000) 1.2 else 1.0
    val cat = Computation.getFlightCategory(fromAirport, toAirport)
    val econ = (priceMod * Pricing.computeStandardPrice(distance, cat, ECONOMY, PassengerType.TRAVELER, fromAirport.baseIncome)).toInt
    val biz = (priceMod * Pricing.computeStandardPrice(distance, cat, BUSINESS, PassengerType.BUSINESS, fromAirport.baseIncome)).toInt
    val first = (priceMod * Pricing.computeStandardPrice(distance, cat, FIRST, PassengerType.BUSINESS, fromAirport.baseIncome)).toInt
    val duration = Computation.calculateDuration(model, distance)

    val airplane = Airplane(
      model = model,
      owner = airline,
      constructedCycle = currentCycle,
      purchasedCycle = currentCycle,
      condition = Airplane.MAX_CONDITION,
      purchasePrice = model.price,
      isSold = false,
      configuration = config,
      home = fromAirport,
      isReady = true
    )

    val assignment = LinkAssignment(frequency, frequency * flightMinutesPerFreq)
    val capacity = LinkClassValues(config.economyVal, config.businessVal, config.firstVal) * frequency
    val link = Link(
      from = fromAirport,
      to = toAirport,
      airline = airline,
      price = LinkClassValues(econ, biz, first),
      distance = distance,
      capacity = capacity,
      rawQuality = 40,
      duration = duration,
      frequency = frequency
    )
    link.setAssignedAirplanes(Map(airplane -> assignment))

    // Estimate pax captured (taking into account competition)
    // Reduce capture ratio if competition is high
    val baseCapture = SoloConfig.consultantCaptureRatio
    val adjustedCapture = competitionLevel match {
      case "NONE" => baseCapture
      case "LOW" => baseCapture * 0.8
      case "MEDIUM" => baseCapture * 0.5
      case "HIGH" => baseCapture * 0.25
    }

    // Let's get both ways passenger demand
    val bothWaysDemand = demandByClass(fromAirport, toAirport, relationship) + demandByClass(toAirport, fromAirport, relationship)

    val estPax = LinkClassValues(
      Math.min(capacity.economyVal, (bothWaysDemand.economyVal * adjustedCapture).toInt),
      Math.min(capacity.businessVal, (bothWaysDemand.businessVal * adjustedCapture).toInt),
      Math.min(capacity.firstVal, (bothWaysDemand.firstVal * adjustedCapture).toInt)
    )
    link.addSoldSeats(estPax)

    // Run simulator details
    val consumptionDetails = LinkSimulation.computeFlightLinkConsumptionDetail(link, currentCycle)
    link.soldSeats = LinkClassValues.getInstance() // reset link state

    var expectedRevenue = consumptionDetails.revenue.toLong
    var expectedCost = (consumptionDetails.revenue - consumptionDetails.profit).toLong

    // Add cargo if enabled
    val expectedCargoRevenue = if (SoloConfig.cargoEnabled && cargoDemand > 0) {
      val cargoCapacity = model.bellyCargoCapacity * frequency
      val cargoCarried = Math.min(cargoCapacity, (cargoDemand * SoloConfig.cargoCaptureRatio).toInt)
      Math.round(cargoCarried * distance * SoloConfig.cargoRevenuePerUnitKm).toLong
    } else {
      0L
    }

    expectedRevenue += expectedCargoRevenue
    val expectedProfit = expectedRevenue - expectedCost

    // Determine confidence level
    // High competition -> lower confidence
    // Low demand -> lower confidence
    // Aircraft constraints -> lower confidence
    val confidenceLevel = if (competitionLevel == "HIGH" || paxDemandEst < 100 || expectedProfit < 0) {
      "LOW"
    } else if (competitionLevel == "MEDIUM" || model.range * 0.9 <= distance || fromAirport.runwayLength < model.runwayRequirement + 200) {
      "MEDIUM"
    } else {
      "HIGH"
    }

    // Build human-readable reason messages
    val reasons = ListBuffer[String]()

    if (paxDemandEst > 500) {
      reasons += "Strong passenger demand on this route."
    } else if (paxDemandEst < 100) {
      reasons += "Weak passenger demand on this route; high risk of empty flights."
    } else {
      reasons += "Moderate passenger demand. Plan schedule and capacity carefully."
    }

    if (SoloConfig.cargoEnabled) {
      if (cargoDemand > 200) {
        reasons += s"Strong cargo demand ($cargoDemand units) can support belly cargo revenue."
      } else if (cargoDemand > 0) {
        reasons += s"Light cargo demand ($cargoDemand units) available."
      } else {
        reasons += "No cargo demand exists between these airports."
      }
    } else {
      reasons += "Cargo simulation is disabled."
    }

    competitionLevel match {
      case "HIGH" => reasons += "High competition. Multiple airlines already fly this route with large capacity."
      case "MEDIUM" => reasons += "Moderate competition from existing carriers."
      case "LOW" => reasons += "Low competition. Market is mostly open."
      case "NONE" => reasons += "No direct competition. A perfect opportunity for monopoly."
    }

    if (distance > model.range * 0.9) {
      reasons += s"Warning: Recommended model ${model.name} operates close to its maximum range limit of ${model.range} km."
    } else {
      reasons += s"Aircraft suggestion: ${model.name} fits this route's distance and runway limits."
    }

    if (fromAirport.runwayLength < model.runwayRequirement + 200 || toAirport.runwayLength < model.runwayRequirement + 200) {
      reasons += "Warning: Runway length is close to aircraft minimum requirement."
    }

    if (expectedProfit < 0) {
      reasons += "Projected operating cost exceeds expected ticket and cargo revenues. Not recommended."
    } else {
      reasons += "Healthy profit margins projected under typical load factors."
    }

    Right(RouteForecastResult(
      originAirportId = originAirportId,
      destinationAirportId = destinationAirportId,
      passengerDemandEstimate = paxDemandEst,
      cargoDemandEstimate = cargoDemand,
      expectedRevenue = expectedRevenue,
      expectedCost = expectedCost,
      expectedProfit = expectedProfit,
      confidenceLevel = confidenceLevel,
      competitionLevel = competitionLevel,
      recommendedAircraftModels = List(model.name),
      recommendedFrequency = Some(frequency),
      reasons = reasons.toList
    ))
  }

  private def demandByClass(from : Airport, to : Airport, relationship : Int) : LinkClassValues = {
    val distance = Computation.calculateDistance(from, to)
    val affinity = Computation.calculateAffinityValue(from.zone, to.zone, relationship)
    val d = DemandGenerator.computeBaseDemandBetweenAirports(from, to, affinity, distance)
    d.travelerDemand + d.businessDemand + d.touristDemand
  }
}
