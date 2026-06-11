package controllers

import controllers.AuthenticationObject.Authenticated
import com.patson.MainSimulation
import com.patson.data.SimControlSource
import play.api.libs.json._
import play.api.mvc._

import javax.inject.Inject

/**
  * Player control over simulation pacing: target minutes per game week and
  * fast-forward (run N cycles back-to-back). Writes the sim_control table,
  * which the simulation scheduler polls (see MainSimulation).
  */
class SimControlApplication @Inject()(cc: ControllerComponents) extends AbstractController(cc) {
  val MAX_FAST_FORWARD = 52

  def getSimControl() = Authenticated { implicit request =>
    val defaultCycleMinutes : Int = MainSimulation.CYCLE_DURATION / 60
    val cycleMinutes : Int = SimControlSource.loadCycleMinutes().getOrElse(defaultCycleMinutes)
    Ok(Json.obj(
      "cycleMinutes" -> cycleMinutes,
      "defaultCycleMinutes" -> defaultCycleMinutes,
      "minCycleMinutes" -> MainSimulation.MIN_CYCLE_MINUTES,
      "maxCycleMinutes" -> MainSimulation.MAX_CYCLE_MINUTES,
      "fastForward" -> SimControlSource.loadFastForward(),
      "maxFastForward" -> MAX_FAST_FORWARD))
  }

  def setCycleMinutes(minutes : Int) = Authenticated { implicit request =>
    val clamped = Math.max(MainSimulation.MIN_CYCLE_MINUTES, Math.min(MainSimulation.MAX_CYCLE_MINUTES, minutes))
    SimControlSource.setCycleMinutes(clamped)
    println(s"User ${request.user.userName} set simulation cycle length to $clamped min")
    Ok(Json.obj("cycleMinutes" -> clamped))
  }

  def fastForward(cycles : Int) = Authenticated { implicit request =>
    val clamped = Math.max(0, Math.min(MAX_FAST_FORWARD, cycles))
    SimControlSource.setFastForward(clamped, MainSimulation.CYCLE_DURATION / 60)
    println(s"User ${request.user.userName} requested fast-forward of $clamped cycle(s)")
    Ok(Json.obj("fastForward" -> clamped))
  }
}
