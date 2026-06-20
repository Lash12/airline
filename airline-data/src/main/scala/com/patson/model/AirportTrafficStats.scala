package com.patson.model

/** Pure aggregation of link_statistics arrival rows into player-facing traffic analytics. */
object AirportTrafficStats {
  case class RouteRow(airportId : Int, totalPax : Int, terminatingPax : Int, connectingPax : Int, premiumPax : Int) {
    def transferShare : Double = if (totalPax <= 0) 0.0 else connectingPax.toDouble / totalPax
  }
  case class Summary(totalPax : Int, terminatingPax : Int, connectingPax : Int, premiumPax : Int) {
    def transferShare : Double = if (totalPax <= 0) 0.0 else connectingPax.toDouble / totalPax
  }

  /** Group arrival LinkStatistics (to_airport = X) by origin airport. isDestination => terminating. */
  def arrivalsByOrigin(arrivals : List[LinkStatistics]) : List[RouteRow] =
    arrivals.groupBy(_.key.fromAirport.id).map { case (originId, rows) =>
      val total = rows.map(_.passengers).sum
      val terminating = rows.filter(_.key.isDestination).map(_.passengers).sum
      val premium = rows.map(_.premiumPax).sum
      RouteRow(originId, total, terminating, total - terminating, premium)
    }.toList

  def summary(arrivals : List[LinkStatistics]) : Summary = {
    val total = arrivals.map(_.passengers).sum
    val terminating = arrivals.filter(_.key.isDestination).map(_.passengers).sum
    val premium = arrivals.map(_.premiumPax).sum
    Summary(total, terminating, total - terminating, premium)
  }
}
