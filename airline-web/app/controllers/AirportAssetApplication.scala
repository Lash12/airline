package controllers

import com.patson.data.{AirlineSource, AirportAssetSource, CycleSource, SoloConfig}
import com.patson.model._
import com.patson.util.AirportCache
import controllers.AuthenticationObject.AuthenticatedAirline
import play.api.libs.json._
import play.api.mvc._

import javax.inject.Inject

/**
  * Player-facing API for airport assets (single-player feature, gated by solo.airportAssets.enabled).
  * Build/upgrade an asset (one level at a time) at an airport where the airline has a base; sell it
  * back for half its invested value. All endpoints no-op/forbid when the flag is off.
  */
class AirportAssetApplication @Inject()(cc: ControllerComponents) extends AbstractController(cc) {

  private def assetJson(asset: AirportAsset, currentCycle: Int): JsObject = {
    val boostValue: Double = asset.assetType.boostsAt(asset.level).headOption.map(_.value).getOrElse(0.0)
    Json.obj(
    "id" -> asset.id,
    "assetType" -> asset.assetType.id,
    "label" -> asset.assetType.label,
    "image" -> asset.assetType.image,
    "category" -> asset.assetType.category.toString,
    "level" -> asset.level,
    "maxLevel" -> asset.assetType.maxLevel,
    "status" -> asset.status.toString,
    "boostType" -> AirportBoostType.getLabel(asset.assetType.boostType),
    "boostValue" -> boostValue,
    "weeklyIncome" -> asset.weeklyIncome,
    "weeklyUpkeep" -> asset.weeklyUpkeep,
    "sellValue" -> asset.assetType.sellValue(asset.airport, asset.level),
    "remainingCycles" -> Math.max(0, asset.completionCycle - currentCycle)
    )
  }

  private def catalogJson(assetType: AirportAssetType, airport: Airport, ownedLevel: Int): JsObject = {
    val nextLevel = ownedLevel + 1
    Json.obj(
      "assetType" -> assetType.id,
      "label" -> assetType.label,
      "category" -> assetType.category.toString,
      "boostType" -> AirportBoostType.getLabel(assetType.boostType),
      "boostPerLevel" -> assetType.baseBoostPerLevel,
      "generatesIncome" -> assetType.generatesIncome,
      "sizeRequirement" -> assetType.sizeRequirement,
      "constructionDuration" -> assetType.constructionDuration,
      "ownedLevel" -> ownedLevel,
      "maxLevel" -> assetType.maxLevel,
      "nextLevelCost" -> assetType.constructionCost(airport, nextLevel),
      "nextLevelUpkeep" -> assetType.upkeep(airport, nextLevel),
      "nextLevelIncome" -> assetType.weeklyIncome(airport, nextLevel),
      "image" -> assetType.image,
      "benefit" -> assetType.benefit,
      "nextLevelNet" -> assetType.netWeekly(airport, nextLevel),
      "nextLevelPayback" -> assetType.paybackCycles(airport, nextLevel),
      "canUpgrade" -> (nextLevel <= assetType.maxLevel),
      "meetsSize" -> (airport.size >= assetType.sizeRequirement)
    )
  }

  def getAssets(airlineId: Int, airportId: Int) = AuthenticatedAirline(airlineId) { request =>
    if (!SoloConfig.assetsEnabled) {
      Ok(Json.obj("enabled" -> false, "assets" -> Json.arr(), "catalog" -> Json.arr()))
    } else {
      AirportCache.getAirport(airportId, true) match {
        case None => NotFound(Json.obj("error" -> s"airport $airportId not found"))
        case Some(airport) =>
          val currentCycle = CycleSource.loadCycle()
          val hasBase = AirlineSource.loadAirlineBaseByAirlineAndAirport(airlineId, airportId).isDefined
          val owned = AirportAssetSource.loadAirportAssetsByAirport(airport).filter(_.airline.id == airlineId)
          val ownedByType = owned.map(a => (a.assetType.id, a.level)).toMap
          val catalog = AirportAssetType.values.filter(t => t != AirportAssetType.CARGO_TERMINAL || SoloConfig.cargoAssetsEnabled).map(t => catalogJson(t, airport, ownedByType.getOrElse(t.id, 0)))
          Ok(Json.obj(
            "enabled" -> true,
            "hasBase" -> hasBase,
            "airportSize" -> airport.size,
            "balance" -> request.user.getBalance(),
            "assets" -> owned.map(assetJson(_, currentCycle)),
            "catalog" -> catalog
          ))
      }
    }
  }

