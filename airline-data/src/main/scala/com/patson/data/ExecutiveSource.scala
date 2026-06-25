package com.patson.data

import com.patson.data.Constants._
import com.patson.model.{Executive, ExecutiveRole}
import com.patson.util.AirlineCache

import java.sql.{Connection, ResultSet, Statement}
import scala.collection.mutable.ListBuffer
import scala.util.Using

/**
  * Persistence for the C-suite roster (see [[com.patson.model.Executive]]). Follows the self-creating
  * `ensureTable()` convention used by the other single-player sources (e.g. PushSubscriptionSource):
  * a brand-new table with no ordering dependency, so it does not need the SchemaPatchRunner migration
  * path (that one is for altering tables that already exist on live databases).
  *
  * Phase 0: CRUD only — nothing in the sim reads or writes this yet beyond the read-only web panel.
  */
object ExecutiveSource {
  @volatile private var tableEnsured = false

  private def ensureTable(connection : Connection) : Unit = {
    if (!tableEnsured) {
      Using.resource(connection.prepareStatement(
        s"""CREATE TABLE IF NOT EXISTS $EXECUTIVE_TABLE(
           |id INTEGER PRIMARY KEY AUTO_INCREMENT,
           |airline INTEGER NOT NULL,
           |role INTEGER NOT NULL,
           |level INTEGER NOT NULL DEFAULT 1,
           |xp INTEGER NOT NULL DEFAULT 0,
           |trait_key VARCHAR(32) DEFAULT NULL,
           |hired_cycle INTEGER NOT NULL,
           |salary INTEGER NOT NULL DEFAULT 0,
           |UNIQUE KEY executive_airline_role_uq(airline, role),
           |INDEX executive_airline_idx(airline),
           |FOREIGN KEY(airline) REFERENCES $AIRLINE_TABLE(id) ON DELETE CASCADE ON UPDATE CASCADE
           |)""".stripMargin)) { _.execute() }
      tableEnsured = true
    }
  }

  private def rowToExecutive(rs : ResultSet) : Executive =
    Executive(
      airline = AirlineCache.getAirline(rs.getInt("airline")).get,
      role = ExecutiveRole(rs.getInt("role")),
      level = rs.getInt("level"),
      xp = rs.getInt("xp"),
      hiredCycle = rs.getInt("hired_cycle"),
      salary = rs.getInt("salary"),
      traitKey = Option(rs.getString("trait_key")),
      id = rs.getInt("id")
    )

  def loadByAirline(airlineId : Int) : List[Executive] = {
    Using.resource(Meta.getConnection()) { connection =>
      ensureTable(connection)
      Using.resource(connection.prepareStatement(s"SELECT * FROM $EXECUTIVE_TABLE WHERE airline = ? ORDER BY role")) { statement =>
        statement.setInt(1, airlineId)
        Using.resource(statement.executeQuery()) { rs =>
          val rows = ListBuffer[Executive]()
          while (rs.next()) rows += rowToExecutive(rs)
          rows.toList
        }
      }
    }
  }

  /** Insert a new exec or update the existing one in the same (airline, role) seat. Returns the row id. */
  def save(executive : Executive) : Int = {
    Using.resource(Meta.getConnection()) { connection =>
      ensureTable(connection)
      Using.resource(connection.prepareStatement(
        s"""INSERT INTO $EXECUTIVE_TABLE(airline, role, level, xp, trait_key, hired_cycle, salary)
           |VALUES(?, ?, ?, ?, ?, ?, ?)
           |ON DUPLICATE KEY UPDATE
           |level = VALUES(level),
           |xp = VALUES(xp),
           |trait_key = VALUES(trait_key),
           |salary = VALUES(salary)""".stripMargin,
        Statement.RETURN_GENERATED_KEYS)) { statement =>
        statement.setInt(1, executive.airline.id)
        statement.setInt(2, executive.role.id)
        statement.setInt(3, executive.level)
        statement.setInt(4, executive.xp)
        executive.traitKey match {
          case Some(value) => statement.setString(5, value.take(32))
          case None => statement.setNull(5, java.sql.Types.VARCHAR)
        }
        statement.setInt(6, executive.hiredCycle)
        statement.setInt(7, executive.salary)
        statement.executeUpdate()
        Using.resource(statement.getGeneratedKeys) { keys =>
          if (keys.next()) {
            executive.id = keys.getInt(1)
          }
        }
        executive.id
      }
    }
  }

  def delete(executiveId : Int) : Int = {
    Using.resource(Meta.getConnection()) { connection =>
      ensureTable(connection)
      Using.resource(connection.prepareStatement(s"DELETE FROM $EXECUTIVE_TABLE WHERE id = ?")) { statement =>
        statement.setInt(1, executiveId)
        statement.executeUpdate()
      }
    }
  }

  def deleteByAirlineAndRole(airlineId : Int, role : ExecutiveRole.Value) : Int = {
    Using.resource(Meta.getConnection()) { connection =>
      ensureTable(connection)
      Using.resource(connection.prepareStatement(s"DELETE FROM $EXECUTIVE_TABLE WHERE airline = ? AND role = ?")) { statement =>
        statement.setInt(1, airlineId)
        statement.setInt(2, role.id)
        statement.executeUpdate()
      }
    }
  }
}
