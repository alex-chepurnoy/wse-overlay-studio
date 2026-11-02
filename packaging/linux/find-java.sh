# This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
# This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
# Sourced by install.sh, uninstall.sh and update-chrome.sh. Sets OVS_JAR to the installer next to this file and OVS_JAVA
# to a Java 21 or later, which the capture service needs: WMSJAVA_HOME, else the Java of the WSE install named by
# WMSAPP_HOME, else the Java WSE ships (WSE's own bin/setenv.sh uses /usr/local/WowzaStreamingEngine/java), else java on
# the PATH. Each one is asked its version, so an older Java is passed over. Exit 8 when either is missing.
OVS_DIR=$(cd "$(dirname "$0")" && pwd)
OVS_JAR=
for j in "$OVS_DIR"/wse-plugin-overlay-studio-installer-*.jar; do
	[ -f "$j" ] && OVS_JAR=$j
done
if [ -z "$OVS_JAR" ]; then
	echo "The installer jar is missing from $OVS_DIR" >&2
	exit 8
fi
OVS_JAVA=
ovs_try() {
	if [ -n "$OVS_JAVA" ] || [ ! -x "$1" ]; then
		return 0
	fi
	# java -version prints, for example: openjdk version "21.0.3" 2024-04-16; Java 8 prints "1.8.0"
	ovs_v=$("$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)
	if [ -n "$ovs_v" ] && [ "$ovs_v" -ge 21 ]; then
		OVS_JAVA=$1
	fi
	return 0
}
[ -n "$WMSJAVA_HOME" ] && ovs_try "$WMSJAVA_HOME/bin/java"
[ -n "$WMSAPP_HOME" ] && ovs_try "$WMSAPP_HOME/java/bin/java"
ovs_try /usr/local/WowzaStreamingEngine/java/bin/java
command -v java >/dev/null 2>&1 && ovs_try "$(command -v java)"
if [ -z "$OVS_JAVA" ]; then
	echo "No Java 21 or later found. Set WMSJAVA_HOME to a Java 21 or later and run this again." >&2
	exit 8
fi
