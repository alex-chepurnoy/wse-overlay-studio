# Sourced by install.sh and uninstall.sh. Sets UO_JAR to the installer next to this file and UO_JAVA to a Java 17 or
# later: WMSJAVA_HOME, else the Java WSE ships (WSE's own bin/setenv.sh uses /usr/local/WowzaStreamingEngine/java),
# else java on the PATH.
UO_DIR=$(cd "$(dirname "$0")" && pwd)
UO_JAR=
for j in "$UO_DIR"/wse-plugin-url-overlay-installer-*.jar; do
	[ -f "$j" ] && UO_JAR=$j
done
if [ -z "$UO_JAR" ]; then
	echo "The installer jar is missing from $UO_DIR" >&2
	exit 2
fi
UO_JAVA=
if [ -n "$WMSJAVA_HOME" ] && [ -x "$WMSJAVA_HOME/bin/java" ]; then
	UO_JAVA=$WMSJAVA_HOME/bin/java
elif [ -n "$WMSAPP_HOME" ] && [ -x "$WMSAPP_HOME/java/bin/java" ]; then
	UO_JAVA=$WMSAPP_HOME/java/bin/java
elif [ -x /usr/local/WowzaStreamingEngine/java/bin/java ]; then
	UO_JAVA=/usr/local/WowzaStreamingEngine/java/bin/java
elif command -v java >/dev/null 2>&1; then
	UO_JAVA=$(command -v java)
fi
if [ -z "$UO_JAVA" ]; then
	echo "No Java found. Set WMSJAVA_HOME to a Java 17 or later and run this again." >&2
	exit 2
fi
