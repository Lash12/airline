package com.patson

import com.patson.data.SoloConfig
import com.patson.model.{AirportBoostType, LinkConsumptionDetails}

object CargoAllocation {
  case class Result(linkId : Int, carried : Int, revenue : Int)

  def cargoTerminalMultiplier(details : LinkConsumptionDetails) : Double = {
    val fromBoost = details.link.from.boostFactorsByType.get(AirportBoostType.CARGO).map(_._2).sum
    val toBoost = details.link.to.boostFactorsByType.get(AirportBoostType.CARGO).map(_._2).sum
    val fromMultiplier = 1.0 + fromBoost / 100.0
    val toMultiplier = 1.0 + toBoost / 100.0
    (fromMultiplier + toMultiplier) / 2.0
  }

  def allocate(details : Seq[LinkConsumptionDetails]) : Map[Int, Result] = {
    if (!SoloConfig.cargoEnabled) return Map.empty
    details.filter(_.cargoCapacity > 0).groupBy(d => (d.link.from.id, d.link.to.id)).values.flatMap { group =>
      val representative = group.head
      val demand = CargoDemandGenerator.demandFor(representative.link.from, representative.link.to)
      val capturable = Math.round(demand * SoloConfig.cargoCaptureRatio * cargoTerminalMultiplier(representative)).toInt
      allocateGroup(group, capturable)
    }.map(result => result.linkId -> result).toMap
  }

  def allocateGroup(details : Seq[LinkConsumptionDetails], capturableDemand : Int) : Seq[Result] = {
    val sorted = details.sortBy(d => (-d.cargoCapacity, d.link.id))
    val totalCapacity = sorted.map(_.cargoCapacity).sum
    val totalToCarry = Math.max(0, Math.min(capturableDemand, totalCapacity))
    if (totalToCarry == 0 || totalCapacity == 0) {
      return sorted.map(d => Result(d.link.id, 0, 0))
    }

    var allocated = 0
    val base = sorted.map { detail =>
      val carried = Math.min(detail.cargoCapacity, Math.floor(totalToCarry.toDouble * detail.cargoCapacity / totalCapacity).toInt)
      allocated += carried
      detail -> carried
    }.toArray

    var remaining = totalToCarry - allocated
    var i = 0
    while (remaining > 0 && i < base.length) {
      val (detail, carried) = base(i)
      if (carried < detail.cargoCapacity) {
        base(i) = detail -> (carried + 1)
        remaining -= 1
      }
      i = (i + 1) % base.length
    }

    base.map { case (detail, carried) =>
      Result(detail.link.id, carried, Math.round(carried * detail.link.distance * SoloConfig.cargoRevenuePerUnitKm).toInt)
    }
  }
}
