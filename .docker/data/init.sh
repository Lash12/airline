#!/bin/sh
echo "===== INITIALIZING (if this fails, run again until it works) ====="
cd /home/airline/airline/airline-data
SBT_OPTS="-Xmx1536M -Xms512M -XX:MaxMetaspaceSize=512M ${SIM_EXTRA_OPTS:-}" sbt publishLocal
echo "===== STARTING MIGRATION ====="
for i in `seq 1 5`
do
  SBT_OPTS="-Xmx1536M -Xms512M -XX:MaxMetaspaceSize=512M ${SIM_EXTRA_OPTS:-}" sbt "runMain com.patson.init.MainInit"
  if [ $? -eq 0 ]; then
    echo "Command succeeded on attempt $i"
    break
  else
    echo "Command failed on attempt $i, retrying in 5 seconds..."
    sleep 5
  fi
done
echo "===== DONE ====="
