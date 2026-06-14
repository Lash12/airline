package com.patson

import com.patson.data.{AirlineSource, NotificationSource, SoloConfig}
import com.patson.model.{Notification, NotificationCategory, NonPlayerAirline}

/**
  * World news feed (single-player). A thin helper that records ambient world events
  * (NPC route changes, etc.) as WORLD_NEWS notifications — one row per player airline so
  * each keeps its own read state — to be browsed in a dedicated News panel, separate from
  * the personal notification bell.
  *
  * No-op unless solo.news.enabled, so default/multiplayer deploys are byte-identical.
  * Reuses the existing notification store (no schema change); it is subject to the normal
  * 100-row retention purge, which is fine for a rolling feed.
  */
object WorldNews {
  /**
    * Player airlines (everything that isn't a computer-controlled carrier). Cheap — the
    * airline table is on the order of tens of rows. Returns Nil when the feed is off so
    * callers pay nothing in default deploys.
    */
  def playerAirlineIds() : List[Int] =
    if (!SoloConfig.newsEnabled) Nil
    else AirlineSource.loadAllAirlines(false).filterNot(_.airlineType == NonPlayerAirline).map(_.id)

  /**
    * Post one news item, visible to each given player airline. Pass the ids from
    * playerAirlineIds() (resolve once per cycle and reuse across many posts).
    */
  def post(playerAirlineIds : Seq[Int], message : String, cycle : Int, targetId : Option[String] = None) : Unit = {
    if (!SoloConfig.newsEnabled || playerAirlineIds.isEmpty) return
    val items = playerAirlineIds.map { id =>
      Notification(airlineId = id, category = NotificationCategory.WORLD_NEWS, message = message, cycle = cycle, targetId = targetId)
    }.toList
    NotificationSource.insertNotificationsBulk(items)
  }
}
