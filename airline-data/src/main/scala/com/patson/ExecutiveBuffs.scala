package com.patson

import com.patson.data.SoloConfig
import com.patson.model.ExecutiveRole
import com.patson.util.ExecutiveCache

/**
  * Central, mostly-pure resolver for Executive (C-suite) effects. Every entry point short-circuits to a
  * neutral value when `solo.exec.enabled` is off, so default/multiplayer deploys pay zero cost and behave
  * byte-identically. When on, seat levels come from [[ExecutiveCache]] (cached; no per-link DB).
  *
  * Cost multipliers are <= 1.0 (a discount that grows with seat level, capped). Salary is a flat weekly
  * amount per seat scaling with level. Reputation thresholds gate which seats may be appointed.
  */
object ExecutiveBuffs {

  private def levelOf(airlineId : Int, role : ExecutiveRole.Value) : Int =
    if (!SoloConfig.execEnabled) 0
    else ExecutiveCache.getLevels(airlineId).getOrElse(role, 0)

  /** A discount multiplier in (0,1]: 1.0 at level 0, decreasing by perLevel each level, floored at 1-max. */
  private def discountMultiplier(level : Int, perLevel : Double, maxDiscount : Double) : Double =
    if (level <= 0) 1.0 else 1.0 - Math.min(maxDiscount, level * perLevel)

  // Level-keyed pure variants (used by the panel to preview a seat's effect). The airline-keyed
  // methods below resolve the seat level from the cache and delegate to these.
  def fuelCostMultiplierAtLevel(level : Int) : Double =
    discountMultiplier(level, SoloConfig.execCfoFuelDiscountPerLevel, SoloConfig.execCfoMaxFuelDiscount)
  def maintenanceCostMultiplierAtLevel(level : Int) : Double =
    discountMultiplier(level, SoloConfig.execCooMaintDiscountPerLevel, SoloConfig.execCooMaxMaintDiscount)
  def adviceDepthBonusAtLevel(level : Int) : Int =
    if (level <= 0) 0 else Math.min(SoloConfig.execCcoMaxAdviceBonus, level * SoloConfig.execCcoAdviceBonusPerLevel)

  /** CFO: multiply a link's fuel cost (<= 1.0). */
  def fuelCostMultiplier(airlineId : Int) : Double =
    fuelCostMultiplierAtLevel(levelOf(airlineId, ExecutiveRole.CFO))

  /** COO: multiply a link's maintenance cost (<= 1.0). */
  def maintenanceCostMultiplier(airlineId : Int) : Double =
    maintenanceCostMultiplierAtLevel(levelOf(airlineId, ExecutiveRole.COO))

  /** CCO: extra route recommendations the consultant surfaces (additive, capped). */
  def adviceDepthBonus(airlineId : Int) : Int =
    adviceDepthBonusAtLevel(levelOf(airlineId, ExecutiveRole.CCO))

  // ---- Salary (pure; no cache needed at appointment, cached lookup per cycle) ----

  /** Flat weekly salary for a seat at the given level. */
  def salaryForLevel(level : Int) : Int = SoloConfig.execSalaryBase + Math.max(0, level - 1) * SoloConfig.execSalaryPerLevel

  /** Total weekly salary across all of an airline's filled seats (0 when disabled). */
  def totalWeeklySalary(airlineId : Int) : Long =
    if (!SoloConfig.execEnabled) 0L
    else ExecutiveCache.getLevels(airlineId).values.map(level => salaryForLevel(level).toLong).sum

  // ---- Seat unlocking ----

  /** Reputation required to appoint a seat (Double.MaxValue = not offered yet). */
  def repThreshold(role : ExecutiveRole.Value) : Double = role match {
    case ExecutiveRole.CFO => SoloConfig.execCfoRepThreshold
    case ExecutiveRole.COO => SoloConfig.execCooRepThreshold
    case ExecutiveRole.CCO => SoloConfig.execCcoRepThreshold
    case _ => Double.MaxValue
  }

  def isUnlocked(reputation : Double, role : ExecutiveRole.Value) : Boolean = reputation >= repThreshold(role)
}
