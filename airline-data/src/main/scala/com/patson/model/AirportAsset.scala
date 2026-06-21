package com.patson.model

import com.patson.data.SoloConfig

/**
  * Airport assets (single-player feature, adapted from patsonluk/airline). A player invests cash to
  * build an asset at an airport where they hold a base. After a multi-cycle construction it becomes
  * ACTIVE and contributes an [[AirportBoost]] through the existing AirportBoostContributor demand
  * pipeline (see Airport.initAirportAssets), plus — for REVENUE/ATTRACTION types — a modest weekly
  * income. INFRASTRUCTURE types give a pure boost and never earn. Every active asset pays weekly
  * upkeep, so an asset is a self-limiting cash sink whose real payoff is the demand it creates at the
  * owner's fortress markets.
  *
  * All numeric behavior is pure and unit-tested in AirportAssetSpec; cost/upkeep/income are scaled by
  * the solo.airportAssets.* multipliers so the system can be tuned live.
  */
object AirportAssetCategory extends Enumeration {
  type AirportAssetCategory = Value
  val REVENUE, ATTRACTION, INFRASTRUCTURE = Value
}

object AirportAssetStatus extends Enumeration {
  type AirportAssetStatus = Value
  val UNDER_CONSTRUCTION, ACTIVE = Value
}

/**
  * Blueprint for an asset kind. `baseBoostPerLevel` is expressed in the units of its `boostType`:
  * absolute income/population for INCOME/POPULATION, feature strength points for the hub types
  * (mirroring AirlineBaseSpecialization boost magnitudes).
  */
sealed abstract class AirportAssetType(val id : String,
                                       val label : String,
                                       val category : AirportAssetCategory.Value,
                                       val boostType : AirportBoostType.Value,
                                       val baseBoostPerLevel : Double,
                                       val baseCost : Long,
                                       val constructionDuration : Int,
                                       val sizeRequirement : Int,
                                       val image : String,
                                       val benefit : String) {

  def maxLevel : Int = Math.max(1, SoloConfig.assetsMaxLevel)

  /** Weekly income minus upkeep at a level. Negative for attraction/infrastructure by design. */
  def netWeekly(airport : Airport, level : Int) : Long = weeklyIncome(airport, level) - upkeep(airport, level)

  /** Cycles to recoup one level's construction cost from net weekly cash, if ever (None if net <= 0). */
  def paybackCycles(airport : Airport, level : Int) : Option[Int] = {
    val net = netWeekly(airport, level)
    if (net <= 0) None else Some(Math.ceil(constructionCost(airport, level).toDouble / net).toInt)
  }

  /** Infrastructure/transport assets grow the city but never earn; revenue/attraction types do. */
  def generatesIncome : Boolean = category != AirportAssetCategory.INFRASTRUCTURE
  private def incomeFactor : Double = category match {
    case AirportAssetCategory.REVENUE       => 1.0
    case AirportAssetCategory.ATTRACTION    => 0.5
    case AirportAssetCategory.INFRASTRUCTURE => 0.0
  }

  /** Boost contributed at a given level (linear in level); empty for a non-positive level. */
  def boostsAt(level : Int) : List[AirportBoost] =
    if (level <= 0) Nil else List(AirportBoost(boostType, baseBoostPerLevel * level))

  /** Bigger airports cost more (and are worth more): size 5 = 1.0x, size 10 = 2.0x, size 1 = 0.4x. */
  def airportModifier(airport : Airport) : Double = Math.max(0.4, airport.size.toDouble / 5.0)

  /** Cash cost to build one level (flat per level), rounded to the nearest 1000. */
  def unitCost(airport : Airport) : Long =
    AirportAssetType.roundTo1000((baseCost * airportModifier(airport) * SoloConfig.assetsCostMultiplier).toLong)

  /** Cost to construct/upgrade to `targetLevel` (one level at a time, so just one unit). */
  def constructionCost(airport : Airport, targetLevel : Int) : Long = unitCost(airport)

  def totalInvested(airport : Airport, level : Int) : Long = unitCost(airport) * Math.max(0, level)
  def sellValue(airport : Airport, level : Int) : Long = totalInvested(airport, level) / 2

  def upkeep(airport : Airport, level : Int) : Long =
    if (level <= 0) 0L
    else Math.round(unitCost(airport) * AirportAssetType.UPKEEP_RATE * level * SoloConfig.assetsUpkeepMultiplier)

  def weeklyIncome(airport : Airport, level : Int) : Long =
    if (!generatesIncome || level <= 0) 0L
    else Math.round(unitCost(airport) * AirportAssetType.INCOME_RATE * incomeFactor * level * SoloConfig.assetsIncomeMultiplier)
}

object AirportAssetType {
  import AirportAssetCategory._

