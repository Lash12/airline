#!/usr/bin/env bash
# Idempotent deploy of the small-server stack on the OptiPlex runner.
# Brings up docker-compose.small.yaml, initializes the database on first
# run only, restarts the simulation and web processes, and waits for the
# web frontend to answer on :9000. See docs/optiplex-ci-plan.md.
set -euo pipefail

cd "$(dirname "$0")/.."

COMPOSE="docker compose -f docker-compose.small.yaml"

# Containers with our fixed names left behind by an older compose project
# (different working dir) block `up` with a name conflict. Only stopped
# containers are reclaimed; volumes are never touched.
for name in airline-app airline-db; do
  state=$(docker inspect -f '{{.State.Status}}' "$name" 2>/dev/null || true)
  if [ -n "$state" ] && [ "$state" != "running" ]; then
    project_dir=$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project.working_dir"}}' "$name")
    if [ "$project_dir" != "$(pwd)" ]; then
      echo "==> Removing stale stopped container $name (from $project_dir)"
      docker rm "$name"
    fi
  fi
done

echo "==> Starting containers"
$COMPOSE up -d --build --force-recreate

echo "==> Waiting for MySQL (up to 60s)"
db_ready=""
for _ in $(seq 1 60); do
  if docker exec airline-db mysqladmin ping --silent 2>/dev/null; then
    db_ready=1
    break
  fi
  sleep 1
done
if [ -z "$db_ready" ]; then
  echo "ERROR: MySQL did not become ready within 60s" >&2
  docker logs --tail 50 airline-db >&2 || true
  exit 1
fi

# Credentials come from the compose file via the running container's env.
DB_USER=$(docker exec airline-db printenv MYSQL_USER)
DB_PASS=$(docker exec airline-db printenv MYSQL_PASSWORD)
DB_NAME=$(docker exec airline-db printenv MYSQL_DATABASE)

cycle_table=$(docker exec airline-db mysql -u"$DB_USER" -p"$DB_PASS" -N \
  -e "SHOW TABLES LIKE 'cycle'" "$DB_NAME" 2>/dev/null || true)
if [ -z "$cycle_table" ]; then
  echo "==> Database not initialized; running init (publishLocal + MainInit)"
  docker exec airline-app sh /home/airline/init-data.sh
else
  echo "==> Database already initialized; supervisor will refresh airline-data artifact"
fi

echo "==> Waiting for HTTP 200 from :9000 (up to 10 min)"
web_ready=""
for _ in $(seq 1 120); do
  if curl -sf -o /dev/null http://localhost:9000/; then
    web_ready=1
    break
  fi
  sleep 5
done
if [ -z "$web_ready" ]; then
  echo "ERROR: web frontend did not answer on :9000 within 10 minutes" >&2
  docker exec airline-app sh -c 'tail -50 /home/airline/web.log' >&2 || true
  exit 1
fi

echo "==> Verifying supervisor-managed JVMs"
docker exec airline-app sh -lc 'pgrep -af "sbt-launch.jar run$|sbt-launch.jar runMain com.patson.MainSimulation"'

echo "==> Container health"
docker ps --format '{{.Names}} {{.Status}}'

echo "==> Deploy complete: http://localhost:9000 is up"
