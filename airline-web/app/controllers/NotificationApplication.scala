package controllers

import com.patson.data.{AirplaneSource, AirportSource, CountrySource, CycleSource, NotificationSource, SoloConfig}
import com.patson.model.{ManagerTaskType, LevelingManagerTask, Notification, NotificationCategory}
import com.patson.ConsultantAdvisor
import controllers.AuthenticationObject.AuthenticatedAirline
import javax.inject.Inject
import play.api.libs.json._
import play.api.mvc._

class NotificationApplication @Inject()(cc: ControllerComponents) extends AbstractController(cc) {

  implicit val notificationWrites: Writes[Notification] = (n: Notification) => {
    val base = Json.obj(
      "id"       -> n.id,
      "category" -> n.category.toString,
      "message"  -> n.message,
      "cycle"    -> n.cycle,
      "isRead"   -> n.isRead
    )
    val withExpiry = n.expiryCycle.fold(base)(ec => base + ("expiryCycle" -> JsNumber(ec)))
    n.targetId.fold(withExpiry)(tid => withExpiry + ("targetId" -> JsString(tid)))
  }

  def getNotifications(airlineId: Int) = AuthenticatedAirline(airlineId) { _ =>
    NotificationSource.purgeExpiredByCategory(airlineId, NotificationCategory.NEGOTIATION_LOSS, CycleSource.loadCycle())
    // WORLD_NEWS and CONSULTANT_ADVICE have their own panels; keep them out of the personal bell.
    val personal = NotificationSource.loadNotificationsByAirline(airlineId)
      .filterNot(n => n.category == NotificationCategory.WORLD_NEWS || n.category == NotificationCategory.CONSULTANT_ADVICE)
    Ok(Json.toJson(personal))
  }

  // World news feed (pull-based, separate from the personal bell).
  def getNews(airlineId: Int) = AuthenticatedAirline(airlineId) { _ =>
    Ok(Json.toJson(NotificationSource.loadByCategory(airlineId, NotificationCategory.WORLD_NEWS, 50)))
  }

  // Route consultant advice — a pull-based, timestamped list (separate from the bell).
  def getConsultantAdvice(airlineId: Int) = AuthenticatedAirline(airlineId) { _ =>
    Ok(Json.toJson(NotificationSource.loadByCategory(airlineId, NotificationCategory.CONSULTANT_ADVICE, 50)))
  }

  // Regenerate advice: the assigned consultant(s) study the network and replace the stored report,
  // stamped with the current cycle. Advice-only; nothing is opened.
  def refreshConsultantAdvice(airlineId: Int) = AuthenticatedAirline(airlineId) { request =>
    val airline = request.user
    val currentCycle = CycleSource.loadCycle()
    val consultants = airline.getManagerInfo().busyManagers.filter(_.assignedTask.getTaskType == ManagerTaskType.CONSULTANT)
    if (!SoloConfig.consultantEnabled || consultants.isEmpty) {
      Ok(Json.obj("count" -> 0, "cycle" -> currentCycle))
    } else {
      val levels = consultants.map(_.assignedTask.asInstanceOf[LevelingManagerTask].level(currentCycle))
      val allAirports = AirportSource.loadAllAirports(true)
      val countryRelationships = CountrySource.getCountryMutualRelationships()
      val ownedAirplanes = AirplaneSource.loadAirplanesByOwner(airlineId).filterNot(_.isSold)
      val ownedModels = ownedAirplanes.map(_.model).distinct
      val fleetByFamily = ownedAirplanes.groupBy(a => ConsultantAdvisor.familyKeyOf(a.model)).map { case (k, v) => (k, v.size) }
      val considerCommonality = levels.nonEmpty && levels.max >= SoloConfig.consultantCommonalityLevel
      val recs = ConsultantAdvisor.recommendations(airline, levels, allAirports, countryRelationships, ownedModels, fleetByFamily, currentCycle)
      NotificationSource.deleteByCategory(airlineId, NotificationCategory.CONSULTANT_ADVICE)
      val notifications = recs.map { r =>
        val cabin = Seq(
          if (r.config.economyVal > 0) Some(s"${r.config.economyVal}Y") else None,
          if (r.config.businessVal > 0) Some(s"${r.config.businessVal}J") else None,
          if (r.config.firstVal > 0) Some(s"${r.config.firstVal}F") else None
        ).flatten.mkString(" ")
        val base = s"${r.from.iata} → ${r.to.iata} · ${f"${r.distance}%,d"} km · ${r.model.name} ($cabin) · ~$$${f"${r.estWeeklyProfit}%,d"}/wk"
        val msg = if (considerCommonality && r.familyInFleet > 0) s"$base · fits your ${r.familyKey} fleet (${r.familyInFleet})" else base
        Notification(airlineId = airlineId, category = NotificationCategory.CONSULTANT_ADVICE, message = msg, cycle = currentCycle, targetId = Some(s"${r.from.id}-${r.to.id}"))
      }
      if (notifications.nonEmpty) NotificationSource.insertNotificationsBulk(notifications)
      Ok(Json.obj("count" -> recs.size, "cycle" -> currentCycle))
    }
  }

  def markNewsRead(airlineId: Int) = AuthenticatedAirline(airlineId) { _ =>
    NotificationSource.markCategoryRead(airlineId, NotificationCategory.WORLD_NEWS)
    Ok(Json.obj())
  }

  def getUnreadCount(airlineId: Int) = AuthenticatedAirline(airlineId) { _ =>
    Ok(Json.obj("count" -> NotificationSource.countUnreadByAirline(airlineId)))
  }

  def markAllRead(airlineId: Int) = AuthenticatedAirline(airlineId) { _ =>
    NotificationSource.markAllRead(airlineId)
    Ok(Json.obj())
  }

  def markSingleRead(airlineId: Int, notifId: Int) = AuthenticatedAirline(airlineId) { _ =>
    NotificationSource.markSingleRead(airlineId, notifId)
    Ok(Json.obj())
  }

  def deleteAllRead(airlineId: Int) = AuthenticatedAirline(airlineId) { _ =>
    NotificationSource.deleteAllRead(airlineId)
    Ok(Json.obj())
  }

  def deleteNotification(airlineId: Int, notifId: Int) = AuthenticatedAirline(airlineId) { _ =>
    NotificationSource.deleteNotification(airlineId, notifId)
    Ok(Json.obj())
  }
}
