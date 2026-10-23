#!/usr/bin/env bash
# Starts the LiteBuild fault-injection test server (Fabric, MC 26.2).
# Java 25 is required; override JAVA_HOME if needed.
set -euo pipefail
cd "$(dirname "$0")"

JAVA="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA="${JAVA:-java}"

exec "$JAVA" -Xms1G -Xmx2G \
    -jar fabric-server-mc.26.2-loader.0.19.5-launcher.1.1.2.jar \
    nogui
