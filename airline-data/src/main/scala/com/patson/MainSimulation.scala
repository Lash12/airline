package com.patson

import java.util.concurrent.TimeUnit
import org.apache.pekko.actor.Props
import org.apache.pekko.actor.Actor
import com.patson.data._
import com.patson.stream.{CycleCompleted, CycleStart, SimulationEventStream}
import com.patson.model.CountryAirlineTitle
import com.patson.util.{AirlineCache, AirplaneOwnershipCache, AirportCache, AirportStatisticsCache}

import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

object MainSimulation extends App {
  SchemaPatchRunner.run()

  val CYCLE_DURATION : Int = 60 * 29
  val MIN_CYCLE_MINUTES : Int = 5 //roughly cycle compute time + DB rest buffer
  val MAX_CYCLE_MINUTES : Int = 24 * 60
  val SCHEDULE_BUFFER_SECS : Int = 30
  val SCHEDULE_OVERHEAD_FACTOR : Double = 1.1
  var currentWeek: Int = 0

  //pause-when-idle: skip cycles while no player has been active (heartbeat written by the web app)
  val pauseWhenIdle : Boolean = Constants.configFactory.hasPath("simulation.pauseWhenIdle") && Constants.configFactory.getBoolean("simulation.pauseWhenIdle")
  val idleGraceMinutes : Long = if (Constants.configFactory.hasPath("simulation.idleGraceMinutes")) Constants.configFactory.getLong("simulation.idleGraceMinutes") else 60
  val idleRecheckMinutes : Long = if (Constants.configFactory.hasPath("simulation.idleRecheckMinutes")) Constants.configFactory.getLong("simulation.idleRecheckMinutes") else 5

  //player-adjustable pacing (written by the web app into the sim_control table)
  def configuredCycleIntervalMs() : Long = {
    try {
      SimControlSource.loadCycleMinutes() match {
        case Some(minutes) => Math.max(MIN_CYCLE_MINUTES, Math.min(MAX_CYCLE_MINUTES, minutes)) * 60000L
        case None => CYCLE_DURATION * 1000L
      }
    } catch {
      case e : Exception =>
        println(s"Failed to read sim control (${e.getMessage}), using default cycle duration")
        CYCLE_DURATION * 1000L
    }
  }

  def consumeFastForward() : Boolean = {
    try {
      SimControlSource.consumeFastForward()
    } catch {
      case e : Exception =>
        println(s"Failed to read fast-forward request (${e.getMessage})")
        false
    }
  }

  def fastForwardPending() : Boolean = {
    try {
      SimControlSource.loadFastForward() > 0
    } catch {
      case _ : Exception => false
    }
  }

  def isIdle() : Boolean = {
    try {
      HeartbeatSource.lastActiveMillis() match {
        case Some(lastActive) => System.currentTimeMillis() - lastActive > idleGraceMinutes * 60000L
        case None => true //no player has ever been active on this install
      }
    } catch {
      case e : Exception =>
        println(s"Failed to read activity heartbeat (${e.getMessage}), running the cycle anyway")
        false
    }
  }

  mainFlow

  def mainFlow() = {
    val actor = actorSystem.actorOf(Props[MainSimulationActor])
    Await.result(actorSystem.whenTerminated, Duration.Inf)
  }

  def initializeCaches() = {
    println("Initializing caches...")
    val startTime = System.currentTimeMillis()
    AirportCache.getAllAirports(true)
    val endTime = System.currentTimeMillis()
    println(s"Cache initialization completed in ${endTime - startTime}ms")
  }

  def invalidateCaches() = {
    AirlineCache.invalidateAll()
    AirportCache.invalidateAll()
    AirportStatisticsCache.invalidateAll()
    AirplaneOwnershipCache.invalidateAll()
    CountryAirlineTitle.invalidateAll()
  }

