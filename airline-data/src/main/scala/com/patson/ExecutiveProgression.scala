package com.patson

import com.patson.data.SoloConfig
import com.patson.model.ExecutiveRole

/**
  * Pure leveling rules for executives (Phase 2). Executives gain experience from how well their domain
  * performs each cycle (in contrast to managers, which level by tenure). The sim loop snapshots the
  * relevant per-airline KPIs into [[Kpi]] and applies these rules; persistence/cache-invalidation lives
  * in AirlineSimulation. Everything here is pure and unit-tested.
  */
object ExecutiveProgression {

  /** Per-airline, per-cycle performance snapshot the leveling rules read. */
  case class Kpi(weeklyProfit : Long, onTime : Double, loadFactor : Double)

  /** Whether a seat earns 1 xp this cycle, given its domain's result. */
  def earnsXp(role : ExecutiveRole.Value, kpi : Kpi) : Boolean = role match {
    case ExecutiveRole.CFO => kpi.weeklyProfit > 0
    case ExecutiveRole.COO => kpi.onTime >= SoloConfig.execCooOnTimeXpThreshold
    case ExecutiveRole.CCO => kpi.loadFactor >= SoloConfig.execCcoLoadFactorXpThreshold
    case _ => false
  }

  /** Seat level for a given total xp: starts at 1, +1 per xpPerLevel, capped at maxLevel. */
  def levelForXp(xp : Int) : Int =
    Math.min(SoloConfig.execMaxLevel, 1 + Math.max(0, xp) / SoloConfig.execXpPerLevel)

  /** Total xp needed to reach the next level from the given level, or None at the cap. */
  def xpForNextLevel(level : Int) : Option[Int] =
    if (level >= SoloConfig.execMaxLevel) None else Some(level * SoloConfig.execXpPerLevel)

  def isMaxLevel(level : Int) : Boolean = level >= SoloConfig.execMaxLevel
}