  // Weekly upkeep / income as a fraction of one level's construction cost. Income for attraction
  // types is further halved (incomeFactor), so a revenue asset runs a small surplus while an
  // attraction asset runs a small deficit — the demand boost is the real return either way.
  val UPKEEP_RATE = 0.008
  val INCOME_RATE = 0.01

  case object SHOPPING_MALL     extends AirportAssetType("SHOPPING_MALL", "Shopping Mall", REVENUE, AirportBoostType.INCOME, 3000, 150_000_000L, 16, 4, "SHOPPING_MALL.png", "Raises overall passenger demand at this airport by lifting its income level, and earns rent.")
  case object GRAND_HOTEL       extends AirportAssetType("GRAND_HOTEL", "Grand Hotel", REVENUE, AirportBoostType.INCOME, 2500, 120_000_000L, 12, 5, "GRAND_HOTEL_BUSINESS.png", "Raises overall passenger demand at this airport by lifting its income level, and earns room revenue.")
  case object RESORT            extends AirportAssetType("RESORT", "Resort", ATTRACTION, AirportBoostType.VACATION_HUB, 4, 90_000_000L, 12, 3, "BEACH_RESORT.png", "Strengthens this airport as a vacation hub, drawing more inbound tourist demand.")
  case object CONVENTION_CENTER extends AirportAssetType("CONVENTION_CENTER", "Convention Center", ATTRACTION, AirportBoostType.FINANCIAL_HUB, 5, 200_000_000L, 20, 6, "CONVENTION_CENTER.png", "Strengthens this airport as a financial hub, drawing more inbound business demand.")
  case object LANDMARK          extends AirportAssetType("LANDMARK", "Landmark", ATTRACTION, AirportBoostType.INTERNATIONAL_HUB, 4, 250_000_000L, 24, 7, "LANDMARK.png", "Strengthens this airport as an international hub, drawing more inbound long-haul tourist demand.")
  case object METRO             extends AirportAssetType("METRO", "Metro / Transit", INFRASTRUCTURE, AirportBoostType.POPULATION, 30000, 200_000_000L, 20, 5, "SUBWAY.png", "Grows the catchment population around this airport, raising demand across the board. No direct income.")
  case object CARGO_TERMINAL    extends AirportAssetType("CARGO_TERMINAL", "Cargo Terminal", INFRASTRUCTURE, AirportBoostType.CARGO, 15, 180_000_000L, 16, 4, "SUBWAY.png", "Raises cargo throughput at this airport, improving cargo carried on passenger and freight routes. No direct income.")

  val values : List[AirportAssetType] = List(SHOPPING_MALL, GRAND_HOTEL, RESORT, CONVENTION_CENTER, LANDMARK, METRO, CARGO_TERMINAL)
  def fromId(id : String) : Option[AirportAssetType] = values.find(_.id == id)

  private def roundTo1000(v : Long) : Long = Math.round(v / 1000.0) * 1000
}

case class AirportAsset(airline : Airline,
                        airport : Airport,
                        assetType : AirportAssetType,
                        level : Int,
                        status : AirportAssetStatus.Value,
                        completionCycle : Int,
                        var id : Int = 0) {
  def isOperational : Boolean = status == AirportAssetStatus.ACTIVE
  def currentBoosts : List[AirportBoost] = if (isOperational) assetType.boostsAt(level) else Nil
  def weeklyIncome : Long = if (isOperational) assetType.weeklyIncome(airport, level) else 0L
  def weeklyUpkeep : Long = if (isOperational) assetType.upkeep(airport, level) else 0L

  /** Construction is finished once the simulation reaches the completion cycle. */
  def isComplete(currentCycle : Int) : Boolean = currentCycle >= completionCycle
  def activated : AirportAsset = copy(status = AirportAssetStatus.ACTIVE)
}

object AirportAsset {
  /**
    * Pure build/upgrade validation. `currentLevel` is 0 when building a brand-new asset (so
    * `targetLevel` is 1). Returns Some(reason) if rejected, None if allowed.
    */
  def validateBuild(hasBaseAtAirport : Boolean,
                    airportSize : Int,
                    assetType : AirportAssetType,
                    currentLevel : Int,
                    targetLevel : Int,
                    balance : Long,
                    cost : Long) : Option[String] = {
    if (!hasBaseAtAirport) Some("You must have a base at this airport to build an asset.")
    else if (airportSize < assetType.sizeRequirement) Some(s"${assetType.label} requires an airport of size ${assetType.sizeRequirement} or larger.")
    else if (targetLevel != currentLevel + 1) Some("Assets can only be upgraded one level at a time.")
    else if (targetLevel > assetType.maxLevel) Some(s"${assetType.label} cannot exceed level ${assetType.maxLevel}.")
    else if (cost > balance) Some("Not enough cash.")
    else None
  }
}
