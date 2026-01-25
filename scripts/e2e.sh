#!/usr/bin/env bash
set -euo pipefail

echo "[gate] Starting docker stack..."
docker compose up -d

echo "[gate] Publishing sbt artifacts..."
docker exec airline-app bash -lc 'cd /home/airline/airline/airline-data && sbt publishLocal'

echo "[gate] Initializing database..."
docker exec airline-app bash -lc 'cd /home/airline/airline/airline-data && sbt "runMain com.patson.init.MainInit"'

echo "[gate] Starting web server..."
docker exec -d airline-app bash -lc 'cd /home/airline/airline/airline-web && sbt run'

echo "[gate] Waiting for http://localhost:9000 ..."
for i in {1..60}; do
  if curl -fsS http://localhost:9000 >/dev/null 2>&1; then
    echo "[gate] Web server is up."
    break
  fi
  sleep 2
  if [[ "$i" == "60" ]]; then
    echo "[gate] Web server did not become ready in time."
    exit 1
  fi
done

echo "[gate] Running Playwright tests..."
pushd e2e >/dev/null
npx playwright test
popd >/dev/null

echo "[gate] PASS"
