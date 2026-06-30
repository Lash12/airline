#!/bin/sh
echo "===== INITIALIZING (if this fails, run again until it works) ====="
# start-small.sh runs sbt publishLocal on every container start and logs to
# /home/airline/publish.log. Wait for that to finish instead of running a
# second concurrent publishLocal (two 2.8G-heap JVMs exceed the mem_limit).
echo "==> Waiting for background publishLocal (start-small.sh) to complete..."
i=0
while [ "$i" -lt 150 ]; do
  if [ -f /home/airline/publish.log ]; then
    if grep -q "\[success\]" /home/airline/publish.log 2>/dev/null; then
      echo "publishLocal complete"
      break
    fi
    if grep -q "\[error\]" /home/airline/publish.log 2>/dev/null; then
      echo "ERROR: publishLocal failed; aborting init" >&2
      tail -30 /home/airline/publish.log >&2 || true
      exit 1
    fi
  fi
  i=$((i + 1))
  sleep 2
done
if [ "$i" -ge 150 ]; then
  echo "ERROR: publishLocal did not complete within 5 minutes" >&2
  exit 1
fi

echo "===== STARTING MIGRATION ====="
cd /home/airline/airline/airline-data
for i in $(seq 1 5)
do
  SBT_OPTS="-Xmx1536M -Xms512M -XX:MaxMetaspaceSize=512M -Dmysqldb.host=airline-db:3306" sbt "runMain com.patson.init.MainInit"
  if [ $? -eq 0 ]; then
    echo "Command succeeded on attempt $i"
    break
  else
    echo "Command failed on attempt $i, retrying in 5 seconds..."
    sleep 5
  fi
done
echo "===== DONE ====="
