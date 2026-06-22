# Database Migrations

Versioned migration runner for schema changes that must apply exactly once, in a fixed order,
against a database that may already be live (not just a fresh DB). This sits alongside, and
does not replace, the existing `ensureTable()` self-create pattern.

## Two mechanisms, two purposes

| Mechanism | Use for | Lives in |
|---|---|---|
| `ensureTable()` (`CREATE TABLE IF NOT EXISTS`, per-source `@volatile` guard) | A brand new table with no ordering dependency on other DDL. See `HeartbeatSource.scala`, `PushSubscriptionSource.scala`, `AirportAssetSource.scala`, `WorldNewsSource.scala`, `SimControlSource.scala`. | Each `data/*Source.scala` file, called lazily at the call site that needs the table. |
| `SchemaPatchRunner` | A schema change that already-existing live databases need but cannot express as a single idempotent `CREATE TABLE IF NOT EXISTS` — e.g. an index or column on a table that may or may not already have it, multi-statement DDL, or anything order-sensitive relative to another migration. | `airline-data/src/main/scala/com/patson/data/SchemaPatchRunner.scala` + `SchemaMigrations.scala`, run once at process startup. |

`Meta.createSchema()` is unrelated to both: it does a full `DROP TABLE IF EXISTS` + recreate of
every table and is **only** ever invoked by `com.patson.init.MainInit` (fresh-DB bootstrap, also
what CI's "Initialize test database" step runs). It must never run against a live database.

## Where migrations live

- SQL files: `airline-data/src/main/resources/db/migration/Vn__description.sql` (packaged onto
  the classpath, so this works the same whether running from `sbt run`, a `publishLocal`'d jar,
  or the staged Docker image).
- Manifest: `airline-data/src/main/scala/com/patson/data/SchemaMigrations.scala` — an explicit
  `Seq[String]` of filenames, in apply order. Listing classpath resources inside a directory is
  unreliable once packaged into a jar, so the order is this explicit list rather than a
  filesystem scan of the `db/migration` folder.
- Tracking table: `schema_patch(filename VARCHAR(255) PRIMARY KEY, applied_at TIMESTAMP)`,
  self-created by the runner the same way `ensureTable()` self-creates its tables.

## How they run

`SchemaPatchRunner.run()` is called at startup from three places, each its own process:

- `com.patson.MainSimulation` (the sim process)
- `airline-web`'s `Module.configure()` (the web process, via Guice on Play boot)
- `com.patson.init.MainInit` (fresh-DB bootstrap / CI)

For each filename in `SchemaMigrations.ordered`, in order: if `schema_patch` doesn't already
have a row for that filename, load the `.sql` resource, split it on `;`, execute each statement,
then record the filename as applied. Already-applied migrations are skipped entirely (no DDL
re-run), so it is safe for multiple processes to call `run()` independently against the same
database — only the first one to reach a given migration executes it; the others see it already
recorded.

**Baseline / pre-existing databases:** a migration's DDL may legitimately already be in place on
a given database before the runner ever sees it — either because `Meta.createSchema()` already
creates the same index/column on a fresh DB, or because it was applied manually on a live
database before the runner existed (this is exactly how the first migration,
`V1__link_consumption_airport_pair_index.sql`, originated — see
`airline-data/db_scripts/patch_link_consumption_airport_pair_index.sql`, the old manual,
never-automated copy-paste version it replaces). Rather than requiring a separate baseline
command, the runner treats specific MySQL "this already exists" error codes (1050 table exists,
1060 duplicate column, 1061 duplicate key name, 1091 can't drop — see
`SchemaPatchRunner.ALREADY_EXISTS_ERROR_CODES`) as success: it logs and records the migration as
applied instead of failing. This means the very first `run()` against any database — fresh,
pre-existing-with-the-change-already-applied, or pre-existing-without-it — converges to the same
recorded state without any manual step.

### Local development

Nothing to run manually. Start the web app or the sim normally; `SchemaPatchRunner.run()` fires
on startup and is a no-op (a single `SELECT`/`INSERT` per migration) once everything is applied.
To re-run a migration against a local DB from scratch, run `MainInit` (drops and recreates the
whole schema) — see `airline-data/src/main/scala/com/patson/init/MainInit.scala`.

### Production

No explicit pre-start step is required — the same startup call applies. The OptiPlex deploy
(`scripts/optiplex-deploy.sh` / `OptiPlex Deploy & Verify`, see `docs/current-development-state.md`
→ Deployment Guardrails) restarts the web and sim containers, and each one runs its pending
migrations on boot before serving traffic or running a cycle. If a migration is large enough to
want a maintenance window, run it manually first against the live DB (`mysql` CLI, see
`docs/current-development-state.md`'s SSH/DB-inspection guidance), then deploy — the runner will
see it already applied (via the baseline error-code handling above, or because you also record
it directly in `schema_patch`) and skip it.

## Adding a new migration

1. Add `airline-data/src/main/resources/db/migration/Vn__description.sql`, where `n` is one
   higher than the current highest version in `SchemaMigrations.ordered`.
2. Append the filename to `SchemaMigrations.ordered`, in order. Never edit or reorder an
   already-shipped entry — once a migration has run anywhere, its filename is permanently
   recorded in that database's `schema_patch` table; renaming it or changing its contents means
   it will not re-run where it's already applied, and a fresh database may apply a different
   version of "the same" change than a live one already has.
3. Prefer DDL that is naturally idempotent (`CREATE TABLE IF NOT EXISTS`, an
   `information_schema`-guarded `ALTER TABLE` per the existing column-migration convention) when
   it's easy to write that way. When it isn't, rely on the error-code handling above rather than
   hand-rolling existence checks per migration.
4. If the new table/column should also exist on a fresh database, add the matching DDL to
   `Meta.createSchema()` too (or to whichever `create*` helper covers that table) — the runner
   does not touch `Meta.createSchema()`'s fresh-init path, and the two must stay in sync.
5. Add or extend a `SchemaPatchRunnerSpec`-style assertion if the migration has any behavior
   worth smoke-testing beyond "appears in `schema_patch`".

## Tests

`airline-data/src/test/scala/com/patson/SchemaPatchRunnerSpec.scala` runs against a real MySQL
instance (CI's `mysql` service container, see `.github/workflows/ci.yml`) and asserts every
migration in `SchemaMigrations.ordered` gets applied and recorded, and that calling `run()`
again is a no-op. It depends on `ci.yml`'s "Initialize test database" step (which runs
`MainInit`, and therefore `SchemaPatchRunner.run()`, in its own process first) to also prove
cross-process idempotency via the tracking table rather than the in-process guard.

There is no local MySQL instance available in this environment by default, so this spec cannot
be run outside CI without first standing up a MySQL server matching
`airline-data/src/main/resources/application.conf`'s `mysqldb` block (`root` user, empty
password, schema `airline`, `localhost:3306`) — e.g. the same `mysql:8.0` Docker image CI uses.
