# Sourced by install.sh, uninstall.sh and update-chrome.sh. Sets UO_JAR to the installer next to this file and UO_JAVA
# to a Java 21 or later, which the capture service needs: WMSJAVA_HOME, else the Java of the WSE install named by
# WMSAPP_HOME, else the Java WSE ships (WSE's own bin/setenv.sh uses /usr/local/WowzaStreamingEngine/java), else java on
# the PATH. Each one is asked its version, so an older Java is passed over. Exit 8 when either is missing.
UO_DIR=$(cd "$(dirname "$0")" && pwd)
UO_JAR=
for j in "$UO_DIR"/wse-plugin-url-overlay-installer-*.jar; do
	[ -f "$j" ] && UO_JAR=$j
done
if [ -z "$UO_JAR" ]; then
	echo "The installer jar is missing from $UO_DIR" >&2
	exit 8
fi
UO_JAVA=
uo_try() {
	if [ -n "$UO_JAVA" ] || [ ! -x "$1" ]; then
		return 0
	fi
	# java -version prints, for example: openjdk version "21.0.3" 2024-04-16; Java 8 prints "1.8.0"
	uo_v=$("$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)
	if [ -n "$uo_v" ] && [ "$uo_v" -ge 21 ]; then
		UO_JAVA=$1
	fi
	return 0
}
[ -n "$WMSJAVA_HOME" ] && uo_try "$WMSJAVA_HOME/bin/java"
[ -n "$WMSAPP_HOME" ] && uo_try "$WMSAPP_HOME/java/bin/java"
uo_try /usr/local/WowzaStreamingEngine/java/bin/java
command -v java >/dev/null 2>&1 && uo_try "$(command -v java)"
if [ -z "$UO_JAVA" ]; then
	echo "No Java 21 or later found. Set WMSJAVA_HOME to a Java 21 or later and run this again." >&2
	exit 8
fi
