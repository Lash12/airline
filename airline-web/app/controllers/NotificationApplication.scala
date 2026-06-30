package controllers

import com.patson.data.{AirplaneSource, AirportSource, CountrySource, CycleSource, LinkSource, NotificationSource, SoloConfig, WorldNewsSource}
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
    // WORLD_NEWS, CONSULTANT_ADVICE and MARKET_OVERVIEW have their own panels; keep them out of the bell.
    val personal = NotificationSource.loadNotificationsByAirline(airlineId)
      .filterNot(n => n.category == NotificationCategory.WORLD_NEWS
        || n.category == NotificationCategory.CONSULTANT_ADVICE
        || n.category == NotificationCategory.MARKET_OVERVIEW)
    Ok(Json.toJson(personal))
  }

  // World news feed (pull-based, separate from the personal bell). Broadcast content shared
  // across all airlines; isRead is derived per-airline from a watermark, not a per-row flag
  // (see WorldNewsSource) -- the JSON shape still matches the personal-notification contract
  // so the existing frontend rendering needs no changes.
  def getNews(airlineId: Int) = AuthenticatedAirline(airlineId) { _ =>
    val watermark = WorldNewsSource.getOrInitWatermark(airlineId)
    val items = WorldNewsSource.loadRecent(50).map { item =>
      val base = Json.obj(
        "id"       -> item.id,
        "category" -> NotificationCategory.WORLD_NEWS.toString,
        "message"  -> item.message,
        "cycle"    -> item.cycle,
        "isRead"   -> WorldNewsSource.isRead(item.id, watermark)
      )
      item.targetId.fold(base)(tid => base + ("targetId" -> JsString(tid)))
    }
    Ok(Json.toJson(items))
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
        val summary = if (considerCommonality && r.familyInFleet > 0) s"$base · fits your ${r.familyKey} fleet (${r.familyInFleet})" else base
        val msg = s"$summary||${buildRecSidecar(r, airlineId, fleetByFamily)}"
        Notification(airlineId = airlineId, category = NotificationCategory.CONSULTANT_ADVICE, message = msg, cycle = currentCycle, targetId = Some(s"${r.from.id}-${r.to.id}"))
      }
      if (notifications.nonEmpty) NotificationSource.insertNotificationsBulk(notifications)

      // Market overview: biggest markets from the bases regardless of fleet, with fleet-gap notes.
      val allModels = com.patson.util.AirplaneModelCache.allModels.values.toList
      val market = ConsultantAdvisor.marketOverview(airline, levels, allAirports, countryRelationships, ownedModels, allModels, currentCycle)
      NotificationSource.deleteByCategory(airlineId, NotificationCategory.MARKET_OVERVIEW)
      val marketNotifs = market.map { mi =>
        val suggestion = mi.suggested.map(_.name).getOrElse("no in-range aircraft")
        val tag = if (mi.ownedFits) s"✓ serve with $suggestion" else s"⚠ fleet gap — consider $suggestion"
        val summary = s"${mi.from.iata} ↔ ${mi.to.iata} · ${f"${mi.demand}%,d"} pax/wk · ${f"${mi.distance}%,d"} km · $tag"
        val msg = s"$summary||${buildMarketSidecar(mi)}"
        Notification(airlineId = airlineId, category = NotificationCategory.MARKET_OVERVIEW, message = msg, cycle = currentCycle, targetId = Some(s"${mi.from.id}-${mi.to.id}"))
      }
      if (marketNotifs.nonEmpty) NotificationSource.insertNotificationsBulk(marketNotifs)

      Ok(Json.obj("count" -> recs.size, "markets" -> market.size, "cycle" -> currentCycle))
    }
  }

  private def buildRecSidecar(r: ConsultantAdvisor.Recommendation, airlineId: Int, fleetByFamily: Map[String, Int]): String = {
    val compLinks =
      LinkSource.loadFlightLinksByAirports(r.from.id, r.to.id).filterNot(_.airline.id == airlineId) ++
      LinkSource.loadFlightLinksByAirports(r.to.id, r.from.id).filterNot(_.airline.id == airlineId)
    val compCap = compLinks.map(_.capacity.total).sum
    val requiresExpansion = fleetByFamily.getOrElse(r.familyKey, 0) == 0
    val reasons = List(
      ConsultantAdvisor.demandReason(r.totalDemand),
      ConsultantAdvisor.competitionReason(compCap),
      ConsultantAdvisor.fleetReason(r.familyKey, r.familyInFleet, r.model.name)
    )
    Json.stringify(Json.obj("r" -> reasons, "x" -> requiresExpansion))
  }

  private def buildMarketSidecar(mi: ConsultantAdvisor.MarketInsight): String = {
    val demandStr = ConsultantAdvisor.demandReason(mi.demand)
    val fleetStr = if (mi.ownedFits) "Your fleet can serve this market"
      else mi.suggested.fold("Requires fleet expansion")(m => s"Requires fleet expansion — consider ${m.name}")
    val reasons = List(demandStr, fleetStr)
    Json.stringify(Json.obj("r" -> reasons, "x" -> !mi.ownedFits))
  }

  // Market overview list (pull-based, separate from the bell).
  def getMarketOverview(airlineId: Int) = AuthenticatedAirline(airlineId) { _ =>
    Ok(Json.toJson(NotificationSource.loadByCategory(airlineId, NotificationCategory.MARKET_OVERVIEW, 50)))
  }

  // Idle aircraft — frames owned by this airline with no link assignments.
  // Live query (not stored); gated by the same consultant flag so it only
  // appears when the consultant panel is unlocked. Returns [{modelName, homeIata, count}].
  def getIdleAircraft(airlineId: Int) = AuthenticatedAirline(airlineId) { _ =>
    if (!SoloConfig.consultantEnabled) {
      Ok(Json.arr())
    } else {
      val owned = AirplaneSource.loadAirplanesByOwner(airlineId).filterNot(_.isSold)
      val assignments = AirplaneSource.loadAirplaneLinkAssignmentsByOwner(airlineId)
      val idle = owned.filter(a => assignments.get(a.id).forall(_.isEmpty))
      val grouped = idle.groupBy(a => (a.model.name, a.home.iata)).map { case ((modelName, homeIata), planes) =>
        Json.obj("modelName" -> modelName, "homeIata" -> homeIata, "count" -> planes.size)
      }.toList.sortBy(j => (-(j \ "count").as[Int], (j \ "homeIata").as[String]))
      Ok(Json.toJson(grouped))
    }
  }

  def markNewsRead(airlineId: Int) = AuthenticatedAirline(airlineId) { _ =>
    WorldNewsSource.markSeen(airlineId, WorldNewsSource.latestId())
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
