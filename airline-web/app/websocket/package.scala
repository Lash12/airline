

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.dispatch.MessageDispatcher

import scala.concurrent.ExecutionContext
package object websocket {
  implicit lazy val actorSystem : ActorSystem = ActorSystem("airline-websocket-actor-system")
  //implicit val ec: ExecutionContext = ExecutionContext.global
  implicit lazy val executionContext : MessageDispatcher = actorSystem.dispatchers.lookup("my-pinned-dispatcher")
}
