package com.patson

import com.patson.data.{CountrySource, LinkSource, SoloConfig}
import com.patson.model.{Airport, Computation}
import com.patson.model.airplane.{Airplane, Model}
import com.patson.util.{AirportCache, AirplaneModelCache}

object CargoMarketVisibilityService {

  case class CargoOpportunity(
    originAirportId: Int,
    destinationAirportId: Int,
    destinationCode: String,
    destinationName: String,
    weeklyCargoDemand: Int,
    weeklyCargoServed: Int,
    weeklyCargoUnserved: Int,
    estimatedYield: Double,
    recommendedAircraftModelIds: List[Int],
    notes: String,
    estimatedYieldPerUnitKm: Double = SoloConfig.cargoRevenuePerUnitKm,
    estimatedProfit: Long = 0L,
    profitBand: String = "Unknown",
    bestAircraft: String = "",
    bestFreighterCandidate: Option[String] = None,
    reasonText: String = "",
    riskText: String = "",
    score: Double = 0.0
  )

  case class CargoMarketLane(
    originAirportId: Int,
    originIata: String,
    originName: String,
    destinationAirportId: Int,
    destinationIata: String,
    destinationName: String,
    cargoDemand: Int,
    estimatedYield: Double,
    estimatedProfit: Long,
    recommendedAircraft: List[String],
    servedByPlayer: Boolean,
    reason: String
  )

  def getCargoOpportunities(originAirportId: Int): List[CargoOpportunity] = {
    if (!SoloConfig.cargoEnabled) {
      return Nil
    }

    AirportCache.getAirport(originAirportId) match {
      case None => Nil
      case Some(originAirport) =>
        val candidates = AirportCache.getAllAirports()
        val relationships = CountrySource.getCountryMutualRelationships(originAirport.countryCode)
        
        // Find outgoing links to determine served cargo
        val consumptions = LinkSource.loadLinkConsumptionsByCriteria(List(("from_airport", originAirportId)), 1)
        val servedMap = consumptions.groupBy(_.link.to.id).view.mapValues { list =>
          list.map(_.cargoCarried).sum
        }.toMap

        // Let's get the likely cargo destinations (top destinations by demand)
        // Let's take the top 50 destinations to provide a comprehensive market opportunity view
        val topDestinations = CargoDemandGenerator.topCargoDestinations(originAirport, candidates, relationships, 50)
        
        // Construct opportunity objects
        topDestinations.flatMap { case (toAirport, demand) =>
          val distance = Computation.calculateDistance(originAirport, toAirport)
          val served = servedMap.getOrElse(toAirport.id, 0)
          val unserved = Math.max(0, demand - served)
          val yieldVal = distance * SoloConfig.cargoRevenuePerUnitKm

          val recommendedModels = suggestCargoModels(distance, originAirport.runwayLength.toInt, toAirport.runwayLength.toInt, unserved)
          val notes = generateOpportunityNotes(originAirport, toAirport, distance, demand, served, unserved, yieldVal)
          val passengerDemand = passengerDemandBetween(originAirport, toAirport, distance)
          val bestBelly = bestBellyModel(distance.toInt, originAirport.runwayLength.toInt, toAirport.runwayLength.toInt)
          val bestFreighterName = recommendedModels.headOption.flatMap(id => AirplaneModelCache.allModels.get(id).map(_.name))
          val bestFreighterModel = recommendedModels.headOption.flatMap(AirplaneModelCache.allModels.get)
          val capturableUnits = bestFreighterModel.map(m => Math.min(unserved, m.freighterCargoCapacity * 7)).getOrElse(unserved)
          val estimatedProfit = estimateFreighterProfit(bestFreighterModel, capturableUnits, distance.toInt)
          val reason = opportunityReason(passengerDemand, unserved, bestFreighterName.nonEmpty, distance.toInt)
          val risk = opportunityRisk(unserved, distance.toInt, bestFreighterName.nonEmpty)
          val score = opportunityScore(estimatedProfit, unserved, distance.toInt, bestFreighterName.nonEmpty)

          Some(CargoOpportunity(
            originAirportId = originAirportId,
            destinationAirportId = toAirport.id,
            destinationCode = toAirport.iata,
            destinationName = toAirport.city,
            weeklyCargoDemand = demand,
            weeklyCargoServed = served,
            weeklyCargoUnserved = unserved,
            estimatedYield = yieldVal,
            recommendedAircraftModelIds = recommendedModels,
            notes = notes,
            estimatedYieldPerUnitKm = SoloConfig.cargoRevenuePerUnitKm,
            estimatedProfit = estimatedProfit,
            profitBand = profitBand(estimatedProfit),
            bestAircraft = bestBelly.filter(_ => passengerDemand >= 150).orElse(bestFreighterName).getOrElse("No suitable aircraft"),
            bestFreighterCandidate = bestFreighterName,
            reasonText = reason,
            riskText = risk,
            score = score
          ))
        }.sortBy(o => (-o.score, -o.weeklyCargoUnserved, -o.estimatedProfit))
    }
  }

