import com.google.inject.AbstractModule
import com.patson.data.SchemaPatchRunner

class Module extends AbstractModule {
  override def configure(): Unit = {
    SchemaPatchRunner.run()
    bind(classOf[websocket.ActorCenterLifecycle]).asEagerSingleton()
    bind(classOf[push.PushNotificationScheduler]).asEagerSingleton()
  }
}
