package com.patson.data

/**
  * Ordered manifest of SQL migration files under `db/migration` on the classpath
  * (physically at `airline-data/src/main/resources/db/migration`).
  *
  * Listing classpath resources inside a directory is unreliable once packaged into a jar,
  * so the run order is this explicit list rather than a filesystem scan. To add a migration:
  * drop a new `Vn__description.sql` file in that folder and append its filename here, in order.
  * Never edit or remove an already-applied entry - SchemaPatchRunner records applied filenames
  * by name, so changing a filename or its contents after it has shipped will not be re-applied
  * on databases that already ran it.
  */
object SchemaMigrations {
  val ordered: Seq[String] = Seq(
    "V1__link_consumption_airport_pair_index.sql"
  )
}
