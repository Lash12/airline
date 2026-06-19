#!/bin/sh
echo "===== START OF BACKEND ====="
cd /home/airline/airline/airline-data
SBT_OPTS="-Xmx3G -Xms1G -XX:MaxMetaspaceSize=512M ${SIM_EXTRA_OPTS:-}" sbt "runMain com.patson.MainSimulation"
echo "===== BACKEND SHUTDOWN WITH CODE $? ====="
