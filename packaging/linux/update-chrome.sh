#!/bin/sh
# This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
# This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
# Installs a newer Chrome for Testing for the Overlay Studio capture service, if there is one, and keeps the previous one.
# The install runs this daily from WowzaOverlayStudioChromeUpdate.timer; run it by hand as root to check now.
. "$(cd "$(dirname "$0")" && pwd)/find-java.sh"
exec "$OVS_JAVA" -jar "$OVS_JAR" UpdateChrome "$@"
