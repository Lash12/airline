package controllers

import com.patson.data.{AirlineSource, AirplaneSource, AirportAssetSource, AirportSource, CountrySource, CycleSource, LinkSource, NotificationSource, SoloConfig, WorldNewsSource}
import com.patson.model.{AirportAssetCategory, AirportAssetStatus, AirportAssetType, LevelingManagerTask, ManagerTaskType, Notification, NotificationCategory}
import com.patson.{CargoMarketVisibilityService, ConsultantAdvisor}
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

  def getAdvisorRecommendations(airlineId: Int) = AuthenticatedAirline(airlineId) { request =>
    val currentCycle = CycleSource.loadCycle()
    val consultants = request.user.getManagerInfo().busyManagers.filter(_.assignedTask.getTaskType == ManagerTaskType.CONSULTANT)
    val levels = consultants.map(_.assignedTask.asInstanceOf[LevelingManagerTask].level(currentCycle))
    val bestLevel = if (levels.isEmpty) 0 else levels.max
    val tier = if (!SoloConfig.consultantEnabled) 0 else ConsultantAdvisor.advisorTier(levels)
    val proficiency = if (!SoloConfig.consultantEnabled) 0.0 else ConsultantAdvisor.advisorProficiency(levels)
    val recs =
      if (!SoloConfig.consultantEnabled || levels.isEmpty) Nil
      else buildAdvisorRecommendations(request.user, levels, currentCycle)

    Ok(Json.obj(
      "advisorLevel" -> bestLevel,
      "advisorProficiency" -> proficiency,
      "advisorTier" -> tier,
      "recommendations" -> recs
    ))
  }

  private def advisorRec(recType: String,
                         tier: Int,
                         priority: String,
                         title: String,
                         summary: String,
                         details: String,
                         estimatedImpact: String,
                         risk: String,
                         action: Option[(String, String)]): JsObject = {
    val base = Json.obj(
      "type" -> recType,
      "tier" -> tier,
      "priority" -> priority,
      "title" -> title,
      "summary" -> summary,
      "details" -> details,
      "estimatedImpact" -> estimatedImpact,
      "risk" -> risk
    )
    action match {
      case Some((label, target)) => base + ("action" -> Json.obj("label" -> label, "target" -> target))
      case None => base + ("action" -> JsNull)
    }
  }

  private def priorityRank(priority: String): Int = priority match {
    case "HIGH" => 0
    case "MEDIUM" => 1
    case _ => 2
  }

  private def buildAdvisorRecommendations(airline: com.patson.model.Airline,
                                          levels: Seq[Int],
                                          currentCycle: Int): List[JsObject] = {
    val tier = ConsultantAdvisor.advisorTier(levels)
    val recs = scala.collection.mutable.ListBuffer[JsObject]()
    val guidedActions = tier >= 4
    val advanced = tier >= 5

    val ownedAirplanes = AirplaneSource.loadAirplanesByOwner(airline.id).filterNot(_.isSold)
    val assignments = AirplaneSource.loadAirplaneLinkAssignmentsByOwner(airline.id)
    val idle = ownedAirplanes.filter(a => assignments.get(a.id).forall(_.isEmpty))

    val allAirports = AirportSource.loadAllAirports(true)
    val countryRelationships = CountrySource.getCountryMutualRelationships()
    val ownedModels = ownedAirplanes.map(_.model).distinct
    val fleetByFamily = ownedAirplanes.groupBy(a => ConsultantAdvisor.familyKeyOf(a.model)).map { case (k, v) => (k, v.size) }
    val routeRecs =
      if (tier >= 2) ConsultantAdvisor.recommendations(airline, levels, allAirports, countryRelationships, ownedModels, fleetByFamily, currentCycle)
      else Nil

    if (idle.nonEmpty) {
      val grouped = idle.groupBy(a => (a.model.name, a.home.iata)).toList.sortBy { case (_, planes) => -planes.size }
      val idleSummary = grouped.take(3).map { case ((model, iata), planes) => s"${planes.size} ${model} at $iata" }.mkString(", ")
      val bestRoute = routeRecs.headOption
      val summary =
        if (tier >= 2 && bestRoute.nonEmpty) {
          val r = bestRoute.get
          s"Use idle capacity on ${r.from.iata} to ${r.to.iata} with ${r.model.name} around 7x weekly."
        } else s"You have ${idle.size} idle aircraft: $idleSummary."
      val details =
        if (advanced && bestRoute.nonEmpty) {
          val r = bestRoute.get
          s"Forecast route profit about $$${f"${r.estWeeklyProfit}%,d"}/wk; demand ${f"${r.totalDemand}%,d"} pax/wk; distance ${f"${r.distance}%,d"} km."
        } else "Idle frames earn nothing and still tie up capital."
      val action = bestRoute.filter(_ => guidedActions).map(r => ("Plan route", s"planRoute:${r.from.id}-${r.to.id}"))
      recs += advisorRec("IDLE_AIRCRAFT", Math.min(tier, 2), "HIGH", "Idle aircraft available", summary, details,
        bestRoute.map(r => s"~$$${f"${r.estWeeklyProfit}%,d"}/wk").getOrElse("Utilization upside"),
        bestRoute.map(_ => "Confirm aircraft fit and cash before opening.").getOrElse("No specific route yet; refresh after adding fleet or bases."),
        action)
    }

    val links = LinkSource.loadFlightLinksByAirlineId(airline.id)
    val consumptions = LinkSource.loadLinkConsumptionsByLinksId(links.map(_.id), 4)
    val losing = consumptions.groupBy(_.link.id).flatMap { case (_, rows) =>
      val profit = rows.map(_.profit.toLong).sum
      val revenue = rows.map(_.revenue.toLong).sum
      val latest = rows.maxBy(_.cycle).link
      if (profit < 0 || (revenue > 0 && profit.toDouble / revenue < 0.08)) Some((latest, profit, revenue)) else None
    }.toList.sortBy(_._2).headOption

    losing.foreach { case (link, profit, revenue) =>
      val priority = if (profit < 0) "HIGH" else "MEDIUM"
      val margin = if (revenue > 0) f"${profit.toDouble / revenue * 100}%.1f%%" else "negative"
      recs += advisorRec("LOSING_ROUTE", Math.min(tier, 2), priority, s"${link.from.iata} to ${link.to.iata} underperforming",
        "Review frequency, prices, and aircraft size before adding more capacity.",
        if (advanced) s"Recent profit $$${f"${profit}%,d"} on $$${f"${revenue}%,d"} revenue; margin $margin." else s"Recent margin is $margin.",
        s"Stop ongoing losses of $$${f"${Math.abs(profit)}%,d"} over the recent sample.",
        "Close the route only if lower frequency, smaller aircraft, and price tuning cannot recover it.",
        None)
    }

    val baseAirports = AirlineSource.loadAirlineBasesByAirline(airline.id).map(_.airport)
    if (SoloConfig.cargoEnabled && baseAirports.nonEmpty) {
      val cargoOpp = baseAirports.flatMap(a => CargoMarketVisibilityService.getCargoOpportunities(a.id).take(3)).sortBy(o => (-o.score, -o.estimatedProfit)).headOption
      cargoOpp.foreach { opp =>
        recs += advisorRec("CARGO_OPPORTUNITY", Math.min(tier, 2), if (opp.weeklyCargoUnserved >= 300) "HIGH" else "MEDIUM",
          s"Cargo lane to ${opp.destinationCode}",
          s"${f"${opp.weeklyCargoUnserved}%,d"} unserved cargo units; ${opp.reasonText}",
          if (advanced) s"Estimated yield $$${f"${opp.estimatedYieldPerUnitKm}%.4f"} per cargo unit per km; ranked score ${f"${opp.score}%.0f"}." else opp.riskText,
          s"${opp.profitBand} cargo potential",
          opp.riskText,
          if (guidedActions) Some(("Plan cargo route", s"cargoRoute:${opp.originAirportId}-${opp.destinationAirportId}")) else None)
      }
    }

    if (SoloConfig.assetsEnabled && baseAirports.nonEmpty) {
      val ownedAssets = AirportAssetSource.loadAirportAssetsByAirline(airline.id)
      val byAirportType = ownedAssets.groupBy(a => (a.airport.id, a.assetType.id)).map { case (k, v) => k -> v.maxBy(_.level) }
      val candidates = baseAirports.flatMap { airport =>
        AirportAssetType.values.filter(_.category == AirportAssetCategory.REVENUE).flatMap { assetType =>
          val current = byAirportType.get((airport.id, assetType.id))
          val currentLevel = current.map(_.level).getOrElse(0)
          val targetLevel = currentLevel + 1
          val cost = assetType.constructionCost(airport, targetLevel)
          val activeOrEmpty = current.forall(_.status == AirportAssetStatus.ACTIVE)
          if (targetLevel <= assetType.maxLevel && activeOrEmpty && airport.size >= assetType.sizeRequirement && cost <= airline.getBalance()) {
            Some((airport, assetType, targetLevel, cost, assetType.paybackCycles(airport, targetLevel)))
          } else None
        }
      }
      candidates.sortBy { case (_, _, _, cost, payback) => (payback.getOrElse(Int.MaxValue), cost) }.headOption.foreach {
        case (airport, assetType, targetLevel, cost, payback) =>
          recs += advisorRec("AIRPORT_ASSET", Math.min(tier, 2), "MEDIUM", s"Upgrade ${airport.iata} assets",
            s"Consider ${assetType.label} level $targetLevel at ${airport.iata}.",
            payback.map(p => s"Estimated payback is about $p cycles.").getOrElse("This is mainly a demand-building investment."),
            s"Cost $$${f"${cost}%,d"}",
            "Keep cash reserves; avoid upgrades if route losses are consuming cash.",
            if (guidedActions) Some(("Open airport assets", s"airport:${airport.id}")) else None)
      }
    }

    recs.toList.sortBy(r => (priorityRank((r \ "priority").as[String]), (r \ "tier").as[Int])).take(12)
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
