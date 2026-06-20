package com.patson.data

import com.patson.data.Constants._
import com.patson.model.WorldNewsItem

import java.sql.{Connection, ResultSet, Statement}
import scala.collection.mutable.ListBuffer
import scala.util.Using

object WorldNewsSource {
  @volatile private var tableEnsured = false

  private def ensureTables(connection : Connection): Unit = {
    if (!tableEnsured) {
      Using.resource(connection.prepareStatement(
        s"""CREATE TABLE IF NOT EXISTS $WORLD_NEWS_TABLE(
           |id INTEGER PRIMARY KEY AUTO_INCREMENT,
           |message VARCHAR(512) NOT NULL,
           |cycle INTEGER NOT NULL,
           |target_id VARCHAR(256) DEFAULT NULL
           |)""".stripMargin)) { _.execute() }
      Using.resource(connection.prepareStatement(
        s"""CREATE TABLE IF NOT EXISTS $WORLD_NEWS_WATERMARK_TABLE(
           |airline INTEGER PRIMARY KEY,
           |last_seen_id INTEGER NOT NULL DEFAULT 0,
           |FOREIGN KEY(airline) REFERENCES $AIRLINE_TABLE(id) ON DELETE CASCADE ON UPDATE CASCADE
           |)""".stripMargin)) { _.execute() }
      tableEnsured = true
    }
  }

  private def rowToItem(rs : ResultSet) : WorldNewsItem =
    WorldNewsItem(rs.getString("message"), rs.getInt("cycle"), Option(rs.getString("target_id")), rs.getInt("id"))

  def insert(item : WorldNewsItem) : WorldNewsItem = {
    Using.resource(Meta.getConnection()) { connection =>
      ensureTables(connection)
      Using.resource(connection.prepareStatement(
        s"INSERT INTO $WORLD_NEWS_TABLE (message, cycle, target_id) VALUES (?, ?, ?)",
        Statement.RETURN_GENERATED_KEYS)) { statement =>
        statement.setString(1, item.message)
        statement.setInt(2, item.cycle)
        item.targetId match {
          case Some(value) => statement.setString(3, value)
          case None => statement.setNull(3, java.sql.Types.VARCHAR)
        }
        statement.executeUpdate()
        val generatedKeys = statement.getGeneratedKeys
        if (generatedKeys.next()) {
          item.id = generatedKeys.getInt(1)
        }
      }
      item
    }
  }

  def latestId() : Int = {
    Using.resource(Meta.getConnection()) { connection =>
      ensureTables(connection)
      Using.resource(connection.prepareStatement(s"SELECT COALESCE(MAX(id), 0) FROM $WORLD_NEWS_TABLE")) { statement =>
        Using.resource(statement.executeQuery()) { rs =>
          if (rs.next()) rs.getInt(1) else 0
        }
      }
    }
  }

  def loadRecent(limit : Int = 50) : List[WorldNewsItem] = {
    Using.resource(Meta.getConnection()) { connection =>
      ensureTables(connection)
      Using.resource(connection.prepareStatement(s"SELECT * FROM $WORLD_NEWS_TABLE ORDER BY id DESC LIMIT ?")) { statement =>
        statement.setInt(1, limit)
        Using.resource(statement.executeQuery()) { rs =>
          val rows = ListBuffer[WorldNewsItem]()
          while (rs.next()) rows += rowToItem(rs)
          rows.toList
        }
      }
    }
  }

  /**
    * Lazily creates a watermark defaulting to "caught up as of right now" — a brand new
    * watermark must not start at 0, or the airline's first News check would show every
    * historical item as unread (same lesson already learned for push subscriptions).
    */
  def getOrInitWatermark(airlineId : Int) : Int = {
    Using.resource(Meta.getConnection()) { connection =>
      ensureTables(connection)
      val existing = Using.resource(connection.prepareStatement(
        s"SELECT last_seen_id FROM $WORLD_NEWS_WATERMARK_TABLE WHERE airline = ?")) { statement =>
        statement.setInt(1, airlineId)
        Using.resource(statement.executeQuery()) { rs =>
          if (rs.next()) Some(rs.getInt(1)) else None
        }
      }
      existing match {
        case Some(value) => value
        case None =>
          val current = latestId()
          Using.resource(connection.prepareStatement(
            s"INSERT INTO $WORLD_NEWS_WATERMARK_TABLE (airline, last_seen_id) VALUES (?, ?)")) { statement =>
            statement.setInt(1, airlineId)
            statement.setInt(2, current)
            statement.executeUpdate()
          }
          current
      }
    }
  }

  def markSeen(airlineId : Int, upToId : Int) : Unit = {
    getOrInitWatermark(airlineId) // ensure the row exists before updating it
    Using.resource(Meta.getConnection()) { connection =>
      Using.resource(connection.prepareStatement(
        s"UPDATE $WORLD_NEWS_WATERMARK_TABLE SET last_seen_id = GREATEST(last_seen_id, ?) WHERE airline = ?")) { statement =>
        statement.setInt(1, upToId)
        statement.setInt(2, airlineId)
        statement.executeUpdate()
      }
    }
  }

  /** Pure: whether an item is already-seen given a watermark. Split out so it's testable without a DB. */
  def isRead(itemId : Int, watermark : Int) : Boolean = itemId <= watermark
}
