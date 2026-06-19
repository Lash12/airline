package com.patson.data

import com.patson.data.Constants._
import com.patson.model.PushSubscription

import java.sql.{Connection, ResultSet, Statement, Types}
import scala.collection.mutable.ListBuffer
import scala.util.Using

object PushSubscriptionSource {
  @volatile private var tableEnsured = false

  private def ensureTable(connection: Connection): Unit = {
    if (!tableEnsured) {
      Using.resource(connection.prepareStatement(
        s"""CREATE TABLE IF NOT EXISTS $PUSH_SUBSCRIPTION_TABLE(
           |id INTEGER PRIMARY KEY AUTO_INCREMENT,
           |airline INTEGER NOT NULL,
           |endpoint VARCHAR(1024) NOT NULL,
           |p256dh_key VARCHAR(256) NOT NULL,
           |auth_key VARCHAR(256) NOT NULL,
           |created_cycle INTEGER NOT NULL,
           |last_pushed_notification_id INTEGER NOT NULL DEFAULT 0,
           |failure_count INTEGER NOT NULL DEFAULT 0,
           |user_agent VARCHAR(512) DEFAULT NULL,
           |UNIQUE KEY push_subscription_endpoint_uq(endpoint),
           |INDEX push_subscription_airline_idx(airline),
           |FOREIGN KEY(airline) REFERENCES $AIRLINE_TABLE(id) ON DELETE CASCADE ON UPDATE CASCADE
           |)""".stripMargin)) { _.execute() }
      tableEnsured = true
    }
  }

  private def rowToSubscription(rs: ResultSet): PushSubscription =
    PushSubscription(
      airlineId = rs.getInt("airline"),
      endpoint = rs.getString("endpoint"),
      p256dhKey = rs.getString("p256dh_key"),
      authKey = rs.getString("auth_key"),
      createdCycle = rs.getInt("created_cycle"),
      lastPushedNotificationId = rs.getInt("last_pushed_notification_id"),
      failureCount = rs.getInt("failure_count"),
      userAgent = Option(rs.getString("user_agent")),
      id = rs.getInt("id")
    )

  def upsert(subscription: PushSubscription): PushSubscription = {
    Using.resource(Meta.getConnection()) { connection =>
      ensureTable(connection)
      Using.resource(connection.prepareStatement(
        s"""INSERT INTO $PUSH_SUBSCRIPTION_TABLE
           |(airline, endpoint, p256dh_key, auth_key, created_cycle, last_pushed_notification_id, failure_count, user_agent)
           |VALUES (?, ?, ?, ?, ?, ?, ?, ?)
           |ON DUPLICATE KEY UPDATE
           |airline = VALUES(airline),
           |p256dh_key = VALUES(p256dh_key),
           |auth_key = VALUES(auth_key),
           |failure_count = 0,
           |user_agent = VALUES(user_agent)""".stripMargin,
        Statement.RETURN_GENERATED_KEYS)) { statement =>
        statement.setInt(1, subscription.airlineId)
        statement.setString(2, subscription.endpoint)
        statement.setString(3, subscription.p256dhKey)
        statement.setString(4, subscription.authKey)
        statement.setInt(5, subscription.createdCycle)
        statement.setInt(6, subscription.lastPushedNotificationId)
        statement.setInt(7, subscription.failureCount)
        subscription.userAgent match {
          case Some(value) => statement.setString(8, value.take(512))
          case None => statement.setNull(8, Types.VARCHAR)
        }
        statement.executeUpdate()
      }
      loadByEndpoint(subscription.endpoint).getOrElse(subscription)
    }
  }

  def loadByEndpoint(endpoint: String): Option[PushSubscription] = {
    Using.resource(Meta.getConnection()) { connection =>
      ensureTable(connection)
      Using.resource(connection.prepareStatement(s"SELECT * FROM $PUSH_SUBSCRIPTION_TABLE WHERE endpoint = ?")) { statement =>
        statement.setString(1, endpoint)
        Using.resource(statement.executeQuery()) { rs =>
          if (rs.next()) Some(rowToSubscription(rs)) else None
        }
      }
    }
  }

  def loadByAirline(airlineId: Int): List[PushSubscription] = {
    Using.resource(Meta.getConnection()) { connection =>
      ensureTable(connection)
      Using.resource(connection.prepareStatement(s"SELECT * FROM $PUSH_SUBSCRIPTION_TABLE WHERE airline = ? ORDER BY id")) { statement =>
        statement.setInt(1, airlineId)
        Using.resource(statement.executeQuery()) { rs =>
          val rows = ListBuffer[PushSubscription]()
          while (rs.next()) rows += rowToSubscription(rs)
          rows.toList
        }
      }
    }
  }

  def loadAll(): List[PushSubscription] = {
    Using.resource(Meta.getConnection()) { connection =>
      ensureTable(connection)
      Using.resource(connection.prepareStatement(s"SELECT * FROM $PUSH_SUBSCRIPTION_TABLE ORDER BY id")) { statement =>
        Using.resource(statement.executeQuery()) { rs =>
          val rows = ListBuffer[PushSubscription]()
          while (rs.next()) rows += rowToSubscription(rs)
          rows.toList
        }
      }
    }
  }

  def deleteByEndpoint(airlineId: Int, endpoint: String): Int = {
    Using.resource(Meta.getConnection()) { connection =>
      ensureTable(connection)
      Using.resource(connection.prepareStatement(s"DELETE FROM $PUSH_SUBSCRIPTION_TABLE WHERE airline = ? AND endpoint = ?")) { statement =>
        statement.setInt(1, airlineId)
        statement.setString(2, endpoint)
        statement.executeUpdate()
      }
    }
  }

  def delete(subscriptionId: Int): Int = {
    Using.resource(Meta.getConnection()) { connection =>
      ensureTable(connection)
      Using.resource(connection.prepareStatement(s"DELETE FROM $PUSH_SUBSCRIPTION_TABLE WHERE id = ?")) { statement =>
        statement.setInt(1, subscriptionId)
        statement.executeUpdate()
      }
    }
  }

  def markPushed(subscriptionId: Int, notificationId: Int): Unit = {
    Using.resource(Meta.getConnection()) { connection =>
      ensureTable(connection)
      Using.resource(connection.prepareStatement(
        s"UPDATE $PUSH_SUBSCRIPTION_TABLE SET last_pushed_notification_id = GREATEST(last_pushed_notification_id, ?), failure_count = 0 WHERE id = ?")) { statement =>
        statement.setInt(1, notificationId)
        statement.setInt(2, subscriptionId)
        statement.executeUpdate()
      }
    }
  }

  def markFailure(subscriptionId: Int): Unit = {
    Using.resource(Meta.getConnection()) { connection =>
      ensureTable(connection)
      Using.resource(connection.prepareStatement(s"UPDATE $PUSH_SUBSCRIPTION_TABLE SET failure_count = failure_count + 1 WHERE id = ?")) { statement =>
        statement.setInt(1, subscriptionId)
        statement.executeUpdate()
      }
    }
  }
}
