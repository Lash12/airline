package com.patson.model

case class PushSubscription(
  airlineId: Int,
  endpoint: String,
  p256dhKey: String,
  authKey: String,
  createdCycle: Int,
  var lastPushedNotificationId: Int = 0,
  var failureCount: Int = 0,
  userAgent: Option[String] = None,
  var id: Int = 0
)
