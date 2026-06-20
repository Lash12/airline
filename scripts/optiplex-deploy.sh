#!/usr/bin/env bash
# Idempotent deploy of the small-server stack on the OptiPlex runner.
# Brings up docker-compose.small.yaml, initializes the database on first
# run only, restarts the simulation and web processes, and waits for the
# web frontend to answer on :9000. See docs/optiplex-ci-plan.md.
set -euo pipefail

cd "$(dirname "$0")/.."

COMPOSE="docker compose -f docker-compose.small.yaml"
MYSQL_CONTAINER="airline-db"
MYSQL_VOLUME="mysql-data"
MYSQL_MOUNT="/bitnami/mysql"
LEGACY_MYSQL_MOUNT="/var/lib/mysql"
APP_CONTAINER="airline-app"
APP_DATA_VOLUME="app-data"
APP_DATA_MOUNT="/home/airline/data"

mysql_mounts() {
  docker inspect -f '{{range .Mounts}}{{println .Name .Destination}}{{end}}' "$MYSQL_CONTAINER" 2>/dev/null || true
}

app_mounts() {
  docker inspect -f '{{range .Mounts}}{{println .Name .Destination}}{{end}}' "$APP_CONTAINER" 2>/dev/null || true
}

verify_mysql_mount_config() {
  echo "==> Verifying MySQL persistence config"
  local config
  config=$($COMPOSE config)

  if printf '%s\n' "$config" | grep -q "target: ${LEGACY_MYSQL_MOUNT}"; then
    echo "ERROR: docker-compose.small.yaml mounts MySQL at ${LEGACY_MYSQL_MOUNT}." >&2
    echo "Bitnami MySQL persists data under ${MYSQL_MOUNT}; refusing deploy." >&2
    exit 1
  fi

  if ! printf '%s\n' "$config" | grep -q "target: ${MYSQL_MOUNT}"; then
    echo "ERROR: docker-compose.small.yaml does not mount ${MYSQL_VOLUME} at ${MYSQL_MOUNT}." >&2
    exit 1
  fi

  if ! printf '%s\n' "$config" | grep -q "target: ${APP_DATA_MOUNT}"; then
    echo "ERROR: docker-compose.small.yaml does not mount ${APP_DATA_VOLUME} at ${APP_DATA_MOUNT}." >&2
    echo "Uploaded logos would be lost on container recreation; refusing deploy." >&2
    exit 1
  fi
}

verify_existing_mysql_mount() {
  local mounts
  mounts=$(mysql_mounts)
  if [ -z "$mounts" ]; then
    return
  fi

  if printf '%s\n' "$mounts" | grep -q " ${LEGACY_MYSQL_MOUNT}$"; then
    echo "ERROR: existing ${MYSQL_CONTAINER} uses the legacy ${LEGACY_MYSQL_MOUNT} mount." >&2
    echo "Stop here and take a database backup/migration before recreating containers." >&2
    exit 1
  fi
}

verify_running_mysql_mount() {
  local mounts
  mounts=$(mysql_mounts)
  if ! printf '%s\n' "$mounts" | grep -q "${MYSQL_VOLUME}.* ${MYSQL_MOUNT}$"; then
    echo "ERROR: running ${MYSQL_CONTAINER} is not using ${MYSQL_VOLUME} at ${MYSQL_MOUNT}." >&2
    echo "Observed mounts:" >&2
    printf '%s\n' "$mounts" >&2
    exit 1
  fi
}

verify_running_app_data_mount() {
  local mounts
  mounts=$(app_mounts)
  if ! printf '%s\n' "$mounts" | grep -q "${APP_DATA_VOLUME}.* ${APP_DATA_MOUNT}$"; then
    echo "ERROR: running ${APP_CONTAINER} is not using ${APP_DATA_VOLUME} at ${APP_DATA_MOUNT}." >&2
    echo "Observed mounts:" >&2
    printf '%s\n' "$mounts" >&2
    exit 1
  fi
}

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

verify_mysql_mount_config
verify_existing_mysql_mount

if [ -z "${CLOUDFLARE_TUNNEL_TOKEN:-}" ]; then
  echo "ERROR: CLOUDFLARE_TUNNEL_TOKEN is required for the HTTPS tunnel." >&2
  echo "Set the GitHub Actions secret before deploying the small-server stack." >&2
  exit 1
fi

echo "==> Starting containers"
$COMPOSE up -d --build --force-recreate
verify_running_mysql_mount
verify_running_app_data_mount

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

cloudflared_state=$(docker inspect -f '{{.State.Status}}' airline-cloudflared 2>/dev/null || true)
if [ "$cloudflared_state" != "running" ]; then
  echo "ERROR: airline-cloudflared is not running." >&2
  docker logs --tail 50 airline-cloudflared >&2 || true
  exit 1
fi

echo "==> Deploy complete: http://localhost:9000 is up"
