# Airline

Scala airline simulation with a shared `airline-data` engine and a Play-based `airline-web` UI.

## Repository layout

- `airline-data` - simulation engine, data loaders, and database access
- `airline-web` - Play application, JSON APIs, websocket/chat, and static assets
- `e2e` - Playwright smoke coverage
- `docs` - backlog and performance notes

## Local quick start

1. Start the container stack:
   ```powershell
   docker-compose up -d airline-db airline-search airline-app
   ```
2. Initialize the database inside the app container:
   ```powershell
   docker exec -it airline-app sh /home/airline/init-data.sh
   ```
3. Start the simulation engine:
   ```powershell
   docker exec -it airline-app sh /home/airline/start-data.sh
   ```
4. Start the web app:
   ```powershell
   docker exec -it airline-app sh /home/airline/start-web.sh
   ```
5. Open `http://localhost:9000`.

## Low-resource local mode

The repo now supports a lighter-weight runtime mode for local and LAN testing.

Set these environment variables before running the start scripts, or add them to a compose override:

```text
AIRLINE_LOCAL_LITE=true
AIRLINE_SEARCH_ELASTICSEARCH_ENABLED=false
AIRLINE_DB_POOL_MAX_SIZE=20
AIRLINE_SIMULATION_PARALLELISM=2
AIRLINE_LOG_CYCLE_TIMINGS=true
```

Effects:

- smaller JVM defaults in `.docker` startup scripts
- lower DB pool usage
- optional in-process search fallback when Elasticsearch is disabled
- tunable simulation parallelism
- per-stage cycle timing logs in the simulation engine

## Development commands

```powershell
Set-Location airline-data
sbt test
Set-Location ..\airline-web
sbt test
Set-Location ..
npm --prefix e2e install
npm --prefix e2e test:list
```

## Data sources

- City data source: http://download.geonames.org/export/dump/
- GDP data source: http://databank.worldbank.org/data/reports.aspx?source=2&type=metadata&series=NY.GDP.PCAP.PP.CD
