package com

import org.apache.pekko.actor.ActorSystem
//import org.apache.pekko.stream.FlowMaterializer
import com.typesafe.config.ConfigFactory
import scala.concurrent.ExecutionContext
import java.util.concurrent.{Executors, ThreadFactory}
import java.util.concurrent.atomic.AtomicInteger
package object patson {
  //bounded pool: the previous unbounded cached pool could grow without limit under simulation load
  private val simulationPoolSize = {
    val config = ConfigFactory.load()
    if (config.hasPath("simulation.threadPoolSize")) config.getInt("simulation.threadPoolSize")
    else math.max(4, Runtime.getRuntime.availableProcessors())
  }
  private val simulationThreadFactory = new ThreadFactory {
    private val counter = new AtomicInteger()
    override def newThread(r : Runnable) : Thread = {
      val thread = new Thread(r, s"simulation-pool-${counter.incrementAndGet()}")
      thread.setDaemon(true) //do not block JVM exit after actorSystem.terminate()
      thread
    }
  }
  implicit val actorSystem : ActorSystem = ActorSystem("rabbit-pekko-stream", None, None, Some(ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(simulationPoolSize, simulationThreadFactory))))

  import actorSystem.dispatcher
}
