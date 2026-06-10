package com.patson.data

import java.sql.Connection
import scala.util.Using

/**
  * Records the last time any player was active. Written by the web app
  * (login + periodic touch while websocket sessions are connected) and read by
  * the simulation's pause-when-idle check (see MainSimulation).
  *
  * The table is created lazily so no migration is required on existing databases.
  */
object HeartbeatSource {
  val ACTIVITY_HEARTBEAT_TABLE = "activity_heartbeat"

  @volatile private var tableEnsured = false

  private def ensureTable(connection : Connection) : Unit = {
    if (!tableEnsured) {
      Using.resource(connection.prepareStatement("CREATE TABLE IF NOT EXISTS " + ACTIVITY_HEARTBEAT_TABLE + "(id INTEGER PRIMARY KEY, last_active TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)")) { statement =>
        statement.execute()
      }
      tableEnsured = true
    }
  }

  def touch() : Unit = {
    Using.resource(Meta.getConnection()) { connection =>
      ensureTable(connection)
      Using.resource(connection.prepareStatement("INSERT INTO " + ACTIVITY_HEARTBEAT_TABLE + "(id, last_active) VALUES(1, CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE last_active = CURRENT_TIMESTAMP")) { statement =>
        statement.executeUpdate()
      }
    }
  }

  def lastActiveMillis() : Option[Long] = {
    Using.resource(Meta.getConnection()) { connection =>
      ensureTable(connection)
      Using.resource(connection.prepareStatement("SELECT last_active FROM " + ACTIVITY_HEARTBEAT_TABLE + " WHERE id = 1")) { statement =>
        Using.resource(statement.executeQuery()) { resultSet =>
          if (resultSet.next()) Some(resultSet.getTimestamp("last_active").getTime) else None
        }
      }
    }
  }
}
