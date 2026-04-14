#!/bin/sh
echo "===== INITIALIZING (if this fails, run again until it works) ====="
cd /home/airline/airline/airline-data
DEFAULT_JAVA_OPTS="-Xmx1536M -Xms256M -XX:MaxMetaspaceSize=384M"
if [ "${AIRLINE_LOCAL_LITE:-false}" = "true" ]; then
  DEFAULT_JAVA_OPTS="-Xmx1024M -Xms256M -XX:MaxMetaspaceSize=320M -XX:+UseG1GC"
fi
SBT_OPTS="${AIRLINE_INIT_JAVA_OPTS:-$DEFAULT_JAVA_OPTS}" sbt publishLocal
echo "===== STARTING MIGRATION ====="
for i in `seq 1 5`
do
  SBT_OPTS="${AIRLINE_INIT_JAVA_OPTS:-$DEFAULT_JAVA_OPTS}" sbt "runMain com.patson.init.MainInit"
  if [ $? -eq 0 ]; then
    echo "Command succeeded on attempt $i"
    break
  else
    echo "Command failed on attempt $i, retrying in 5 seconds..."
    sleep 5
  fi
done
echo "===== DONE ====="
