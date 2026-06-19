#!/bin/sh
set -eu

echo "===== START OF SMALL-SERVER APP SUPERVISOR ====="

wait_for_db() {
  echo "Waiting for airline-db:3306..."
  i=0
  while [ "$i" -lt 90 ]; do
    if timeout 2 bash -c '</dev/tcp/airline-db/3306' >/dev/null 2>&1; then
      echo "airline-db:3306 is reachable"
      return 0
    fi
    i=$((i + 1))
    sleep 2
  done

  echo "ERROR: airline-db:3306 did not become reachable" >&2
  return 1
}

shutdown() {
  echo "Supervisor received shutdown; stopping child processes"
  [ -n "${SIM_PID:-}" ] && kill "$SIM_PID" 2>/dev/null || true
  [ -n "${WEB_PID:-}" ] && kill "$WEB_PID" 2>/dev/null || true
  wait 2>/dev/null || true
}

trap shutdown INT TERM

wait_for_db

echo "Publishing airline-data locally"
(
  cd /home/airline/airline/airline-data
  SBT_OPTS="-Xmx1536M -Xms512M -XX:MaxMetaspaceSize=512M ${SIM_EXTRA_OPTS:-}" sbt publishLocal
) > /home/airline/publish.log 2>&1 || {
  code=$?
  echo "ERROR: airline-data publishLocal failed with code $code" >&2
  tail -80 /home/airline/publish.log >&2 || true
  exit "$code"
}

echo "Starting web"
sh /home/airline/start-web.sh > /home/airline/web.log 2>&1 &
WEB_PID=$!

echo "Waiting for web to bind :9000..."
i=0
while [ "$i" -lt 120 ]; do
  if curl -fsS --max-time 2 http://localhost:9000/ >/dev/null 2>&1; then
    echo "Web is serving on :9000"
    break
  fi

  if ! kill -0 "$WEB_PID" 2>/dev/null; then
    code=0
    wait "$WEB_PID" || code=$?
    echo "Web exited during startup with code ${code}; supervisor exiting"
    exit "${code:-1}"
  fi

  i=$((i + 1))
  sleep 5
done

if [ "$i" -ge 120 ]; then
  echo "ERROR: web did not serve HTTP within 10 minutes" >&2
  kill "$WEB_PID" 2>/dev/null || true
  exit 1
fi

echo "Starting simulation"
sh /home/airline/start-data.sh > /home/airline/sim.log 2>&1 &
SIM_PID=$!

while :; do
  if ! kill -0 "$SIM_PID" 2>/dev/null; then
    code=0
    wait "$SIM_PID" || code=$?
    echo "Simulation exited with code ${code:-0}; supervisor exiting"
    exit "${code:-1}"
  fi

  if ! kill -0 "$WEB_PID" 2>/dev/null; then
    code=0
    wait "$WEB_PID" || code=$?
    echo "Web exited with code ${code:-0}; supervisor exiting"
    exit "${code:-1}"
  fi

  sleep 5
done
