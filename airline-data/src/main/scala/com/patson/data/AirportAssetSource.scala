package com.patson.data

import com.patson.data.Constants._
import com.patson.model._
import com.patson.util.{AirlineCache, AirportCache}

import java.sql.Statement
import scala.collection.mutable.ListBuffer
import scala.util.Using

/**
  * Persistence for airport assets (single-player feature). Mirrors the AirlineBase DAO style:
  * straightforward SQL via Meta.getConnection, cache invalidation on every mutation. Airport/airline
  * objects are resolved through the caches so callers get fully-formed AirportAsset instances.
  */
object AirportAssetSource {

  @volatile private var tableEnsured = false

  /**
    * Create the airport_asset table if it is missing. Mirrors HeartbeatSource's self-creating
    * pattern so the feature works on a pre-existing database (Meta.createSchema only runs on a fresh
    * init). Idempotent and cheap after the first call. Indexes are declared inline so we avoid
    * "CREATE INDEX IF NOT EXISTS" (unsupported on older MySQL).
    */
  def ensureTable() : Unit = {
    if (tableEnsured) return
    synchronized {
      if (tableEnsured) return
      Using.resource(Meta.getConnection()) { connection =>
        Using.resource(connection.prepareStatement("CREATE TABLE IF NOT EXISTS " + AIRPORT_ASSET_TABLE + "(" +
          "id INTEGER PRIMARY KEY AUTO_INCREMENT, " +
          "airport INTEGER, " +
          "airline INTEGER, " +
          "asset_type VARCHAR(64) NOT NULL, " +
          "level INTEGER, " +
          "status VARCHAR(32) NOT NULL, " +
          "completion_cycle INTEGER, " +
          "KEY " + AIRPORT_ASSET_INDEX_1 + " (airport), " +
          "KEY " + AIRPORT_ASSET_INDEX_2 + " (airline), " +
          "FOREIGN KEY(airport) REFERENCES " + AIRPORT_TABLE + "(id) ON DELETE CASCADE ON UPDATE CASCADE, " +
          "FOREIGN KEY(airline) REFERENCES " + AIRLINE_TABLE + "(id) ON DELETE CASCADE ON UPDATE CASCADE)")) { statement =>
          statement.execute()
        }
      }
      tableEnsured = true
    }
  }

  private def rowToAsset(airport : Airport,
                         airlineId : Int,
                         assetTypeId : String,
                         level : Int,
                         statusName : String,
                         completionCycle : Int,
                         id : Int) : Option[AirportAsset] = {
    AirportAssetType.fromId(assetTypeId).map { assetType =>
      val airline = AirlineCache.getAirline(airlineId).getOrElse(Airline.fromId(airlineId))
      AirportAsset(airline, airport, assetType, level, AirportAssetStatus.withName(statusName), completionCycle, id)
    }
  }

  /** Load assets for one airport, reusing an already-resolved Airport (used during airport load). */
  def loadAirportAssetsByAirport(airport : Airport) : List[AirportAsset] = {
    ensureTable()
    Using.resource(Meta.getConnection()) { connection =>
      Using.resource(connection.prepareStatement("SELECT * FROM " + AIRPORT_ASSET_TABLE + " WHERE airport = ?")) { statement =>
        statement.setInt(1, airport.id)
        Using.resource(statement.executeQuery()) { rs =>
          val result = ListBuffer[AirportAsset]()
          while (rs.next()) {
            rowToAsset(airport, rs.getInt("airline"), rs.getString("asset_type"), rs.getInt("level"),
              rs.getString("status"), rs.getInt("completion_cycle"), rs.getInt("id")).foreach(result += _)
          }
          result.toList
        }
      }
    }
  }

  def loadAirportAssetsByAirline(airlineId : Int) : List[AirportAsset] =
    loadByCriteria(List(("airline", airlineId)))

  def loadAirportAssetById(id : Int) : Option[AirportAsset] =
    loadByCriteria(List(("id", id))).headOption

  /** All assets, for the per-cycle simulation phase. Resolves each asset's airport via the cache. */
  def loadAllAirportAssets() : List[AirportAsset] = loadByCriteria(List.empty)

  private def loadByCriteria(criteria : List[(String, Any)]) : List[AirportAsset] = {
    ensureTable()
    Using.resource(Meta.getConnection()) { connection =>
      var queryString = "SELECT * FROM " + AIRPORT_ASSET_TABLE
      if (criteria.nonEmpty) {
        queryString += " WHERE " + criteria.map(_._1 + " = ?").mkString(" AND ")
      }
      Using.resource(connection.prepareStatement(queryString)) { statement =>
        criteria.zipWithIndex.foreach { case ((_, value), index) => statement.setObject(index + 1, value) }
        Using.resource(statement.executeQuery()) { rs =>
          val result = ListBuffer[AirportAsset]()
          while (rs.next()) {
            val airportId = rs.getInt("airport")
            AirportCache.getAirport(airportId, false).foreach { airport =>
              rowToAsset(airport, rs.getInt("airline"), rs.getString("asset_type"), rs.getInt("level"),
                rs.getString("status"), rs.getInt("completion_cycle"), rs.getInt("id")).foreach(result += _)
            }
          }
          result.toList
        }
      }
    }
  }

  /** Insert a new asset, returning it with the generated id. */
  def saveAirportAsset(asset : AirportAsset) : AirportAsset = {
    ensureTable()
    Using.resource(Meta.getConnection()) { connection =>
      Using.resource(connection.prepareStatement("INSERT INTO " + AIRPORT_ASSET_TABLE +
        "(airport, airline, asset_type, level, status, completion_cycle) VALUES(?, ?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) { statement =>
        statement.setInt(1, asset.airport.id)
        statement.setInt(2, asset.airline.id)
        statement.setString(3, asset.assetType.id)
        statement.setInt(4, asset.level)
        statement.setString(5, asset.status.toString)
        statement.setInt(6, asset.completionCycle)
        statement.executeUpdate()
        val generatedKeys = statement.getGeneratedKeys
        if (generatedKeys.next()) {
          asset.id = generatedKeys.getInt(1)
        }
      }
      invalidate(asset)
      asset
    }
  }

  /** Update mutable fields (level, status, completion cycle) of an existing asset. */
  def updateAirportAsset(asset : AirportAsset) : Unit = {
    ensureTable()
    Using.resource(Meta.getConnection()) { connection =>
      Using.resource(connection.prepareStatement("UPDATE " + AIRPORT_ASSET_TABLE +
        " SET level = ?, status = ?, completion_cycle = ? WHERE id = ?")) { statement =>
        statement.setInt(1, asset.level)
        statement.setString(2, asset.status.toString)
        statement.setInt(3, asset.completionCycle)
        statement.setInt(4, asset.id)
        statement.executeUpdate()
      }
      invalidate(asset)
    }
  }

  def deleteAirportAsset(asset : AirportAsset) : Unit = {
    Using.resource(Meta.getConnection()) { connection =>
      Using.resource(connection.prepareStatement("DELETE FROM " + AIRPORT_ASSET_TABLE + " WHERE id = ?")) { statement =>
        statement.setInt(1, asset.id)
        statement.executeUpdate()
      }
      invalidate(asset)
    }
  }

  private def invalidate(asset : AirportAsset) : Unit = {
    AirlineCache.invalidateAirline(asset.airline.id)
    AirportCache.refreshAirport(asset.airport.id)
  }
}
