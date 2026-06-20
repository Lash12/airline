package push

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration

class PushConfigSpec extends AnyWordSpec with Matchers {

  def configFrom(raw: String): Configuration = Configuration(ConfigFactory.parseString(raw))

  "PushConfig.from" should {
    "default adminAirlineId to None when unset" in {
      PushConfig.from(configFrom("")).adminAirlineId shouldBe None
    }

    "parse adminAirlineId when set" in {
      PushConfig.from(configFrom("solo.push.adminAirlineId = 34")).adminAirlineId shouldBe Some(34)
    }
  }

  "PushConfig.isAdmin" should {
    "be false when adminAirlineId is unset" in {
      val config = PushConfig.from(configFrom(""))
      PushConfig.isAdmin(34, config) shouldBe false
    }

    "be true only for the configured admin airline id" in {
      val config = PushConfig.from(configFrom("solo.push.adminAirlineId = 34"))
      PushConfig.isAdmin(34, config) shouldBe true
      PushConfig.isAdmin(35, config) shouldBe false
    }
  }
}
