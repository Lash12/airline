package com.patson

import com.patson.data.SoloConfig
import com.patson.model.{Airport, Computation}

/**
 * Air cargo demand layer (Phase C-1).
 *
 * Mirrors the structure of [[DemandGenerator]] but models a single weekly cargo-units figure per
 * directed airport pair, driven by economic mass (population x income, a proxy for goods produced
 * and consumed) rather than passenger propensity. The math is a pure, deterministic function so it
 * is unit-testable and safe to memoize.
 *
 * Everything here is gated by `solo.cargo.enabled` at the call sites, so when the flag is off this
 * code never runs and default/multiplayer deploys are byte-identical. C-1 has NO gameplay effect:
 * it only computes the model and exposes a per-cycle inspection summary. The cached lookup is what
 * the belly-cargo revenue step (Phase C-2) will consume.
 */
object CargoDemandGenerator {
  // Calibration. Cargo demand = geometric mean of the two airports' economic mass, divided by this,
  // then adjusted by affinity and distance. Chosen so a major economic pair yields on the order of
  // hundreds of weekly cargo-units (comparable to belly capacity on a daily widebody). It is a pure
  // scaling constant; live tuning is done via SoloConfig.cargoDemandAmplitude.
  val CARGO_BASE_DIVISOR = 2.0e9
  // Below this distance, air freight loses to trucking, so demand fades linearly to zero.
  val TRUCKING_DISTANCE = 400

  case class CargoHubProfile(rank : Int, iata : String) {
    val multiplier : Double =
      if (rank <= 5) 1.35
      else if (rank <= 10) 1.25
      else 1.15
  }

  val cargoHubProfiles : Map[String, CargoHubProfile] = List(
    "HKG", "PVG", "ANC", "SDF", "MIA", "MEM", "ICN", "DOH", "TPE", "CAN",
    "LAX", "NRT", "FRA", "CDG", "SIN", "DXB", "ORD", "AMS", "BKK", "LHR"
  ).zipWithIndex.map { case (iata, idx) => iata -> CargoHubProfile(idx + 1, iata) }.toMap

  def cargoHubMultiplier(fromAirport : Airport, toAirport : Airport) : Double = {
    val from = cargoHubProfiles.get(fromAirport.iata).map(_.multiplier).getOrElse(1.0)
    val to = cargoHubProfiles.get(toAirport.iata).map(_.multiplier).getOrElse(1.0)
    Math.min(1.4, Math.sqrt(from * to))
  }

  /**
   * Weekly cargo-units demand from one airport to another (directed). Pure and deterministic.
   * Returns 0 when the pair cannot have demand or either side has no economy.
   */
  def computeCargoDemandBetweenAirports(fromAirport: Airport, toAirport: Airport, affinity: Int, distance: Int): Int = {
    if (!DemandGenerator.canHaveDemand(fromAirport, toAirport, distance)) return 0
    if (fromAirport.income <= 0 || toAirport.income <= 0) return 0

    // Economic mass = population * income (a proxy for total goods produced/consumed at each end).
    val fromEcon = fromAirport.population.toDouble * fromAirport.income
    val toEcon = toAirport.population.toDouble * toAirport.income
    if (fromEcon <= 0 || toEcon <= 0) return 0

    // Gravity model: geometric mean keeps the magnitude sane and rewards pairs where BOTH ends are
    // productive (real trade lanes), not a single megacity shipping to a village.
    val gravity = math.sqrt(fromEcon) * math.sqrt(toEcon) / CARGO_BASE_DIVISOR

    // Domestic/allied lanes carry more freight; foreign/hostile less. Same shape as the passenger
    // affinity curve but with a higher floor (some trade flows even across weak relationships).
    val affinityMultiplier =
      if (affinity >= 5) (affinity - 5) * 0.05 + 1
      else if (affinity < 0) 0.1
      else affinity * 0.1 + 0.1

    // Short hops lose to trucking; everything above the trucking threshold carries at full weight
    // (high-value air freight is comparatively distance-insensitive vs. passenger demand).
    val distanceMultiplier =
      if (distance < TRUCKING_DISTANCE) distance.toDouble / TRUCKING_DISTANCE
      else 1.0

    val raw = gravity * affinityMultiplier * distanceMultiplier * cargoHubMultiplier(fromAirport, toAirport) * SoloConfig.cargoDemandAmplitude
    if (raw <= 0) 0 else raw.toInt
  }

  // --- Per-cycle memoization (mirrors DemandGenerator's base-demand cache) ---------------------
  // One int per directed pair, index-keyed by the cycle's ordered airport array. The cache is
  // fingerprint-invalidated: a full reset on airport-count or relationship-epoch change, otherwise
  // per-airport eviction (its row and column) when an airport's demographics change.

  private val ABSENT = -1

  @volatile private var cacheData : Array[Array[Int]] = null
  private var fingerprints : Array[Long] = null
  private var lastEpoch : Long = Long.MinValue
  private var lastCount : Int = -1
  private var airportIndexById : Map[Int, Int] = Map.empty
  private var cachedAirports : Array[Airport] = Array.empty

  /** Demographic fingerprint of an airport: any change invalidates its cached cargo demand. */
  def airportFingerprint(airport : Airport) : Long = {
    var h = 1125899906842597L // prime
    h = 31 * h + airport.income
    h = 31 * h + airport.population
    h = 31 * h + airport.size
    h = 31 * h + airport.zone.hashCode
    h = 31 * h + airport.countryCode.hashCode
    h
  }

