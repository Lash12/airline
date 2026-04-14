package com

import org.apache.pekko.actor.ActorSystem
//import org.apache.pekko.stream.FlowMaterializer
import scala.concurrent.ExecutionContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
package object patson {
  private lazy val actorExecutor = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(RuntimeSettings.actorThreadPoolSize))
  implicit lazy val actorSystem : ActorSystem = ActorSystem("rabbit-akka-stream", None, None, Some(actorExecutor))

  import actorSystem.dispatcher

  //implicit val materializer = FlowMaterializer()
}