  def startCycle(cycle : Int) = {
    val cycleStartTime = System.currentTimeMillis()
    println("cycle " + cycle + " starting!")

    val phaseTimings = ListBuffer[(String, Long)]()
    def timed[T](phaseName : String)(block : => T) : T = {
      val phaseStart = System.currentTimeMillis()
      val result = block
      phaseTimings += ((phaseName, System.currentTimeMillis() - phaseStart))
      result
    }

    if (cycle == 1) { //initialize it
      OilSimulation.simulate(1)
      LoanInterestRateSimulation.simulate(1)
    }

    SimulationEventStream.publish(CycleStart(cycle, cycleStartTime), None)
    timed("caches") {
      invalidateCaches()
      initializeCaches()
    }

    timed("user") {
      UserSimulation.simulate(cycle)
    }
    println("Event simulation")
    timed("event") {
      EventSimulation.simulate(cycle)
    }
    println("Event simulation done")

    println("Link simulation starting")
    val (flightLinkResult, loungeResult, linkRidershipDetails, paxStatsByAirlineId) = timed("link") {
      LinkSimulation.linkSimulation(cycle)
    }
    println("Link simulation done")

    println("Airport simulation")
    val airportChampionInfo = timed("airport") {
      AirportSimulation.airportSimulation(cycle, linkRidershipDetails)
    }
    println("Airport simulation done")

    println("Airport asset simulation")
    timed("airportAsset") {
      AirportAssetSimulation.simulate(cycle)
    }
    println("Airport asset simulation done")

    println("Alliance simulation")
    timed("alliance") {
      AllianceSimulation.simulate(flightLinkResult, loungeResult, paxStatsByAirlineId, airportChampionInfo, cycle)
    }
    println("Alliance simulation done")

    println("Airplane simulation")
    val airplanes = timed("airplane") {
      AirplaneSimulation.airplaneSimulation(cycle)
    }
    println("Airplane simulation done")

    println("Airline simulation")
    timed("airline") {
      AirlineSimulation.airlineSimulation(cycle, flightLinkResult, loungeResult, airplanes, paxStatsByAirlineId)
    }
    println("Airline simulation done")

    println("Airplane model simulation")
    timed("airplaneModel") {
      AirplaneModelSimulation.simulate(cycle)
    }
    println("Airplane model simulation done")

    // Living-world AI (single-player, gated by solo.ai.enabled). Runs after the
    // economic sims so this cycle's link profit data is fresh.
    println("Computer airline simulation")
    timed("computerAirline") {
      ComputerAirlineSimulation.simulate(cycle)
    }
    println("Computer airline simulation done")

    timed("purge") {
      //purge history
      println("Purging link history")
      ChangeHistorySource.deleteLinkChangeByCriteria(List(("cycle", "<", cycle - 400)))

      //purge airline modifier
      println("Purging airline modifier")
      AirlineSource.deleteAirlineModifierByExpiry(cycle)
    }

    val cycleEnd = System.currentTimeMillis()

    println(">>>>> cycle " + cycle + " spent " + (cycleEnd - cycleStartTime) / 1000 + " secs")
    println(">>>>> cycle " + cycle + " phase timings: " + phaseTimings.map { case (name, ms) => s"$name=${ms}ms" }.mkString(", "))
    cycleEnd
  }

  /**
    * Things to be done after cycle ticked. These should be relatively short operations (data reconciliation etc)
    * @param currentCycle
    */
  def postCycle(currentCycle : Int) = {
    println("Oil simulation")
    OilSimulation.simulate(currentCycle)
    println("Loan simulation")
    LoanInterestRateSimulation.simulate(currentCycle)
    println("Add action points")
    ManagerSimulation.simulate(currentCycle)
    println("Post cycle link simulation")
    LinkSimulation.simulatePostCycle(currentCycle)

    println(s"Post cycle done $currentCycle")
  }

  // Actor Messages
  case object ExecuteProcessing
  case object BroadcastAndAdvance
  case object ScheduleNext
  case object CheckFastForward

  /**
    * The simulation can be seen like this:
    * On week(cycle) n. It starts the long simulation (pax simulation) at the "END of the week"
    * when it finishes computing the pax of the past week. It sets the current week to next week (which indicates a beginning of week n + 1)
    * It then runs some postCycle task (these tasks should be short and can be regarded as things to do at the Beginning of a week)
    *
    */
  class MainSimulationActor extends Actor {
    val DB_REST_BUFFER_MS = SCHEDULE_BUFFER_SECS * 1000L

    var currentWeek = CycleSource.loadCycle()
    var lastExecutionMs: Long = 0L
    var targetDeadline: Long = 0L // In-memory dynamic deadline
    var scheduledWakeUp: Option[org.apache.pekko.actor.Cancellable] = None

