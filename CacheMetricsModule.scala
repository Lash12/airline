package com.airline.smallserver.commons

import com.google.inject.{AbstractModule, Inject}
import com.github.benmanes.caffeine.cache.stats.StatsCounter
import com.github.benmanes.caffeine.cache.stats.CaffeineStatsCounter
import com.github.benmanes.caffeine.cache.{Caffeine, Cache => CaffeineCache}
import io.micrometer.core.instrument.MeterRegistry
import play.api.cache.{AsyncCacheApi, SyncCacheApi}
import play.api.cache.caffeine.CaffeineCacheManager

/**
 * Guice module that registers all Caffeine caches with Micrometer.
 * Requires .recordStats() enabled in application.conf
 */
class CacheMetricsModule extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[CacheMetricsInitializer]).asEagerSingleton()
  }
}

@Singleton
class CacheMetricsInitializer @Inject() (
  registry: MeterRegistry,
  cacheManager: CaffeineCacheManager
) {
  // Called on application startup
  def initialize(): Unit = {
    import scala.jdk.CollectionConverters._
    
    cacheManager.cacheNames().asScala.foreach { cacheName =>
      val cache = cacheManager.getCache(cacheName)
      
      // Get the underlying Caffeine cache
      val caffeineCache = getCaffeineCacheFrom(cache)
      
      // Register stats with Micrometer
      CaffeineStatsCounter.bindCaffeineStats(registry, caffeineCache, cacheName)
    }
  }

  private def getCaffeineCacheFrom(cache: AsyncCacheApi): CaffeineCache[Any, Any] = {
    // Use Java reflection to access the underlying CaffeineCache instance
    val syncCache = cache.sync
    
    syncCache.getClass.getDeclaredMethod("underlying").invoke(syncCache)
      .asInstanceOf[CaffeineCache[Any, Any]]
  }
}