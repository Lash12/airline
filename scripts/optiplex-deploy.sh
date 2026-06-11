#!/usr/bin/env bash
# Idempotent deploy of the small-server stack on the OptiPlex runner.
# Brings up docker-compose.small.yaml, initializes the database on first
# run only, restarts the simulation and web processes, and waits for the
# web frontend to answer on :9000. See docs/optiplex-ci-plan.md.
set -euo pipefail

cd "$(dirname "$0")/.."

COMPOSE="docker compose -f docker-compose.small.yaml"
# application.conf hardcodes localhost:3306; override via Typesafe Config
# system property (sbt does not fork, so SBT_OPTS reaches the app).
DB_OVERRIDE="-Dmysqldb.host=airline-db:3306"
SIM_EXTRA_OPTS="${SIM_EXTRA_OPTS:-} $DB_OVERRIDE"

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
$COMPOSE up -d --build

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

echo "==> Stopping any prior simulation/web processes"
# The matcher script's own cmdline contains the pattern strings, so it
# must skip itself or it commits suicide (exit 143).
docker exec airline-app sh -c '
  for p in /proc/[0-9]*; do
    pid=${p#/proc/}
    [ "$pid" = 1 ] && continue
    [ "$pid" = "$$" ] && continue
    cmd=$(tr "\0" " " < "$p/cmdline" 2>/dev/null || true)
    case "$cmd" in
      *sbt*|*java*) kill "$pid" 2>/dev/null || true ;;
    esac
  done
  true' || true
sleep 3

# Credentials come from the compose file via the running container's env.
DB_USER=$(docker exec airline-db printenv MYSQL_USER)
DB_PASS=$(docker exec airline-db printenv MYSQL_PASSWORD)
DB_NAME=$(docker exec airline-db printenv MYSQL_DATABASE)

cycle_table=$(docker exec airline-db mysql -u"$DB_USER" -p"$DB_PASS" -N \
  -e "SHOW TABLES LIKE 'cycle'" "$DB_NAME" 2>/dev/null || true)
if [ -z "$cycle_table" ]; then
  echo "==> Database not initialized; running init (publishLocal + MainInit)"
  docker exec -e SBT_OPTS="$DB_OVERRIDE" airline-app sh /home/airline/init-data.sh
else
  echo "==> Database already initialized; refreshing airline-data artifact"
  docker exec airline-app sh -c \
    'cd /home/airline/airline/airline-data && SBT_OPTS="-Xmx1536M -Xms512M -XX:MaxMetaspaceSize=512M" sbt publishLocal'
fi

echo "==> Starting simulation (SIM_EXTRA_OPTS='$SIM_EXTRA_OPTS') and web"
docker exec -d -e SIM_EXTRA_OPTS="$SIM_EXTRA_OPTS" airline-app \
  sh -c 'sh /home/airline/start-data.sh > /home/airline/sim.log 2>&1'
docker exec -d -e WEB_EXTRA_OPTS="$DB_OVERRIDE ${WEB_SOLO_OPTS:-}" airline-app \
  sh -c 'sh /home/airline/start-web.sh > /home/airline/web.log 2>&1'

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

echo "==> Deploy complete: http://localhost:9000 is up"
