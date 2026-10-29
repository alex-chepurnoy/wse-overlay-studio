#!/bin/sh
# Removes URL Overlay from Wowza Streaming Engine. Options are passed through; run "./uninstall.sh --help" for them.
# Run as root, or as the user that owns the WSE install.
. "$(cd "$(dirname "$0")" && pwd)/find-java.sh"
exec "$UO_JAVA" -jar "$UO_JAR" Uninstall "$@"
