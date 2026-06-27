package com.patson

import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import com.patson.data.{AirlineSource, AirportSource, LinkSource, SoloConfig}
import com.patson.model._

class RouteForecastServiceSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll {

  var testAirlineId: Int = 0

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    val airline = Airline("Route Forecast Test Airline")
    AirlineSource.saveAirlines(List(airline))
    val saved = AirlineSource.loadAllAirlines().find(_.name == "Route Forecast Test Airline").get
    testAirlineId = saved.id
  }

  override protected def afterAll(): Unit = {
    if (testAirlineId > 0) {
      AirlineSource.deleteAirline(testAirlineId)
      LinkSource.deleteLinksByAirlineId(testAirlineId)
    }
    super.afterAll()
  }

  "RouteForecastService" should {

    "return FEATURE_DISABLED when the route forecast feature flag is off" in {
      SoloConfig.routeForecastEnabled = false

      val result = RouteForecastService.getForecast(testAirlineId, 1, 2)
      result shouldBe Left("FEATURE_DISABLED: Route forecast backend is disabled in solo configuration.")
    }

    "return Left with UNAVAILABLE_DATA if origin or destination airport does not exist" in {
      SoloConfig.routeForecastEnabled = true

      val result = RouteForecastService.getForecast(testAirlineId, 99999, 99998)
      result shouldBe Left("UNAVAILABLE_DATA: One or both airports not found.")
    }

    "return forecast with 0 passenger demand if distance is 0 or canHaveDemand is false" in {
      SoloConfig.routeForecastEnabled = true
      val jfk = AirportSource.loadAirportByIata("JFK", true).get

      val result = RouteForecastService.getForecast(testAirlineId, jfk.id, jfk.id)
      result.isRight shouldBe true
      val forecast = result.toOption.get
      forecast.passengerDemandEstimate shouldBe 0
      forecast.reasons.head should startWith("NO_DEMAND")
    }

    "return passenger-only route forecast when cargo is disabled" in {
      SoloConfig.routeForecastEnabled = true
      SoloConfig.cargoEnabled = false

      val jfk = AirportSource.loadAirportByIata("JFK", true).get
      val lax = AirportSource.loadAirportByIata("LAX", true).get

      val result = RouteForecastService.getForecast(testAirlineId, jfk.id, lax.id)
      result.isRight shouldBe true
      val forecast = result.toOption.get
      forecast.passengerDemandEstimate should be > 0
      forecast.cargoDemandEstimate shouldBe 0
      forecast.expectedRevenue should be > 0L
      forecast.expectedCost should be > 0L
      forecast.recommendedAircraftModels should not be empty
      forecast.recommendedFrequency should not be empty
      forecast.reasons should contain ("Cargo simulation is disabled.")
    }

    "return cargo-supported route forecast when cargo is enabled" in {
      SoloConfig.routeForecastEnabled = true
      SoloConfig.cargoEnabled = true

      val jfk = AirportSource.loadAirportByIata("JFK", true).get
      val lax = AirportSource.loadAirportByIata("LAX", true).get

      val result = RouteForecastService.getForecast(testAirlineId, jfk.id, lax.id)
      result.isRight shouldBe true
      val forecast = result.toOption.get
      forecast.passengerDemandEstimate should be > 0
      forecast.cargoDemandEstimate should be > 0
      forecast.expectedRevenue should be > 0L
      forecast.reasons.exists(r => r.contains("cargo demand") && r.contains(" belly cargo revenue")) shouldBe true
    }

    "return candidateAircraft with at least one entry for a normal JFK-LAX forecast" in {
      SoloConfig.routeForecastEnabled = true
      SoloConfig.cargoEnabled = true

      val jfk = AirportSource.loadAirportByIata("JFK", true).get
      val lax = AirportSource.loadAirportByIata("LAX", true).get

      val result = RouteForecastService.getForecast(testAirlineId, jfk.id, lax.id)
      result.isRight shouldBe true
      val forecast = result.toOption.get
      forecast.candidateAircraft should not be empty
      val primary = forecast.candidateAircraft.head
      primary.modelName should not be empty
      primary.frequency should be > 0
      primary.weeklyPaxCapacity should be > 0
      primary.estimatedRevenue should be > 0L
      primary.estimatedCost should be > 0L
      primary.youOwnThis shouldBe false
    }

    "return competition level HIGH/MEDIUM when competitor flights exist on the route" in {
      SoloConfig.routeForecastEnabled = true
      SoloConfig.cargoEnabled = false

      val jfk = AirportSource.loadAirportByIata("JFK", true).get
      val lax = AirportSource.loadAirportByIata("LAX", true).get

      // Create a competitor airline and direct link
      val competitor = Airline("Competitor Airline for Forecast")
      AirlineSource.saveAirlines(List(competitor))
      val savedCompetitor = AirlineSource.loadAllAirlines().find(_.name == "Competitor Airline for Forecast").get

      val distance = Computation.calculateDistance(jfk, lax).toInt
      val link = Link(
        from = jfk,
        to = lax,
        airline = savedCompetitor,
        price = LinkClassValues(300, 600, 1000),
        distance = distance,
        capacity = LinkClassValues(10000, 2000, 500),
        rawQuality = 50,
        duration = 300,
        frequency = 20
      )
      LinkSource.saveLink(link)

      try {
        val result = RouteForecastService.getForecast(testAirlineId, jfk.id, lax.id)
        result.isRight shouldBe true
        val forecast = result.toOption.get
        forecast.competitionLevel should (equal("HIGH") or equal("MEDIUM"))
        forecast.reasons.exists(r => r.contains("competition") || r.contains("existing carriers")) shouldBe true
      } finally {
        LinkSource.deleteLinksByAirlineId(savedCompetitor.id)
        AirlineSource.deleteAirline(savedCompetitor.id)
      }
    }

    "return UNSUITABLE_AIRCRAFT forecast if no plane fits range or runway restrictions" in {
      SoloConfig.routeForecastEnabled = true

      // Save dummy airports with extremely short runway of 100 meters
      val originDummy = Airport("DUM1", "DM1", "Dummy Airport 1", 40.0, -74.0, countryCode = "US", "New York", "US-NY", 1, 10000, basePopulation = 500, runwayLength = 100)
      val destDummy = Airport("DUM2", "DM2", "Dummy Airport 2", 34.0, -118.0, countryCode = "US", "Los Angeles", "US-CA", 1, 10000, basePopulation = 500, runwayLength = 100)
      AirportSource.saveAirports(List(originDummy, destDummy))

      try {
        val result = RouteForecastService.getForecast(testAirlineId, originDummy.id, destDummy.id)
        result.isRight shouldBe true
        val forecast = result.toOption.get
        forecast.recommendedAircraftModels shouldBe empty
        forecast.recommendedFrequency shouldBe empty
        forecast.reasons.head should startWith("UNSUITABLE_AIRCRAFT")
      } finally {
        AirportSource.deleteAirports(List(originDummy.id, destDummy.id))
      }
    }
  }
}
