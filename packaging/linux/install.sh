#!/bin/sh
# Installs URL Overlay into Wowza Streaming Engine with the Java that WSE ships. Options are passed through;
# run "./install.sh --help" for them. Run as root, or as the user that owns the WSE install.
. "$(cd "$(dirname "$0")" && pwd)/find-java.sh"
exec "$UO_JAVA" -jar "$UO_JAR" Install "$@"
