package com.patson.init

import com.patson.data.{Meta, SchemaPatchRunner}

/**
 * The main flow to initialize everything
 */
object MainInit extends App {
  Meta.createSchema()
  SchemaPatchRunner.run()
  GeoDataGenerator.main()
  AirplaneModelInitializer.populateAirplaneModels()
  AirlineGenerator.mainFlow()
}