  def buildAsset(airlineId: Int, airportId: Int) = AuthenticatedAirline(airlineId) { request =>
    if (!SoloConfig.assetsEnabled) {
      Forbidden("Airport assets are not enabled")
    } else if (!request.body.isInstanceOf[AnyContentAsJson]) {
      BadRequest("expected JSON body")
    } else {
      val assetTypeId = (request.body.asInstanceOf[AnyContentAsJson].json \ "assetType").as[String]
      (AirportCache.getAirport(airportId, true), AirportAssetType.fromId(assetTypeId)) match {
        case (None, _) => NotFound(s"airport $airportId not found")
        case (_, None) => BadRequest(s"unknown asset type $assetTypeId")
        case (Some(airport), Some(assetType)) =>
          if (assetType == AirportAssetType.CARGO_TERMINAL && !SoloConfig.cargoAssetsEnabled) {
            BadRequest("Cargo terminal is not enabled")
          } else {
            val airline = request.user
            val currentCycle = CycleSource.loadCycle()
            val existing = AirportAssetSource.loadAirportAssetsByAirport(airport).find(a => a.airline.id == airlineId && a.assetType == assetType)
            val currentLevel = existing.map(_.level).getOrElse(0)
            val targetLevel = currentLevel + 1
            val hasBase = AirlineSource.loadAirlineBaseByAirlineAndAirport(airlineId, airportId).isDefined
            val cost = assetType.constructionCost(airport, targetLevel)

            if (existing.exists(_.status == AirportAssetStatus.UNDER_CONSTRUCTION)) {
              BadRequest("This asset is already under construction.")
            } else {
              AirportAsset.validateBuild(hasBase, airport.size, assetType, currentLevel, targetLevel, airline.getBalance(), cost) match {
                case Some(reason) => BadRequest(reason)
                case None =>
                  val completion = currentCycle + assetType.constructionDuration
                  existing match {
                    case Some(asset) => AirportAssetSource.updateAirportAsset(asset.copy(level = targetLevel, status = AirportAssetStatus.UNDER_CONSTRUCTION, completionCycle = completion))
                    case None => AirportAssetSource.saveAirportAsset(AirportAsset(airline, airport, assetType, targetLevel, AirportAssetStatus.UNDER_CONSTRUCTION, completion))
                  }
                  AirlineSource.saveLedgerEntry(AirlineLedgerEntry(airlineId, currentCycle, LedgerType.AIRPORT_ASSET_CONSTRUCTION, -cost, Some(s"${assetType.label} at ${airport.iata} Lv$targetLevel")))
                  Ok(Json.obj("ok" -> true, "completionCycle" -> completion))
              }
            }
          }
      }
    }
  }

  def sellAsset(airlineId: Int, airportId: Int, assetId: Int) = AuthenticatedAirline(airlineId) { request =>
    if (!SoloConfig.assetsEnabled) {
      Forbidden("Airport assets are not enabled")
    } else {
      AirportAssetSource.loadAirportAssetById(assetId) match {
        case Some(asset) if asset.airline.id == airlineId && asset.airport.id == airportId =>
          val currentCycle = CycleSource.loadCycle()
          val refund = asset.assetType.sellValue(asset.airport, asset.level)
          AirportAssetSource.deleteAirportAsset(asset)
          if (refund > 0) {
            AirlineSource.saveLedgerEntry(AirlineLedgerEntry(airlineId, currentCycle, LedgerType.AIRPORT_ASSET_CONSTRUCTION, refund, Some(s"Sold ${asset.assetType.label} at ${asset.airport.iata}")))
          }
          Ok(Json.obj("refund" -> refund))
        case _ => BadRequest("asset not found or not owned")
      }
    }
  }
}
