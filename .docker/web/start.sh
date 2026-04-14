#!/bin/sh
echo "===== START OF FRONTEND ====="
cd /home/airline/airline/airline-web
DEFAULT_JAVA_OPTS="-Xmx2G -Xms512M -XX:MaxMetaspaceSize=512M"
if [ "${AIRLINE_LOCAL_LITE:-false}" = "true" ]; then
  DEFAULT_JAVA_OPTS="-Xmx1024M -Xms256M -XX:MaxMetaspaceSize=384M -XX:+UseG1GC"
fi
SBT_OPTS="${AIRLINE_WEB_JAVA_OPTS:-$DEFAULT_JAVA_OPTS}" sbt run
echo "===== FRONTEND SHUTDOWN WITH CODE $? ====="
