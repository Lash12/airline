package com.patson.model

case class WorldNewsItem(message : String, cycle : Int, targetId : Option[String] = None, var id : Int = 0)
