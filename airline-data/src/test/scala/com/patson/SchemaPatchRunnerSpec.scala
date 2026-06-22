package com.patson

import com.patson.data.{Constants, Meta, SchemaMigrations, SchemaPatchRunner}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.util.Using

/**
 * Smoke test against a real MySQL instance (CI's `mysql` service, see ci.yml) proving the
 * migration runner applies every entry in SchemaMigrations.ordered and is safe to call
 * repeatedly within one process (the @volatile guard).
 *
 * Cross-process idempotency (MainInit, MainSimulation and the web Module each call run()
 * independently against the same database) is additionally exercised by ci.yml itself: the
 * "Initialize test database" step runs MainInit - which calls SchemaPatchRunner.run() in its
 * own JVM - before this spec runs in a separate sbt invocation and finds every migration
 * already recorded.
 */
class SchemaPatchRunnerSpec extends AnyWordSpecLike with Matchers {

  private def appliedFilenames() : Set[String] = {
    Using.resource(Meta.getConnection()) { connection =>
      Using.resource(connection.prepareStatement("SELECT filename FROM " + Constants.SCHEMA_PATCH_TABLE)) { statement =>
        Using.resource(statement.executeQuery()) { resultSet =>
          val buffer = scala.collection.mutable.Set[String]()
          while (resultSet.next()) {
            buffer += resultSet.getString("filename")
          }
          buffer.toSet
        }
      }
    }
  }

  "SchemaPatchRunner.run" should {
    "apply every migration in SchemaMigrations.ordered and record it" in {
      SchemaPatchRunner.run()
      appliedFilenames() shouldBe SchemaMigrations.ordered.toSet
    }

    "be idempotent when called again in the same process" in {
      noException should be thrownBy SchemaPatchRunner.run()
      appliedFilenames() shouldBe SchemaMigrations.ordered.toSet
    }

    "have already recorded every migration before this spec runs (MainInit ran it in its own JVM)" in {
      // ci.yml's "Initialize test database" step runs MainInit, which calls
      // SchemaPatchRunner.run() before this spec's sbt invocation even starts - proving the
      // tracking table (not the in-process @volatile guard) is what makes a second process
      // safe to call run() again.
      appliedFilenames() shouldBe SchemaMigrations.ordered.toSet
    }
  }
}
