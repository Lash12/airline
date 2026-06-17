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

  // Living-world AI growth (Phase H-1). Independent of aiEnabled (drops); off by default.
  // When on, each acting NPC may OPEN one profitable route from an existing base using a
  // spare (idle) owned airplane — the growth counterpart to the drop path. Bounded and
  // self-limiting: at most maxOpensPerAirline route(s)/cycle, only if projected weekly profit
  // (the real LinkSimulation cost model on an estimated load factor) clears openProfitThreshold
  // AND the network is under maxNetworkSize. No aircraft purchases in H-1 — only opens when a
  // spare frame already exists (frames freed by the drop path are the natural supply).
  val aiGrowthEnabled : Boolean = boolAt("solo.ai.growth.enabled", false)
  // Only this many of the acting NPCs attempt to open a route each cycle, keeping growth slow
  // and legible ("notice a change every few sessions") rather than redeploying all idle frames
  // at once. Bounds the per-cycle open count regardless of how many frames are spare.
  val aiMaxGrowthAirlinesPerCycle : Int = intAt("solo.ai.growth.maxAirlinesPerCycle", 3)
  val aiMaxOpensPerAirline : Int = intAt("solo.ai.growth.maxOpensPerAirline", 1)
  val aiOpenProfitThreshold : Long = longAt("solo.ai.growth.openProfitThreshold", 0L)
  val aiGrowthCandidateLimit : Int = intAt("solo.ai.growth.candidateLimit", 20)
  val aiMaxNetworkSize : Int = intAt("solo.ai.growth.maxNetworkSize", 60)
  // Fraction of base demand an NPC is assumed to capture when projecting a new route's load
  // factor (conservative; a new entrant won't take 100%). Lower = stricter open gate.
  val aiGrowthCaptureRatio : Double = doubleAt("solo.ai.growth.captureRatio", 0.65)
  // A frame counts as "spare" only if it has at least this many idle weekly flight-minutes
  // (MAX_FLIGHT_MINUTES is 6480), so we don't open a route on a near-fully-utilised plane.
  val aiGrowthMinAvailableMinutes : Int = intAt("solo.ai.growth.minSpareFlightMinutes", 600)
  // Service quality assumed for an NPC-opened route (feeds price/cost estimation).
  val aiGrowthRawQuality : Int = intAt("solo.ai.growth.rawQuality", 40)

  // World news feed (single-player): an ambient, pull-based log of notable world
  // events (NPC route changes, etc.) surfaced in a dedicated News panel, separate from
  // the personal notification bell. Off by default. Reuses the notification store under
  // a WORLD_NEWS category (no schema change); events only emit when both this and the
  // relevant source (e.g. solo.ai.enabled) are on.
  val newsEnabled : Boolean = boolAt("solo.news.enabled", false)

  // Solo progression (Phase G): surface the already-computed milestone system to the
  // player as one-time achievement notifications + a progress view. Off by default so
  // multiplayer/default deploys are unchanged (no notifications, no behavior change).
  val progressionEnabled : Boolean = boolAt("solo.progression.enabled", false)

  // Demand balance (ported from upstream v5): seasonal amplitude multiplier for TOURIST
  // demand in DemandGenerator.demandRandomizerByType. Our fork inherited 2.0 (very strong
  // tourist swings → routes whiplash between profitable and dead); upstream iteratively
  // reduced this to 1.25. Exposed as a knob so it can be tuned live during playtest.
  val demandTouristAmplitude : Double = doubleAt("solo.demand.touristAmplitude", 1.25)

  // Lounges & alliances (single-player). Lounges are an alliance facility upstream, but
  // the sim already supports airline-owned lounges (Airport.getLounge is airline-first);
  // allowWithoutAlliance lets a solo airline build one standalone (benefits its own premium
  // pax, no codeshare impact). allianceMinMembers lowers the count at which an alliance
  // becomes ESTABLISHED (upstream 3) so a player's own 2 airlines can form a real alliance.
  val loungeWithoutAlliance : Boolean = boolAt("solo.lounge.allowWithoutAlliance", false)
  val allianceMinMembers : Int = intAt("solo.alliance.minMembers", 3)
}
