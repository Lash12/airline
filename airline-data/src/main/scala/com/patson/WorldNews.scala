package com.patson

import com.patson.data.{SoloConfig, WorldNewsSource}
import com.patson.model.WorldNewsItem

/**
  * World news feed (single-player). A thin helper that records ambient world events
  * (NPC route changes, etc.) to be browsed in a dedicated News panel, separate from the
  * personal notification bell.
  *
  * Broadcast content, not a personalized event: one row is written per event regardless of
  * how many airlines exist, and each airline tracks its own read position via a lightweight
  * watermark (mirroring the push-subscription watermark pattern) instead of one notification
  * row per recipient. See WorldNewsSource for the storage.
  *
  * No-op unless solo.news.enabled, so default/multiplayer deploys are byte-identical.
  */
object WorldNews {
  def post(message : String, cycle : Int, targetId : Option[String] = None) : Unit = {
    if (!SoloConfig.newsEnabled) return
    WorldNewsSource.insert(WorldNewsItem(message, cycle, targetId))
  }
}
