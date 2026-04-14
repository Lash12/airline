package com

import org.apache.pekko.actor.ActorSystem
//import org.apache.pekko.stream.FlowMaterializer
import scala.concurrent.ExecutionContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
package object patson {
  private lazy val actorExecutorService: ExecutorService = Executors.newFixedThreadPool(
    RuntimeSettings.actorThreadPoolSize,
    new java.util.concurrent.ThreadFactory {
      private val defaultThreadFactory = Executors.defaultThreadFactory()

      override def newThread(runnable: Runnable): Thread = {
        val thread = defaultThreadFactory.newThread(runnable)
        thread.setDaemon(true)
        thread
      }
    }
  )
  private lazy val actorExecutor = ExecutionContext.fromExecutorService(actorExecutorService)
  implicit lazy val actorSystem : ActorSystem = {
    val system = ActorSystem("rabbit-akka-stream", None, None, Some(actorExecutor))
    system.registerOnTermination {
      actorExecutorService.shutdown()
    }
    system
  }

  import actorSystem.dispatcher

  //implicit val materializer = FlowMaterializer()
}