  def getCargoMarketOverview(airlineId: Int, limit: Int = 20): List[CargoMarketLane] = {
    if (!SoloConfig.cargoEnabled) return Nil

    val airports = AirportCache.getAllAirports()
    val relationshipsByCountry = CountrySource.getCountryMutualRelationships()
    val servedByPlayer = (
      LinkSource.loadFlightLinksByAirlineId(airlineId).map(l => (l.from.id, l.to.id)) ++
        LinkSource.loadCargoLinksByCriteria(List(("airline", airlineId))).map(l => (l.from.id, l.to.id))
      ).toSet

    val origins = airports
      .filter(_.population > 0)
      .sortBy(a => -(a.population.toLong * Math.max(1, a.income)))
      .take(45)

    origins.flatMap { origin =>
      val relationships = relationshipsByCountry.collect { case ((from, to), rel) if from == origin.countryCode => to -> rel }.toMap
      CargoDemandGenerator.topCargoDestinations(origin, airports, relationships, 8).map { case (destination, demand) =>
        val distance = Computation.calculateDistance(origin, destination).toInt
        val recommendedIds = suggestCargoModels(distance, origin.runwayLength.toInt, destination.runwayLength.toInt, demand)
        val recommendedNames = recommendedIds.flatMap(id => AirplaneModelCache.allModels.get(id).map(_.name))
        val bestFreighterModel = recommendedIds.headOption.flatMap(AirplaneModelCache.allModels.get)
        val capacityCap = bestFreighterModel.map(_.freighterCargoCapacity * 7).getOrElse(demand)
        val units = Math.min(demand, capacityCap)
        val estimatedProfit = estimateFreighterProfit(bestFreighterModel, units, distance)
        val served = servedByPlayer.contains((origin.id, destination.id))
        CargoMarketLane(
          originAirportId = origin.id,
          originIata = origin.iata,
          originName = origin.city,
          destinationAirportId = destination.id,
          destinationIata = destination.iata,
          destinationName = destination.city,
          cargoDemand = demand,
          estimatedYield = SoloConfig.cargoRevenuePerUnitKm,
          estimatedProfit = estimatedProfit,
          recommendedAircraft = recommendedNames,
          servedByPlayer = served,
          reason = if (served) "You already serve this lane." else opportunityReason(passengerDemandBetween(origin, destination, distance), demand, recommendedNames.nonEmpty, distance)
        )
      }
    }.groupBy(l => (l.originAirportId, l.destinationAirportId))
      .map(_._2.maxBy(_.estimatedProfit))
      .toList
      .sortBy(l => (-l.estimatedProfit, -l.cargoDemand))
      .take(limit)
  }

  def suggestCargoModels(distance: Int, originRunway: Int, destinationRunway: Int, unservedDemand: Int): List[Int] = {
    suggestCargoModels(distance, originRunway, destinationRunway, unservedDemand, AirplaneModelCache.allModels.values.toList)
  }

  def suggestCargoModels(distance: Int, originRunway: Int, destinationRunway: Int, unservedDemand: Int, allModels: List[Model]): List[Int] = {
    val fittingModels = allModels.filter { model =>
      model.range >= distance &&
      originRunway >= model.runwayRequirement &&
      destinationRunway >= model.runwayRequirement &&
      model.speed >= 300 && // exclude airships/helicopters
      model.freighterCargoCapacity > 0
    }

    if (fittingModels.isEmpty) {
      Nil
    } else {
      val targetWeeklyCapacity = unservedDemand
      val modelsWithScore = fittingModels.map { model =>
        val weeklyFreighterCapacity = model.freighterCargoCapacity * 7
        val efficiency = model.cruiseBurn / Math.max(1.0, model.freighterCargoCapacity * model.speed)
        
        val sizeRatio = if (targetWeeklyCapacity > 0) weeklyFreighterCapacity.toDouble / targetWeeklyCapacity else 1.0
        val sizePenalty = if (sizeRatio < 0.5) {
          (0.5 - sizeRatio) * 2.0
        } else if (sizeRatio > 2.0) {
          (sizeRatio - 2.0) * 0.5
        } else {
          0.0
        }
        val score = efficiency * (1.0 + sizePenalty)
        (model, score)
      }

      modelsWithScore.sortBy(_._2).map(_._1.id).take(3)
    }
  }

