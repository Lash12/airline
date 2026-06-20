# Airline

An open-source airline simulation game (forked from the original `airline-club` repo), now maintained and
deployed as a working single-player/ops-oriented codebase.

Current state snapshot: **2026-06-20**

Repository structure
--------------------

- `airline-data`: simulation engine, DB schema/migrations, and single-player feature logic.
- `airline-web`: Play web app, REST/API layer, and browser-side assets.
- `e2e`: Playwright tests used for deployment smoke validation.
- `.docker`: container entrypoint and MySQL tuning scripts.
- `scripts`: deployment scripts and OptiPlex workflows.
- `docs`: architecture/status roadmaps and runbooks.

Quick start (local development)
-------------------------------

### 1) Prerequisites

- Java 17 (local runtime target)
- Scala 2.13.18
- sbt
- MySQL 8
- Node.js + npm (for `e2e` tests)

### 2) Build + local boot

1. Update DB credentials in:
   - `airline-data/src/main/resources/application.conf`
   - `airline-web/conf/application.conf`
2. Build and publish local data artifacts:
   - `cd airline-data`
   - `sbt publishLocal`
3. Initialize DB on fresh installs:
   - `sbt "runMain com.patson.init.MainInit"`
4. Start simulation and web in separate terminals:
   - Terminal A: `cd airline-data && sbt "runMain com.patson.MainSimulation"`
   - Terminal B: `cd airline-web && sbt run`
5. Open: [http://localhost:9000](http://localhost:9000)

### 3) Quick verification

- App routes and API smoke checks should be run via Playwright:
  - `cd e2e`
  - `npm ci`
  - `npm test`

Deployment / Operations
-----------------------

### Small-server profile (recommended for LAN/single-box)

1. Start containers:
   - `docker compose -f docker-compose.small.yaml up -d`
2. First run database init:
   - `docker exec -it airline-app sh /home/airline/init-data.sh`
3. Start/run both JVMs + guardrails:
   - `scripts/optiplex-deploy.sh`

`scripts/optiplex-deploy.sh` is the canonical deploy path in this repo because it:

- Verifies MySQL mounts use Bitnami path `/bitnami/mysql`.
- Checks container health and supervisor-managed JVM presence.
- Initializes DB only on first boot.
- Waits for HTTP readiness on `:9000`.

### Important constraints

- Small-server persistence is mounted at `/bitnami/mysql` in this deployment path.
- `docker-compose.yaml` is not the preferred single-player entrypoint profile.
- Avoid destructive DB volume operations without a backup.

### CI/CD ops references

- `.github/workflows/optiplex-deploy.yml` (self-hosted deploy + e2e verification)
- `.github/workflows/validate-web-push.yml` (production push sender validation)

Testing
-------

- Data module: `cd airline-data && sbt test`
- Web module: `cd airline-web && sbt test`
- Front-end JS tests: `cd airline-web && npm test`
- End-to-end: `cd e2e && npm test`
- CI compile/check flow: `npm --prefix e2e run test:list`

Configuration and feature highlights
----------------------------------

- Single-player behavior is feature-gated by `solo.*` flags in config (consultant, AI growth, news, push, etc.).
- AI routes can now include growth/pricing/fleet-oriented behavior under solo gates.
- Web push is implemented and configured under `solo.push.*` keys; HTTPS is required for production flow.
- JVM/DB tuning guidance lives in:
  - [SMALL_SERVER.md](SMALL_SERVER.md)
  - [HIKARI_TUNING.md](HIKARI_TUNING.md)
- Cache observability:
  - [OBSERVABILITY.md](OBSERVABILITY.md)

Reference docs
--------------

- [docs/current-development-state.md](docs/current-development-state.md)
- [docs/optiplex-ci-plan.md](docs/optiplex-ci-plan.md)
- [docs/single-player-performance-roadmap.md](docs/single-player-performance-roadmap.md)
- [docs/ai-growth-plan.md](docs/ai-growth-plan.md)
- [docs/web-push-notifications-plan.md](docs/web-push-notifications-plan.md)

Attribution
-----------

Icons by [Yusuke Kamiyamane](http://p.yusukekamiyamane.com/) under
[Creative Commons Attribution 3.0](http://creativecommons.org/licenses/by/3.0/).
