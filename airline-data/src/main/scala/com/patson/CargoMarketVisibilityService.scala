package com.patson

import com.patson.data.{CountrySource, LinkSource, SoloConfig}
import com.patson.model.{Airport, Computation}
import com.patson.model.airplane.Model
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
    notes: String
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
            notes = notes
          ))
        }
    }
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
}