  def generateOpportunityNotes(
    origin: Airport,
    destination: Airport,
    distance: Int,
    demand: Int,
    served: Int,
    unserved: Int,
    yieldValue: Double
  ): String = {
    val reasons = scala.collection.mutable.ListBuffer[String]()

    val isOriginHub = CargoDemandGenerator.cargoHubProfiles.contains(origin.iata)
    val isDestHub = CargoDemandGenerator.cargoHubProfiles.contains(destination.iata)

    if (isOriginHub && isDestHub) {
      reasons += "Connects two major global cargo hubs."
    } else if (isOriginHub) {
      reasons += s"${origin.iata} is a major global cargo hub."
    } else if (isDestHub) {
      reasons += s"${destination.iata} is a major global cargo hub."
    }

    if (distance < CargoDemandGenerator.TRUCKING_DISTANCE) {
      reasons += "Short hop; air freight competes with trucking."
    } else if (distance > 5000) {
      reasons += "Long-haul trade lane; higher yield potential."
    }

    if (served == 0 && demand > 0) {
      reasons += "Untapped market with zero cargo currently served."
    } else if (unserved > demand * 0.5) {
      reasons += s"High unserved demand (${(unserved.toDouble / demand * 100).toInt}% unserved)."
    } else if (unserved > 0) {
      reasons += "Existing flights do not fully satisfy demand."
    } else {
      reasons += "Market is fully served."
    }

    reasons.mkString(" ")
  }

  def opportunityScore(estimatedProfit: Long, unservedDemand: Int, distance: Int, hasAircraft: Boolean): Double = {
    val distancePenalty = if (distance < CargoDemandGenerator.TRUCKING_DISTANCE) 250.0
      else if (distance > 9000) 150.0
      else 0.0
    (estimatedProfit / 1000.0) + (unservedDemand * 2.0) + (if (hasAircraft) 500.0 else -300.0) - distancePenalty
  }

  def profitBand(estimatedProfit: Long): String =
    if (estimatedProfit >= 1_000_000) "High"
    else if (estimatedProfit >= 250_000) "Medium"
    else if (estimatedProfit > 0) "Low"
    else "None"

  private def estimateFreighterProfit(model: Option[Model], units: Int, distance: Int): Long = {
    model match {
      case Some(m) if units > 0 && distance > 0 && m.freighterCargoCapacity > 0 =>
        val frequency = Math.max(1, Math.ceil(units.toDouble / m.freighterCargoCapacity).toInt)
        val revenue = Math.round(units * distance * SoloConfig.cargoRevenuePerUnitKm * SoloConfig.cargoFreighterRevenueMultiplier)
        val fuelCost = LinkSimulation.calculateFuelCost(
          m,
          distance,
          soldSeats = units,
          capacity = Math.max(1, m.freighterCargoCapacity * frequency).toDouble,
          frequency = frequency
        )
        val fixedCost = m.baseMaintenanceCost + Airplane.standardDepreciationRate(m)
        Math.round(revenue - fuelCost - fixedCost)
      case _ => 0L
    }
  }

  private def passengerDemandBetween(origin: Airport, destination: Airport, distance: Int): Int = {
    if (distance <= 0 || !DemandGenerator.canHaveDemand(origin, destination, distance)) 0
    else {
      val relationship = CountrySource.getCountryMutualRelationship(origin.countryCode, destination.countryCode)
      val affinity = Computation.calculateAffinityValue(origin.zone, destination.zone, relationship)
      val demand = DemandGenerator.computeBaseDemandBetweenAirports(origin, destination, affinity, distance)
      demand.travelerDemand.total + demand.businessDemand.total + demand.touristDemand.total
    }
  }

  private def bestBellyModel(distance: Int, originRunway: Int, destinationRunway: Int): Option[String] = {
    AirplaneModelCache.allModels.values.filter { model =>
      model.range >= distance &&
        originRunway >= model.runwayRequirement &&
        destinationRunway >= model.runwayRequirement &&
        model.speed >= 300 &&
        model.bellyCargoCapacity > 0
    }.toList.sortBy(m => m.cruiseBurn.toDouble / Math.max(1, m.capacity * m.speed)).headOption.map(_.name)
  }

  private def opportunityReason(passengerDemand: Int, unservedDemand: Int, hasFreighter: Boolean, distance: Int): String = {
    if (passengerDemand >= 150) "Best as belly cargo on a passenger route."
    else if (hasFreighter && unservedDemand >= 300) "Potential freighter lane if freighter multiplier is enabled."
    else if (distance > 5000) "Long distance gives useful cargo revenue per unit."
    else "Useful cargo filler if you already plan service nearby."
  }

  private def opportunityRisk(unservedDemand: Int, distance: Int, hasFreighter: Boolean): String = {
    if (!hasFreighter) "No suitable freighter candidate; treat as belly cargo only."
    else if (unservedDemand < 150) "Demand is thin; avoid oversized freighter capacity."
    else if (distance > 9000) "Very long distance; use long-range equipment and watch utilization."
    else if (SoloConfig.cargoFreighterRevenueMultiplier <= 1.0) "Freighter economics may still be weak without the freighter-only multiplier."
    else "Moderate risk; confirm passenger demand or freighter utilization before opening."
  }
}
