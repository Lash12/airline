package com.patson

import com.patson.model.{AirlineMilestones, Milestone, MilestoneCondition}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

/**
 * Phase G: milestone achievement emission logic. Tests the pure decision helper
 * `AirlineMilestones.milestoneNotificationsToEmit` (no DB), which the simulation uses to
 * decide which milestone tiers should fire a one-time achievement notification.
 */
class AirlineMilestoneSpec extends AnyWordSpecLike with Matchers {
  // Two-tier milestone (highest threshold first, as in the real definitions).
  val milestone = Milestone("MILESTONE_DESTINATIONS", "Destinations",
    List(MilestoneCondition(150, 60), MilestoneCondition(50, 30)))

  val nothingAchieved: Long => Boolean = _ => false

  "milestoneNotificationsToEmit".must {
    "emit nothing when tracking is off (progression disabled or NPC airline)".in {
      AirlineMilestones.milestoneNotificationsToEmit(track = false, milestone, 9999, nothingAchieved) shouldBe empty
    }

    "emit nothing when the value is below every threshold".in {
      AirlineMilestones.milestoneNotificationsToEmit(track = true, milestone, 30, nothingAchieved) shouldBe empty
    }

    "emit only the tiers met when crossing the lower tier".in {
      val emitted = AirlineMilestones.milestoneNotificationsToEmit(track = true, milestone, 60, nothingAchieved)
      emitted.map(_.threshold) should contain theSameElementsAs List(50L)
    }

    "emit all met tiers when jumping past both at once".in {
      val emitted = AirlineMilestones.milestoneNotificationsToEmit(track = true, milestone, 200, nothingAchieved)
      emitted.map(_.threshold) should contain theSameElementsAs List(150L, 50L)
    }

    "not re-emit a tier already achieved (idempotent)".in {
      val only50Done: Long => Boolean = t => t == 50L
      val emitted = AirlineMilestones.milestoneNotificationsToEmit(track = true, milestone, 200, only50Done)
      emitted.map(_.threshold) should contain theSameElementsAs List(150L)
    }

    "emit nothing once every met tier is already achieved".in {
      val allDone: Long => Boolean = _ => true
      AirlineMilestones.milestoneNotificationsToEmit(track = true, milestone, 200, allDone) shouldBe empty
    }
  }
}
