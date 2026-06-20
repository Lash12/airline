package com.patson.data

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class WorldNewsSourceSpec extends AnyWordSpecLike with Matchers {

  "WorldNewsSource.isRead" should {

    "treat an item at or below the watermark as read" in {
      WorldNewsSource.isRead(5, 5) shouldBe true
      WorldNewsSource.isRead(3, 5) shouldBe true
    }

    "treat an item above the watermark as unread" in {
      WorldNewsSource.isRead(6, 5) shouldBe false
    }

    "treat everything as unread when the watermark is 0 (no row yet)" in {
      WorldNewsSource.isRead(1, 0) shouldBe false
    }
  }
}
