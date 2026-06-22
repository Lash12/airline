package com.patson.data

import java.sql.{Connection, SQLException}
import scala.io.Source
import scala.util.{Try, Using}

/**
  * Minimal versioned migration runner. Does not replace the `ensureTable()` self-create
  * pattern used throughout the various `data.*Source` objects (kept as-is per existing convention - those
  * stay the right tool for "new table, no ordering/dependency on other DDL"). This runner is
  * for schema changes that must run exactly once, in a fixed order, against tables that may
  * already exist on a live database - e.g. adding an index/column to an existing table.
  *
  * Migrations are listed in `SchemaMigrations.ordered` and loaded from
  * `db/migration/<filename>` on the classpath (see `airline-data/src/main/resources/db/migration`).
  * Applied filenames are recorded in the `schema_patch` table so each migration runs at most once
  * per database. See docs/database-migrations.md for the developer-facing guide.
  */
object SchemaPatchRunner {
  import Constants.SCHEMA_PATCH_TABLE

  //MySQL error codes that mean "this DDL already happened" - safe to treat as already-applied
  //rather than fail, since some migrations target columns/indexes that may already exist on a
  //given database (e.g. a fresh DB created via Meta.createSchema, or a live DB this was already
  //applied to manually before the runner existed).
  private val ALREADY_EXISTS_ERROR_CODES = Set(1050, 1060, 1061, 1091)

  @volatile private var hasRun = false

  private def ensureTrackingTable(connection : Connection) : Unit = {
    Using.resource(connection.prepareStatement(
      "CREATE TABLE IF NOT EXISTS " + SCHEMA_PATCH_TABLE + "(" +
        "filename VARCHAR(255) PRIMARY KEY, " +
        "applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)")) { statement =>
      statement.execute()
    }
  }

  private def isApplied(connection : Connection, filename : String) : Boolean = {
    Using.resource(connection.prepareStatement("SELECT 1 FROM " + SCHEMA_PATCH_TABLE + " WHERE filename = ?")) { statement =>
      statement.setString(1, filename)
      Using.resource(statement.executeQuery()) { resultSet =>
        resultSet.next()
      }
    }
  }

  private def recordApplied(connection : Connection, filename : String) : Unit = {
    Using.resource(connection.prepareStatement("INSERT IGNORE INTO " + SCHEMA_PATCH_TABLE + "(filename) VALUES(?)")) { statement =>
      statement.setString(1, filename)
      statement.executeUpdate()
    }
  }

  private def loadSql(filename : String) : Seq[String] = {
    val resourcePath = s"/db/migration/$filename"
    val stream = getClass.getResourceAsStream(resourcePath)
    if (stream == null) {
      throw new IllegalStateException(s"Migration $filename listed in SchemaMigrations.ordered but missing from classpath at $resourcePath")
    }
    val content = Using.resource(Source.fromInputStream(stream, "UTF-8"))(_.mkString)
    content.split(";").map(_.trim).filter(_.nonEmpty)
  }

  private def applyMigration(connection : Connection, filename : String) : Unit = {
    loadSql(filename).foreach { sqlStatement =>
      Try {
        Using.resource(connection.prepareStatement(sqlStatement)) { statement =>
          statement.execute()
        }
      }.recover {
        case e : SQLException if ALREADY_EXISTS_ERROR_CODES.contains(e.getErrorCode) =>
          println(s"Migration $filename: statement already applied (${e.getMessage}), continuing")
      }.get
    }
    recordApplied(connection, filename)
    println(s"Migration $filename applied")
  }

  /**
    * Applies every migration in `SchemaMigrations.ordered` not yet recorded as applied, in order.
    * Safe to call multiple times (idempotent) and from multiple processes (web + sim both call
    * this at startup); each migration only ever executes its DDL once per database.
    */
  def run() : Unit = {
    if (!hasRun) {
      synchronized {
        if (!hasRun) {
          Using.resource(Meta.getConnection()) { connection =>
            ensureTrackingTable(connection)
            SchemaMigrations.ordered.foreach { filename =>
              if (!isApplied(connection, filename)) {
                applyMigration(connection, filename)
              }
            }
          }
          hasRun = true
        }
      }
    }
  }
}
