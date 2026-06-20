# Small Server Deployment Guide

Deploy the game on a small single-player host (8 GB RAM / 4 cores class, e.g. a Dell
OptiPlex Micro) using the `docker-compose.small.yaml` profile. It uses the same image
and mounts as the full `docker-compose.yaml`, with memory limits, a tuned MySQL
(`.docker/db/small.cnf`), and no Elasticsearch.

## Prerequisites
- Docker with the compose plugin

## Deployment safety checklist

- Use `scripts/optiplex-deploy.sh` for OptiPlex deploys; it verifies the MySQL
  volume before and after container recreation.
- The Bitnami MySQL image must mount `mysql-data` at `/bitnami/mysql`. Do not
  mount it at `/var/lib/mysql`; that path does not preserve this image's data.
- Uploaded app assets, including airline logos under `data/logos`, must persist
  in the `app-data` volume mounted at `/home/airline/data`.
- Before any intentional reset, take a DB backup first. A reset should be an
  explicit `docker compose -f docker-compose.small.yaml down -v`, never a side
  effect of rebuilding.

## First-time setup

1. **Start the stack**
   ```bash
   docker compose -f docker-compose.small.yaml up -d
   ```

2. **Initialize the database** (first run only; loads airports/cities/routes —
   takes a while)
   ```bash
   docker exec -it airline-app sh /home/airline/init-data.sh
   ```

3. **Start the simulation and the web app**
   ```bash
   docker exec -d airline-app sh /home/airline/start-data.sh
   docker exec -d airline-app sh /home/airline/start-web.sh
   ```

4. **Verify** — `docker logs airline-app`, then open http://localhost:9000

## Resource budget (8 GB host)

| Component | Limit / setting |
|---|---|
| MySQL container | 1.5 GB `mem_limit`, 768M InnoDB buffer pool, performance_schema off, binlog off |
| App container (both JVMs) | 3.5 GB `mem_limit`; sim `-Xmx1536M`, web `-Xmx1G` (`.docker/*/start.sh`) |
| DB connections | sim pool 8, web pool 10 (`hikari.*` settings) |

## Notes

- Elasticsearch is not part of this profile (no code depends on it).
- The MySQL slow-query log is enabled (`long_query_time=0.5`) to collect evidence
  for index tuning; see `docs/single-player-performance-roadmap.md`.
- The app is exposed on port 9000.
