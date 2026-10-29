#!/bin/sh
# Installs a newer Chrome for Testing for the URL Overlay capture service, if there is one, and keeps the previous one.
# The install runs this daily from WowzaURLOverlayChromeUpdate.timer; run it by hand as root to check now.
. "$(cd "$(dirname "$0")" && pwd)/find-java.sh"
exec "$UO_JAVA" -jar "$UO_JAR" UpdateChrome "$@"
