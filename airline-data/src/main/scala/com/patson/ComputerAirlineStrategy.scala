package com.patson

import com.patson.data.SoloConfig
import com.patson.model.{Airport, Computation, LinkClassValues}

object ComputerAirlineStrategy {
  object Strategy extends Enumeration {
    val RegionalFocus, TrunkFocus, LeisureFocus, PremiumFocus = Value
  }

  def strategyForAirline(airlineId : Int) : Strategy.Value =
    Strategy(Math.floorMod(airlineId, Strategy.values.size))

  def multiplier(strategy : Strategy.Value, from : Airport, to : Airport, demand : LinkClassValues, maxBonus : Double) : Double = {
    val bonus = Math.max(0.0, Math.min(0.5, maxBonus))
    val distance = Computation.calculateDistance(from, to)
    val aligned = strategy match {
      case Strategy.RegionalFocus => from.countryCode == to.countryCode || distance <= 1500
      case Strategy.TrunkFocus => distance >= 2500 && from.size >= 6 && to.size >= 6
      case Strategy.LeisureFocus => to.size <= 5 && demand.economyVal >= demand.businessVal + demand.firstVal
      case Strategy.PremiumFocus => demand.businessVal + demand.firstVal >= demand.economyVal * 0.25
    }
    if (aligned) 1.0 + bonus else 1.0 - (bonus / 2)
  }

  def scoreProfit(airlineId : Int, from : Airport, to : Airport, demand : LinkClassValues, profit : Long) : Long = {
    if (!SoloConfig.aiStrategyEnabled) profit
    else Math.round(profit * multiplier(strategyForAirline(airlineId), from, to, demand, SoloConfig.aiStrategyMaxBonus))
  }
}
