package com.patson.model.media

case class WikimediaResource(resourceId : Int, resourceType : ResourceType.Value, url : String, maxAgeDeadline : Option[Long])

object ResourceType extends Enumeration {
  val CITY_IMAGE, AIRPORT_IMAGE = Value
}
