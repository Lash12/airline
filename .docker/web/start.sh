#!/bin/sh
echo "===== START OF FRONTEND ====="
cd /home/airline/airline/airline-web
SBT_OPTS="-Xmx1G -Xms512M -XX:MaxMetaspaceSize=512M ${WEB_EXTRA_OPTS:-}" sbt run
echo "===== FRONTEND SHUTDOWN WITH CODE $? ====="
