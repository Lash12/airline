

package com.patson

import java.util.concurrent.TimeUnit
import org.apache.pekko.actor.Props
import org.apache.pekko.actor.Actor
import com.patson.data._
import com.patson.stream.{CycleCompleted, CycleStart, SimulationEventStream}
import com.patson.util.{AirlineCache, AirplaneOwnershipCache, AirplaneOwnershipInfo, AirportCache}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

object MainSimulation extends App {
  val CYCLE_DURATION : Int = 60 * 29
  var currentWeek: Int = 0

  private def timedStep[T](label: String)(block: => T): T = {
    val startTime = System.nanoTime()
    val result = block
    if (RuntimeSettings.cycleTimingEnabled) {
      val elapsedSeconds = (System.nanoTime() - startTime) / 1000000000d
      println(f"[timing] $label took $elapsedSeconds%.2fs")
    }
    result
  }

//  implicit val actorSystem = ActorSystem("rabbit-akka-stream")

//  import actorSystem.dispatcher

//  implicit val materializer = FlowMaterializer()
  
  mainFlow
  
  def mainFlow() = {
    val actor = actorSystem.actorOf(Props[MainSimulationActor])
    actorSystem.scheduler.schedule(Duration.Zero, Duration(CYCLE_DURATION, TimeUnit.SECONDS), actor, Start)
    Await.result(actorSystem.whenTerminated, Duration.Inf)
  }


  def invalidateCaches() = {
    AirlineCache.invalidateAll()
    AirportCache.invalidateAll()
    AirplaneOwnershipCache.invalidateAll()
  }

  def startCycle(cycle : Int) = {
      val cycleStartTime = System.currentTimeMillis()
      println("cycle " + cycle + " starting!")
      if (cycle == 1) { //initialize it
        timedStep("OilSimulation.bootstrap") { OilSimulation.simulate(1) }
        timedStep("LoanInterestRateSimulation.bootstrap") { LoanInterestRateSimulation.simulate(1) }
      }

      timedStep("SimulationEventStream.publishCycleStart") { SimulationEventStream.publish(CycleStart(cycle, cycleStartTime), None) }
      timedStep("Cache invalidation") { invalidateCaches() }

      timedStep("UserSimulation") { UserSimulation.simulate(cycle) }
      println("Event simulation")
      timedStep("EventSimulation") { EventSimulation.simulate(cycle) }

      val (flightLinkResult, loungeResult, linkRidershipDetails, airlineStats) = timedStep("LinkSimulation") { LinkSimulation.linkSimulation(cycle) }
      println("Airport simulation")
      val airportChampionInfo = timedStep("AirportSimulation") { AirportSimulation.airportSimulation(cycle, flightLinkResult, linkRidershipDetails) }

      println("Airport assets simulation")
      timedStep("AirportAssetSimulation") { AirportAssetSimulation.simulate(cycle, linkRidershipDetails) }

      println("Airplane simulation")
      val airplanes = timedStep("AirplaneSimulation") { AirplaneSimulation.airplaneSimulation(cycle) }
      println("Airline simulation")
      timedStep("AirlineSimulation") { AirlineSimulation.airlineSimulation(cycle, flightLinkResult, loungeResult, airplanes, airlineStats) }
      println("Country simulation")
      val countryChampionInfo = timedStep("CountrySimulation") { CountrySimulation.simulate(cycle) }

      println("Alliance simulation")
      timedStep("AllianceSimulation") { AllianceSimulation.simulate(cycle, flightLinkResult, loungeResult, airportChampionInfo, countryChampionInfo) }
      println("Airplane model simulation")
      timedStep("AirplaneModelSimulation") { AirplaneModelSimulation.simulate(cycle) }

      //purge log
      println("Purging logs")
      timedStep("Log purge") { LogSource.deleteLogsBeforeCycle(cycle - com.patson.model.Log.RETENTION_CYCLE) }

      //purge history
      println("Purging link history")
      timedStep("Link history purge") { ChangeHistorySource.deleteLinkChangeByCriteria(List(("cycle", "<", cycle - 500))) }

      println("Purging Alliance Stats – remove if running AllianceSimulation")
      timedStep("Alliance stats purge") { AllianceSource.deleteAllianceStatsBeforeCutoff(cycle - 108) }

      //purge airline modifier
      println("Purging airline modifier")
      timedStep("Airline modifier purge") { AirlineSource.deleteAirlineModifierByExpiry(cycle) }

      val cycleEnd = System.currentTimeMillis()
      
      println(">>>>> cycle " + cycle + " spent " + (cycleEnd - cycleStartTime) / 1000 + " secs")
      cycleEnd
  }

  /**
    * Things to be done after cycle ticked. These should be relatively short operations (data reconciliation etc)
    * @param currentCycle
    */
  def postCycle(currentCycle : Int) = {
    println("Oil simulation")
    timedStep("OilSimulation.postCycle") { OilSimulation.simulate(currentCycle) } //simulate at the beginning of a new cycle
    println("Loan simulation")
    timedStep("LoanInterestRateSimulation.postCycle") { LoanInterestRateSimulation.simulate(currentCycle) } //simulate at the beginning of a new cycle
    //refresh delegates
    println("Delegate simulation")
    timedStep("DelegateSimulation") { DelegateSimulation.simulate(currentCycle) }

    println("Post cycle link simulation")
    timedStep("LinkSimulation.postCycle") { LinkSimulation.simulatePostCycle(currentCycle) }

    println(s"Post cycle done $currentCycle")
  }


  /**
    * The simulation can be seen like this:
    * On week(cycle) n. It starts the long simulation (pax simulation) at the "END of the week"
    * when it finishes computing the pax of the past week. It sets the current week to next week (which indicates a beginning of week n + 1)
    * It then runs some postCycle task (these tasks should be short and can be regarded as things to do at the Beginning of a week)
    *
    */
  class MainSimulationActor extends Actor {
    currentWeek = CycleSource.loadCycle()
    def receive = {
      case Start =>
        status = SimulationStatus.IN_PROGRESS
        val endTime = startCycle(currentWeek)

        currentWeek += 1
        CycleSource.setCycle(currentWeek)
        status = SimulationStatus.WAITING_CYCLE_START
        postCycle(currentWeek) //post cycle do some quick updates, no long simulation

        //notify the websockets via EventStream
        println("Publish Cycle Complete message")
        SimulationEventStream.publish(CycleCompleted(currentWeek - 1, endTime), None)
    }
  }
   
  
  case class Start()

  var status : SimulationStatus.Value = SimulationStatus.WAITING_CYCLE_START
  object SimulationStatus extends Enumeration {
    type DelegateTaskType = Value
    val IN_PROGRESS, WAITING_CYCLE_START = Value
  }

  
}