  /**
   * Prepare the cargo cache for this cycle. Returns (fullReset, evictedCount). Single-threaded;
   * call once before any cached lookups. Mirrors `DemandGenerator.prepareBaseDemandCache`.
   */
  def prepareCargoCache(orderedAirports : Array[Airport], relationshipEpoch : Long) : (Boolean, Int) = {
    val n = orderedAirports.length
    val fullReset = cacheData == null || n != lastCount || relationshipEpoch != lastEpoch
    airportIndexById = orderedAirports.zipWithIndex.map { case (airport, index) => airport.id -> index }.toMap
    cachedAirports = orderedAirports
    if (fullReset) {
      cacheData = Array.fill(n)(Array.fill(n)(ABSENT))
      fingerprints = orderedAirports.map(airportFingerprint)
      lastCount = n
      lastEpoch = relationshipEpoch
      (true, n)
    } else {
      var changed = 0
      var i = 0
      while (i < n) {
        val fp = airportFingerprint(orderedAirports(i))
        if (fp != fingerprints(i)) {
          java.util.Arrays.fill(cacheData(i), ABSENT) // row i (from this airport)
          var j = 0
          while (j < n) { cacheData(j)(i) = ABSENT; j += 1 } // column i (to this airport)
          fingerprints(i) = fp
          changed += 1
        }
        i += 1
      }
      (false, changed)
    }
  }

  private def cachedDemand(fromIndex : Int, toIndex : Int, fromAirport : Airport, toAirport : Airport, affinity : Int, distance : Int) : Int = {
    val row = cacheData(fromIndex)
    if (row(toIndex) == ABSENT) {
      val d = computeCargoDemandBetweenAirports(fromAirport, toAirport, affinity, distance)
      row(toIndex) = d
      d
    } else {
      row(toIndex)
    }
  }

  def demandFor(fromAirport : Airport, toAirport : Airport, countryRelationships : Map[(String, String), Int] = Map.empty) : Int = {
    val distance = Computation.calculateDistance(fromAirport, toAirport)
    if (!DemandGenerator.canHaveDemand(fromAirport, toAirport, distance)) return 0
    val relationship = countryRelationships.getOrElse((fromAirport.countryCode, toAirport.countryCode), 0)
    val affinity = Computation.calculateAffinityValue(fromAirport.zone, toAirport.zone, relationship)
    (airportIndexById.get(fromAirport.id), airportIndexById.get(toAirport.id), Option(cacheData)) match {
      case (Some(fromIndex), Some(toIndex), Some(_)) if fromIndex < cachedAirports.length && toIndex < cachedAirports.length =>
        cachedDemand(fromIndex, toIndex, cachedAirports(fromIndex), cachedAirports(toIndex), affinity, distance)
      case _ =>
        computeCargoDemandBetweenAirports(fromAirport, toAirport, affinity, distance)
    }
  }

  /**
   * Inspection (C-1.4): sweep all directed pairs, populate the cache, and return a one-line summary
   * of how much cargo demand the world generates this cycle. No gameplay effect — purely diagnostic.
   * Reuses the airports + country relationships already loaded by `DemandGenerator.computeDemand`.
   */
  def summarizeCycle(airports : Seq[Airport], countryRelationships : Map[(String, String), Int]) : String = {
    val ordered = airports.toArray
    val (fullReset, changed) = prepareCargoCache(ordered, countryRelationships.hashCode.toLong)
    var total = 0L
    var pairsWithCargo = 0L
    var i = 0
    while (i < ordered.length) {
      val fromAirport = ordered(i)
      var j = 0
      while (j < ordered.length) {
        if (i != j) {
          val toAirport = ordered(j)
          val distance = Computation.calculateDistance(fromAirport, toAirport)
          if (DemandGenerator.canHaveDemand(fromAirport, toAirport, distance)) {
            val relationship = countryRelationships.getOrElse((fromAirport.countryCode, toAirport.countryCode), 0)
            val affinity = Computation.calculateAffinityValue(fromAirport.zone, toAirport.zone, relationship)
            val d = cachedDemand(i, j, fromAirport, toAirport, affinity, distance)
            if (d > 0) { total += d; pairsWithCargo += 1 }
          }
        }
        j += 1
      }
      i += 1
    }
    val cacheState = if (fullReset) "full-reset" else s"incremental evicted=$changed"
    s"[cargo] demand summary: cache $cacheState; $pairsWithCargo directed pairs with cargo, total $total weekly cargo-units (N=${ordered.length})"
  }

  /**
   * Top cargo-demand destinations from `fromAirport` among `candidates`.
   * Pure: callers supply the candidate airports and a country->relationship map
   * for `fromAirport`'s country (so this opens no DB connection). O(candidates).
   */
  def topCargoDestinations(fromAirport: Airport, candidates: List[Airport], relationshipsByCountry: Map[String, Int], limit: Int): List[(Airport, Int)] = {
    candidates.iterator.filter(_.id != fromAirport.id).flatMap { to =>
      val distance = Util.calculateDistance(fromAirport.latitude, fromAirport.longitude, to.latitude, to.longitude).toInt
      val relationship = relationshipsByCountry.getOrElse(to.countryCode, 0)
      val affinity = Computation.calculateAffinityValue(fromAirport.zone, to.zone, relationship)
      val demand = computeCargoDemandBetweenAirports(fromAirport, to, affinity, distance)
      if (demand > 0) Some((to, demand)) else None
    }.toList.sortBy(-_._2).take(limit)
  }
}
