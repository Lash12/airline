package com.patson

import com.typesafe.config.ConfigFactory

import java.util.Locale
import java.util.concurrent.ForkJoinPool
import scala.collection.parallel.ForkJoinTaskSupport
import scala.util.Try

object RuntimeSettings {
  private val config = ConfigFactory.load()
  private val availableProcessors = Math.max(1, Runtime.getRuntime.availableProcessors())

  private def parseBoolean(value: String): Option[Boolean] = {
    value.trim.toLowerCase(Locale.ROOT) match {
      case "1" | "true" | "yes" | "y" | "on" => Some(true)
      case "0" | "false" | "no" | "n" | "off" => Some(false)
      case _ => None
    }
  }

  private def envBoolean(name: String): Option[Boolean] = {
    sys.env.get(name).flatMap(parseBoolean)
  }

  private def envInt(name: String): Option[Int] = {
    sys.env.get(name).flatMap(value => Try(value.trim.toInt).toOption)
  }

  private def configBoolean(path: String, defaultValue: Boolean): Boolean = {
    if (config.hasPath(path)) {
      config.getBoolean(path)
    } else {
      defaultValue
    }
  }

  private def configInt(path: String, defaultValue: Int): Int = {
    if (config.hasPath(path)) {
      config.getInt(path)
    } else {
      defaultValue
    }
  }

  lazy val localLite: Boolean = envBoolean("AIRLINE_LOCAL_LITE").getOrElse(configBoolean("airline.local-lite", false))

  lazy val simulationParallelism: Int = Math.max(
    1,
    envInt("AIRLINE_SIMULATION_PARALLELISM").getOrElse(
      configInt("airline.simulation.parallelism", if (localLite) Math.max(1, availableProcessors / 2) else availableProcessors)
    )
  )

  lazy val demandFullLoadAirports: Boolean =
    envBoolean("AIRLINE_DEMAND_FULL_LOAD_AIRPORTS").getOrElse(configBoolean("airline.simulation.demand.full-load-airports", !localLite))

  lazy val cycleTimingEnabled: Boolean =
    envBoolean("AIRLINE_LOG_CYCLE_TIMINGS").getOrElse(configBoolean("airline.simulation.log-timings", true))

  lazy val routeMemoizationEnabled: Boolean =
    envBoolean("AIRLINE_MEMOIZE_ROUTES").getOrElse(configBoolean("airline.simulation.memoize-routes", true))

  lazy val dbPoolMaxSize: Int = Math.max(
    1,
    envInt("AIRLINE_DB_POOL_MAX_SIZE").getOrElse(configInt("airline.db.pool.max-size", if (localLite) 20 else 100))
  )

  lazy val actorThreadPoolSize: Int = Math.max(
    2,
    envInt("AIRLINE_ACTOR_THREAD_POOL_SIZE").getOrElse(
      configInt("airline.actor.thread-pool-size", if (localLite) 2 else Math.max(4, availableProcessors))
    )
  )

  lazy val simulationTaskSupport: ForkJoinTaskSupport = new ForkJoinTaskSupport(new ForkJoinPool(simulationParallelism))
}