    private def scheduleExecution(delayMs : Long) : Unit = {
      scheduledWakeUp.foreach(_.cancel())
      scheduledWakeUp = Some(context.system.scheduler.scheduleOnce(Duration(delayMs, TimeUnit.MILLISECONDS), self, ExecuteProcessing))
    }

    override def preStart(): Unit = {
      // First run executes immediately to update users ASAP
      scheduleExecution(0L)
      // While waiting for the next deadline, notice player fast-forward requests promptly
      context.system.scheduler.scheduleWithFixedDelay(Duration(30, TimeUnit.SECONDS), Duration(30, TimeUnit.SECONDS), self, CheckFastForward)
    }

    def receive = {
      case ScheduleNext =>
        status = SimulationStatus.WAITING_CYCLE_START
        if (consumeFastForward()) {
          println("Fast-forward requested: starting next cycle immediately")
          targetDeadline = System.currentTimeMillis() //broadcast right after compute instead of waiting for the deadline
          scheduleExecution(DB_REST_BUFFER_MS)
        } else {
          val estimatedExecution = (lastExecutionMs * SCHEDULE_OVERHEAD_FACTOR).toLong
          val leadTime = estimatedExecution + DB_REST_BUFFER_MS

          val wakeUpTime = targetDeadline - leadTime
          val delayUntilWakeUp = Math.max(0L, wakeUpTime - System.currentTimeMillis())

          println(s"Next cycle will wake up in ${delayUntilWakeUp / 1000}s (estimated exec: ${estimatedExecution / 1000}s)")
          scheduleExecution(delayUntilWakeUp)
        }

      case CheckFastForward =>
        //a player asked to fast-forward while we are waiting for the next deadline: run now instead
        if (status == SimulationStatus.WAITING_CYCLE_START && consumeFastForward()) {
          println("Fast-forward requested: cancelling scheduled wait and starting cycle now")
          targetDeadline = System.currentTimeMillis() //broadcast right after compute instead of waiting for the deadline
          scheduleExecution(0L)
        }

      case ExecuteProcessing if pauseWhenIdle && isIdle() && !fastForwardPending() =>
        status = SimulationStatus.WAITING_CYCLE_START
        println(s"Simulation paused: no player activity within the last $idleGraceMinutes min. Rechecking in $idleRecheckMinutes min")
        scheduleExecution(idleRecheckMinutes * 60000L)

      case ExecuteProcessing =>
        status = SimulationStatus.IN_PROGRESS
        val startMs = System.currentTimeMillis()

        try {
          startCycle(currentWeek)
          postCycle(currentWeek + 1)

          lastExecutionMs = System.currentTimeMillis() - startMs

          // Determine DB rest. If first run (targetDeadline is 0), just take the minimum buffer.
          // Otherwise, sync to the target deadline, enforcing the minimum buffer.
          val delayUntilBroadcast = if (targetDeadline == 0L) {
            DB_REST_BUFFER_MS
          } else {
            val timeToDeadline = targetDeadline - System.currentTimeMillis()
            Math.max(timeToDeadline, DB_REST_BUFFER_MS)
          }

          context.system.scheduler.scheduleOnce(Duration(delayUntilBroadcast, TimeUnit.MILLISECONDS), self, BroadcastAndAdvance)

        } catch {
          case e : Exception =>
            println(s"!!!!!!! Cycle $currentWeek failed with exception: ${e.getClass.getSimpleName}: ${e.getMessage}. Retrying in 60s.")
            status = SimulationStatus.WAITING_CYCLE_START
            scheduleExecution(60000L)
        }

      case BroadcastAndAdvance =>
        val endTime = System.currentTimeMillis()
        println("Publish Cycle Complete message")
        SimulationEventStream.publish(CycleCompleted(currentWeek, endTime), None)

        currentWeek += 1
        CycleSource.setCycle(currentWeek)

        targetDeadline = System.currentTimeMillis() + configuredCycleIntervalMs()
        self ! ScheduleNext
    }
  }

  var status : SimulationStatus.Value = SimulationStatus.WAITING_CYCLE_START
  object SimulationStatus extends Enumeration {
    type ManagerTaskType = Value
    val IN_PROGRESS, WAITING_CYCLE_START = Value
  }

}