package com.patson.util

import com.patson.data.ExecutiveSource
import com.patson.model.ExecutiveRole

/**
  * Per-airline executive roster cache (role → seat level). The buff lookups run in the per-link hot
  * path of LinkSimulation, so this avoids a DB round-trip per link. Mirrors AirplaneOwnershipCache.
  * Invalidated by the controller whenever a seat is appointed or dismissed.
  */
object ExecutiveCache {
  import com.github.benmanes.caffeine.cache.{Caffeine, CacheLoader, LoadingCache}

  private val simpleCache : LoadingCache[Int, Map[ExecutiveRole.Value, Int]] =
    Caffeine.newBuilder().maximumSize(10000).build(new SimpleLoader())

  def getLevels(airlineId : Int) : Map[ExecutiveRole.Value, Int] = simpleCache.get(airlineId)

  def invalidate(airlineId : Int) : Unit = simpleCache.invalidate(airlineId)

  def invalidateAll() : Unit = simpleCache.invalidateAll()

  class SimpleLoader() extends CacheLoader[Int, Map[ExecutiveRole.Value, Int]] {
    override def load(airlineId : Int) : Map[ExecutiveRole.Value, Int] =
      ExecutiveSource.loadByAirline(airlineId).map(e => e.role -> e.level).toMap
  }
}
