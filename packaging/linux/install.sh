#!/bin/sh
# This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
# This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
# Installs Overlay Studio into Wowza Streaming Engine with the Java that WSE ships. Options are passed through;
# run "./install.sh --help" for them. Run as root, or as the user that owns the WSE install.
. "$(cd "$(dirname "$0")" && pwd)/find-java.sh"
exec "$OVS_JAVA" -jar "$OVS_JAR" Install "$@"
