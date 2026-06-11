package com.patson.data

import java.sql.Connection
import scala.util.Using

/**
  * Player-adjustable simulation pacing. Written by the web app and read by the
  * simulation scheduler (see MainSimulation): `cycle_minutes` is the target
  * wall-clock length of one game week, and `fast_forward` is a count of extra
  * cycles to run back-to-back without waiting for the deadline.
  *
  * The table is created lazily so no migration is required on existing databases.
  */
object SimControlSource {
  val SIM_CONTROL_TABLE = "sim_control"

  @volatile private var tableEnsured = false

  private def ensureTable(connection : Connection) : Unit = {
    if (!tableEnsured) {
      Using.resource(connection.prepareStatement("CREATE TABLE IF NOT EXISTS " + SIM_CONTROL_TABLE + "(id INTEGER PRIMARY KEY, cycle_minutes INTEGER NOT NULL, fast_forward INTEGER NOT NULL DEFAULT 0)")) { statement =>
        statement.execute()
      }
      tableEnsured = true
    }
  }

  def loadCycleMinutes() : Option[Int] = {
    Using.resource(Meta.getConnection()) { connection =>
      ensureTable(connection)
      Using.resource(connection.prepareStatement("SELECT cycle_minutes FROM " + SIM_CONTROL_TABLE + " WHERE id = 1")) { statement =>
        Using.resource(statement.executeQuery()) { resultSet =>
          if (resultSet.next()) Some(resultSet.getInt("cycle_minutes")) else None
        }
      }
    }
  }

  def loadFastForward() : Int = {
    Using.resource(Meta.getConnection()) { connection =>
      ensureTable(connection)
      Using.resource(connection.prepareStatement("SELECT fast_forward FROM " + SIM_CONTROL_TABLE + " WHERE id = 1")) { statement =>
        Using.resource(statement.executeQuery()) { resultSet =>
          if (resultSet.next()) resultSet.getInt("fast_forward") else 0
        }
      }
    }
  }

  def setCycleMinutes(minutes : Int) : Unit = {
    Using.resource(Meta.getConnection()) { connection =>
      ensureTable(connection)
      Using.resource(connection.prepareStatement("INSERT INTO " + SIM_CONTROL_TABLE + "(id, cycle_minutes) VALUES(1, ?) ON DUPLICATE KEY UPDATE cycle_minutes = ?")) { statement =>
        statement.setInt(1, minutes)
        statement.setInt(2, minutes)
        statement.executeUpdate()
      }
    }
  }

  def setFastForward(cycles : Int, defaultCycleMinutes : Int) : Unit = {
    Using.resource(Meta.getConnection()) { connection =>
      ensureTable(connection)
      Using.resource(connection.prepareStatement("INSERT INTO " + SIM_CONTROL_TABLE + "(id, cycle_minutes, fast_forward) VALUES(1, ?, ?) ON DUPLICATE KEY UPDATE fast_forward = ?")) { statement =>
        statement.setInt(1, defaultCycleMinutes)
        statement.setInt(2, cycles)
        statement.setInt(3, cycles)
        statement.executeUpdate()
      }
    }
  }

  /**
    * Atomically consumes one pending fast-forward cycle.
    * @return true if one was consumed (caller should run a cycle immediately)
    */
  def consumeFastForward() : Boolean = {
    Using.resource(Meta.getConnection()) { connection =>
      ensureTable(connection)
      Using.resource(connection.prepareStatement("UPDATE " + SIM_CONTROL_TABLE + " SET fast_forward = fast_forward - 1 WHERE id = 1 AND fast_forward > 0")) { statement =>
        statement.executeUpdate() == 1
      }
    }
  }
}
