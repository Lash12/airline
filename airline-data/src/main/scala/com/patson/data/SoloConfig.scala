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

  // Negotiation reminder: when enabled, failed-route negotiation discounts emit
  // a one-time bell notification when their cooldown expires and the route can
  // be attempted again. Off by default so default/multiplayer deploys are unchanged.
  val negotiationReadyEnabled : Boolean = boolAt("solo.negotiationReady.enabled", false)

  // Diagnostic: split DemandGenerator timing into base-demand vs chunk-generation
  // to decide whether memoizing the base layer is worthwhile (off by default).
  val demandProfile : Boolean = boolAt("solo.demand.profile", false)

  // Diagnostic: emit per-pass LinkSimulation route precompute and demand-consume
  // timings. Off by default because the sim log is already noisy in normal play.
  val linkProfile : Boolean = boolAt("solo.link.profile", false)

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
  // Evaluate up to this many of the airline's most-idle frames and pick the globally best
  // (frame, route) pair, so frame size matches route demand (a widebody won't be forced onto a
  // thin route where it flies empty). Bounds per-airline cost.
  val aiGrowthFramesConsidered : Int = intAt("solo.ai.growth.framesConsidered", 6)
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

  // Living-world AI strategy bias (H-5). Off by default. When enabled, NPC route
  // growth keeps the same profitability gate but applies a small deterministic
  // preference so carriers expand in more legible patterns instead of every NPC
  // chasing the same top route.
  val aiStrategyEnabled : Boolean = boolAt("solo.ai.strategy.enabled", false)
  val aiStrategyMaxBonus : Double = doubleAt("solo.ai.strategy.maxBonus", 0.25)

  // Living-world AI price tuning (Phase H-2). Off by default; independent of the other ai flags.
  // When on, each acting NPC nudges prices on a few of its existing links toward equilibrium by
  // recent load factor: a persistently full route edges its price up (capture revenue), a
  // persistently empty one edges down (fill seats, and undercut a competitor). Small bounded step,
  // clamped to a band around the standard price so prices never run away — this is what makes
  // competition feel adaptive without new-route churn. No aircraft/fleet changes here.
  val aiPriceTuneEnabled : Boolean = boolAt("solo.ai.pricetune.enabled", false)
  val aiPriceTuneMaxAirlinesPerCycle : Int = intAt("solo.ai.pricetune.maxAirlinesPerCycle", 3)
  val aiPriceTuneMaxLinksPerAirline : Int = intAt("solo.ai.pricetune.maxLinksPerAirline", 5)
  val aiPriceTuneStep : Double = doubleAt("solo.ai.pricetune.step", 0.05)
  val aiPriceTuneHighLoadFactor : Double = doubleAt("solo.ai.pricetune.highLoadFactor", 0.85)
  val aiPriceTuneLowLoadFactor : Double = doubleAt("solo.ai.pricetune.lowLoadFactor", 0.5)
  val aiPriceTuneFloorRatio : Double = doubleAt("solo.ai.pricetune.floorRatio", 0.6)
  val aiPriceTuneCeilRatio : Double = doubleAt("solo.ai.pricetune.ceilRatio", 1.5)
  val aiPriceTuneLookbackCycles : Int = intAt("solo.ai.pricetune.lookbackCycles", 4)

  // Living-world AI fleet renewal (Phase H-3). Off by default; independent of the other ai flags.
  // The sim already renews worn aircraft in place (buy replacement, sell old via ledger, reset
  // condition, keep airplane id so link assignments survive) but only for airlines with an
  // "airplane renewal" threshold; players get one at sign-up, NPCs never do, so NPC fleets decay to
  // scrap and networks shrink. When on, seed that threshold for NPCs so the existing (financially
  // self-limiting) renewal path keeps their fleets alive. Renew when condition < renewalThreshold.
  val aiFleetEnabled : Boolean = boolAt("solo.ai.fleet.enabled", false)
  val aiFleetRenewalThreshold : Int = intAt("solo.ai.fleet.renewalThreshold", 40)

  // Living-world AI base expansion (Phase H-4). Off by default; independent of the other ai flags.
  // H-1 can only open routes from airports where an NPC already has a base AND a spare frame is
  // homed, so each NPC's reach is permanently capped by its existing bases. When on, a thriving NPC
  // may occasionally open ONE new base by promoting a city it already flies to (a destination of its
  // existing links) into a base, re-homing one fully-idle owned frame there and launching that
  // frame's best profitable first route in the same cycle — so the hub is productive immediately and
  // H-1 grows the cluster around it on later cycles. Deliberately the most conservative growth step:
  // - at most maxOpeningsPerCycle base(s) open across ALL acting NPCs per cycle (rare/legible),
  // - a per-NPC base ceiling (maxBasesPerAirline),
  // - self-limiting economics: the real base construction cost is paid via the ledger and the NPC
  //   must hold cash >= cost * cashCushion AND have a profitable first route clearing
  //   openProfitThreshold, so only genuinely thriving carriers expand. No aircraft purchases here
  //   (re-homes an existing idle frame only; buying stays H-3's domain).
  val aiBasesEnabled : Boolean = boolAt("solo.ai.bases.enabled", false)
  val aiBasesMaxOpeningsPerCycle : Int = intAt("solo.ai.bases.maxOpeningsPerCycle", 1)
  val aiBasesMaxPerAirline : Int = intAt("solo.ai.bases.maxBasesPerAirline", 6)
  // Evaluate at most this many of the NPC's most-served destinations as candidate base sites.
  val aiBasesCandidateLimit : Int = intAt("solo.ai.bases.candidateLimit", 8)
  // The best first route from the new base must clear this projected weekly profit to commit.
  val aiBasesOpenProfitThreshold : Long = longAt("solo.ai.bases.openProfitThreshold", 0L)
  // Require balance >= constructionCost * cashCushion, so a base never leaves an NPC cash-starved.
  val aiBasesCashCushion : Double = doubleAt("solo.ai.bases.cashCushion", 3.0)

  // Airport assets (single-player). Off by default; player-facing investment layer adapted from
  // patsonluk/airline. A player spends cash to build assets at airports where they have a BASE; the
  // asset takes several cycles to construct, then contributes an AirportBoost (reusing the existing
  // AirportBoostContributor demand pipeline) and, for revenue/attraction types, a modest weekly
  // income — while infrastructure/transport types give a pure boost with no income. Every asset
  // carries weekly upkeep, so it is a self-limiting cash sink that pays off mainly via the demand it
  // creates at your fortress markets. Cost/upkeep/income are scaled by these multipliers so the whole
  // system can be tuned live without code changes.
  val assetsEnabled : Boolean = boolAt("solo.airportAssets.enabled", false)
  val assetsMaxLevel : Int = intAt("solo.airportAssets.maxLevel", 3)
  val assetsCostMultiplier : Double = doubleAt("solo.airportAssets.costMultiplier", 1.0)
  val assetsUpkeepMultiplier : Double = doubleAt("solo.airportAssets.upkeepMultiplier", 1.0)
  val assetsIncomeMultiplier : Double = doubleAt("solo.airportAssets.incomeMultiplier", 1.0)

  // Route/fleet consultant (single-player QOL). A manager assigned to the CONSULTANT task studies
  // the player's network and surfaces profitable route opportunities (+ a suggested aircraft) that
  // would otherwise require clicking every airport. Advice-only. Depth scales with the consultant's
  // level and how many are assigned (capped). Off by default.
  val consultantEnabled : Boolean = boolAt("solo.consultant.enabled", false)
  val consultantBaseRecs : Int = intAt("solo.consultant.baseRecommendations", 3)
  val consultantRecsPerLevel : Int = intAt("solo.consultant.recsPerLevel", 2)
  val consultantRecsPerExtraConsultant : Int = intAt("solo.consultant.recsPerExtraConsultant", 2)
  val consultantMaxRecs : Int = intAt("solo.consultant.maxRecommendations", 15)
  val consultantCandidateLimit : Int = intAt("solo.consultant.candidateLimit", 40)
  val consultantCaptureRatio : Double = doubleAt("solo.consultant.captureRatio", 0.7)
  // Fleet-commonality bias: from this consultant level up, advice favors (and mentions) routes whose
  // suggested aircraft is from a family the player already operates, rewarding a focused fleet. A
  // novice consultant ignores it. Bonus = perFrame * (frames of that family owned), capped.
  val consultantCommonalityLevel : Int = intAt("solo.consultant.commonalityLevel", 2)
  val consultantCommonalityPerFrame : Double = doubleAt("solo.consultant.commonalityPerFrame", 0.03)
  val consultantCommonalityMaxBonus : Double = doubleAt("solo.consultant.commonalityMaxBonus", 0.25)
  // Market overview: a more experienced consultant (level >= marketOverviewLevel) also reports the
  // biggest markets from the player's bases regardless of the current fleet, suggesting an ideal
  // aircraft and flagging fleet gaps (a market whose ideal plane the player doesn't own).
  val consultantMarketLevel : Int = intAt("solo.consultant.marketLevel", 2)
  val consultantMarketCount : Int = intAt("solo.consultant.marketCount", 5)
  val consultantMarketCandidateLimit : Int = intAt("solo.consultant.marketCandidateLimit", 80)

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
