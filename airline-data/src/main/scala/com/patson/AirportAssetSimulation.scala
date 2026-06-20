package com.patson

import com.patson.data.{AirlineSource, AirportAssetSource, NotificationSource, SoloConfig}
import com.patson.model._

/**
  * Per-cycle phase for airport assets (single-player). Gated by solo.airportAssets.enabled.
  *
  *  1. Completes any asset whose construction has finished (UNDER_CONSTRUCTION -> ACTIVE) and
  *     notifies the owner. An ACTIVE asset's boost is picked up by the airport demand pipeline
  *     (Airport.initAirportAssets) on the next airport load.
  *  2. Books each operational asset's weekly income (revenue/attraction types) and upkeep (all
  *     types) to the airline ledger, which atomically adjusts cash.
  *
  * Runs after the airport phase and before the airline phase in MainSimulation, so balances are
  * consistent for that cycle's bankruptcy check. A no-op (and zero DB cost) when the flag is off.
  */
object AirportAssetSimulation {
  def simulate(cycle : Int) : Unit = {
    if (!SoloConfig.assetsEnabled) return
    try {
      val assets = AirportAssetSource.loadAllAirportAssets()
      if (assets.isEmpty) return

      // 1. Flip finished construction to ACTIVE and notify the owner.
      val (building, alreadyActive) = assets.partition(_.status == AirportAssetStatus.UNDER_CONSTRUCTION)
      val justCompleted = building.filter(_.isComplete(cycle)).map(_.activated)
      justCompleted.foreach { asset =>
        AirportAssetSource.updateAirportAsset(asset)
        NotificationSource.insertNotification(Notification(asset.airline.id, NotificationCategory.AIRPORT_ASSET_COMPLETE,
          s"Your ${asset.assetType.label} at ${asset.airport.iata} is complete and now boosting the airport.",
          cycle, targetId = Some(asset.airport.id.toString)))
      }

      // 2. Book weekly income + upkeep for every operational asset (incl. the ones just completed).
      val operational = alreadyActive ++ justCompleted
      val ledger = operational.flatMap { asset =>
        val incomeEntry =
          if (asset.weeklyIncome > 0) Some(AirlineLedgerEntry(asset.airline.id, cycle, LedgerType.AIRPORT_ASSET_INCOME, asset.weeklyIncome, Some(s"${asset.assetType.label} at ${asset.airport.iata}"))) else None
        val upkeepEntry =
          if (asset.weeklyUpkeep > 0) Some(AirlineLedgerEntry(asset.airline.id, cycle, LedgerType.AIRPORT_ASSET_UPKEEP, -asset.weeklyUpkeep, Some(s"${asset.assetType.label} at ${asset.airport.iata}"))) else None
        incomeEntry.toList ++ upkeepEntry.toList
      }
      AirlineSource.saveLedgerEntries(ledger)

      println(s"[asset] cycle $cycle: ${justCompleted.size} completed, ${operational.size} operational, ${ledger.size} ledger entries")
    } catch {
      case e : Exception => println(s"[asset] AirportAssetSimulation failed (skipping): ${e.getClass.getSimpleName}: ${e.getMessage}")
    }
  }
}
