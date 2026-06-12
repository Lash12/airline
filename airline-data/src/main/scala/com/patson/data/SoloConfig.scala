package com.patson.data

/**
  * Single-player tuning knobs. Every value defaults to the current upstream
  * constant, so default/multiplayer deploys are unchanged. A single-player host
  * opts in by setting the `solo.*` keys via system properties
  * (e.g. SBT_OPTS="-Dsolo.startingBalance=25000000") or application.conf.
  *
  * Read once at startup from the same loaded config as the rest of the game
  * (Constants.configFactory, which includes -D system properties).
  */
object SoloConfig {
  private val config = Constants.configFactory

  private def longAt(path : String, default : Long) : Long = if (config.hasPath(path)) config.getLong(path) else default
  private def intAt(path : String, default : Int) : Int = if (config.hasPath(path)) config.getInt(path) else default
  private def doubleAt(path : String, default : Double) : Double = if (config.hasPath(path)) config.getDouble(path) else default
  private def boolAt(path : String, default : Boolean) : Boolean = if (config.hasPath(path)) config.getBoolean(path) else default

  // New-player economy (consumed by airline-web SignUp)
  val startingBalance : Long = longAt("solo.startingBalance", 0L)
  val minRenewalBalance : Long = longAt("solo.minRenewalBalance", 300000L)

  // Action-point generation (ManagerBaseTask / ManagerSimulation)
  val apGenerationRate : Double = doubleAt("solo.ap.generationRate", 0.1)
  val apMaxCyclesStored : Int = intAt("solo.ap.maxCyclesStored", 24 * 4)

  // Loans (Bank)
  val loanDefaultAnnualRate : Double = doubleAt("solo.loan.defaultAnnualRate", 0.11)

  // Bankruptcy thresholds (GameConstants / AirlineSimulation)
  val bankruptcyCashThreshold : Int = intAt("solo.bankruptcy.cashThreshold", -10_000_000)
  val bankruptcyAssetsThreshold : Int = intAt("solo.bankruptcy.assetsThreshold", -100_000_000)

  // Financial notifications for player airlines (AirlineSimulation). Off by
  // default so multiplayer deploys are unchanged. Throttled by intervalCycles.
  val notifyEnabled : Boolean = boolAt("solo.notify.enabled", false)
  val notifyCashWarningThreshold : Long = longAt("solo.notify.cashWarningThreshold", 5_000_000L)
  val notifyProfitMilestone : Long = longAt("solo.notify.profitMilestone", 10_000_000L)
  val notifyIntervalCycles : Int = intAt("solo.notify.intervalCycles", 4)

  // Diagnostic: split DemandGenerator timing into base-demand vs chunk-generation
  // to decide whether memoizing the base layer is worthwhile (off by default).
  val demandProfile : Boolean = boolAt("solo.demand.profile", false)

  // Memoize the deterministic base-demand layer across cycles (Step E-1). The base
  // layer is ~55x the per-cycle chunk cost but its inputs (airport demographics +
  // country relationships) barely change, so a fingerprint-invalidated cache serves
  // most pairs for free. Off by default (multiplayer/default deploys unchanged); the
  // cache is ~635MB at full dataset, so only enable with sufficient sim heap.
  val demandMemoize : Boolean = boolAt("solo.demand.memoize", false)

  // Living-world dynamic AI (ComputerAirlineSimulation). Off by default. MVP is
  // drop-only: NPC airlines cancel persistently money-losing routes. Bounded per
  // cycle so it cannot destabilize the economy or blow the cycle budget.
  val aiEnabled : Boolean = boolAt("solo.ai.enabled", false)
  val aiAirlinesPerCycle : Int = intAt("solo.ai.airlinesPerCycle", 10)
  val aiMaxDropsPerAirline : Int = intAt("solo.ai.maxDropsPerAirline", 1)
  val aiLossLookbackCycles : Int = intAt("solo.ai.lossLookbackCycles", 4)
  val aiDropProfitThreshold : Long = longAt("solo.ai.dropProfitThreshold", -500000L)
}
