#!/bin/sh
echo "===== START OF BACKEND ====="
cd /home/airline/airline/airline-data
DEFAULT_JAVA_OPTS="-Xmx4G -Xms512M -XX:MaxMetaspaceSize=512M"
if [ "${AIRLINE_LOCAL_LITE:-false}" = "true" ]; then
  DEFAULT_JAVA_OPTS="-Xmx1536M -Xms256M -XX:MaxMetaspaceSize=384M -XX:+UseG1GC"
fi
SBT_OPTS="${AIRLINE_DATA_JAVA_OPTS:-$DEFAULT_JAVA_OPTS}" sbt "runMain com.patson.MainSimulation"
echo "===== BACKEND SHUTDOWN WITH CODE $? ====="
