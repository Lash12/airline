package com.patson

import com.patson.model.Airport
import com.patson.model.airplane.{Model, Manufacturer}
import com.patson.data.SoloConfig
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class CargoMarketVisibilityServiceSpec extends AnyWordSpecLike with Matchers {

  private def mk(iata : String, income : Int, population : Int) : Airport =
    Airport(iata, s"K$iata", iata, 10.0, 10.0 + math.abs(iata.hashCode) % 30, "ZZ", iata, "North America", 5, income, population, population / 2, 0, 2000, math.abs(iata.hashCode) % 100000)

  private def mkModel(name: String, capacity: Int, range: Int, runwayRequirement: Int, id: Int): Model =
    Model(name, name, capacity, 5, 1.0, 1.0, 800, range, 10_000_000, 1000, 10, Manufacturer("Boeing", "US"), runwayRequirement, id = id)

  "CargoMarketVisibilityService" should {

    "return empty opportunities if cargo feature is disabled" in {
      if (!SoloConfig.cargoEnabled) {
        CargoMarketVisibilityService.getCargoOpportunities(1) shouldBe Nil
      }
    }

    "suggest cargo models based on runway, range, and capacity" in {
      val modelSmall = mkModel("SmallProp", 50, 1500, 1000, 1)
      val modelMedium = mkModel("MediumJet", 150, 4000, 1800, 2)
      val modelLarge = mkModel("LargeJet", 350, 9000, 2500, 3)
      val allModels = List(modelSmall, modelMedium, modelLarge)

      // Short distance (1000km), low runway requirement (1500m), low demand (200 units)
      // SmallProp should fit perfectly and have the best size/efficiency score
      val models1 = CargoMarketVisibilityService.suggestCargoModels(
        distance = 1000,
        originRunway = 1500,
        destinationRunway = 1500,
        unservedDemand = 200,
        allModels = allModels
      )
      models1 should contain (1)

      // Medium distance (3000km), medium runway (2000m), medium demand (800 units)
      // SmallProp range is too short (1500), LargeJet is too large/inefficient. MediumJet is the best match.
      val models2 = CargoMarketVisibilityService.suggestCargoModels(
        distance = 3000,
        originRunway = 2000,
        destinationRunway = 2000,
        unservedDemand = 800,
        allModels = allModels
      )
      models2 should contain (2)
      models2 should not contain (1)
    }

    "generate appropriate opportunity notes" in {
      val origin = mk("HKG", 40000, 5_000_000)
      val destination = mk("MEM", 35000, 4_000_000)
      
      val noteHub = CargoMarketVisibilityService.generateOpportunityNotes(
        origin = origin,
        destination = destination,
        distance = 6000,
        demand = 2000,
        served = 0,
        unserved = 2000,
        yieldValue = 1.2
      )
      
      noteHub should include("Connects two major global cargo hubs")
      noteHub should include("Long-haul trade lane")
      noteHub should include("Untapped market")

      val destinationSmall = mk("AAA", 15000, 100_000)
      val noteSmall = CargoMarketVisibilityService.generateOpportunityNotes(
        origin = destinationSmall,
        destination = destinationSmall,
        distance = 200,
        demand = 100,
        served = 50,
        unserved = 50,
        yieldValue = 0.04
      )
      
      noteSmall should include("Short hop")
      noteSmall should include("Existing flights do not fully satisfy demand")
    }
  }
}
