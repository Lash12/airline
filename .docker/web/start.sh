#!/bin/sh
echo "===== START OF FRONTEND ====="
cd /home/airline/airline/airline-web
DEFAULT_JAVA_OPTS="-Xmx2G -Xms512M -XX:MaxMetaspaceSize=512M"
AIRLINE_LOCAL_LITE_NORMALIZED=$(printf '%s' "${AIRLINE_LOCAL_LITE:-false}" | tr '[:upper:]' '[:lower:]')
case "$AIRLINE_LOCAL_LITE_NORMALIZED" in
  true|1|yes|on)
    DEFAULT_JAVA_OPTS="-Xmx1024M -Xms256M -XX:MaxMetaspaceSize=384M -XX:+UseG1GC"
    ;;
esac
SBT_OPTS="${AIRLINE_WEB_JAVA_OPTS:-$DEFAULT_JAVA_OPTS}" sbt run
echo "===== FRONTEND SHUTDOWN WITH CODE $? ====="
