#!/bin/bash
# Font spike, part 2: with no system fonts and no libfontconfig, can Java draw text if it is given its own
# fontconfig.properties (-Dsun.awt.fontconfig=...) that maps the logical fonts to the bundled Fira files?
# Runs in a private mount namespace; nothing on the server is changed.
set -u
D=$(cd "$(dirname "$0")" && pwd)
J=/usr/local/WowzaStreamingEngine/java/bin/java
LIB=$(ldconfig -p | awk '/libfontconfig.so.1 /{print $NF; exit}')
EMPTY=$D/empty
mkdir -p "$EMPTY"
P=$D/fontconfig.properties
{
	echo "version=1"
	for fam in serif sansserif monospaced dialog dialoginput; do
		face="Fira Sans"; [ $fam = monospaced ] || [ $fam = dialoginput ] && face="Fira Mono"
		echo "$fam.plain.latin-1=$face Regular"
		echo "$fam.bold.latin-1=$face Bold"
		echo "$fam.italic.latin-1=$face Regular"
		echo "$fam.bolditalic.latin-1=$face Bold"
	done
	echo "sequence.allfonts=latin-1"
	echo "filename.Fira_Sans_Regular=$D/fonts-fira/FiraSans-Regular.ttf"
	echo "filename.Fira_Sans_Bold=$D/fonts-fira/FiraSans-Bold.ttf"
	echo "filename.Fira_Mono_Regular=$D/fonts-fira/FiraMono-Regular.ttf"
	echo "filename.Fira_Mono_Bold=$D/fonts-fira/FiraMono-Bold.ttf"
} > "$P"
run() {
	name=$1; prop=$2
	echo "===== $name (no system fonts, no libfontconfig, sun.awt.fontconfig=$prop)"
	sudo unshare -m bash -c "
		mount --make-rprivate /
		for d in /usr/share/fonts /usr/local/share/fonts /var/cache/fontconfig; do [ -d \$d ] && mount --bind $EMPTY \$d; done
		mount --bind /dev/null $LIB
		cd $D
		OPT=''; [ $prop = yes ] && OPT='-Dsun.awt.fontconfig=$P'
		echo '--- bundled font only'
		sudo -u ubuntu env HOME=$EMPTY $J -Djava.awt.headless=true \$OPT -cp classes BundledOnly fonts-fira/FiraSans-Regular.ttf bundled-$name.png 2>&1 | head -4
		echo '--- full spike'
		sudo -u ubuntu env HOME=$EMPTY $J -Djava.awt.headless=true \$OPT -cp classes FontSpike fonts-fira out-$name 2>&1 | grep -E 'families_before|^Serif |^SansSerif |^Dialog |Exception|FiraSans-Regular.ttf|Fira Sans +(plain|bold) |Fira Mono +plain|unknown|No Such|heap_used' | head -14
	"
}
run neither-props yes
echo "===== after: leftover mounts in the main namespace (expect 0)"
findmnt -rn -o TARGET | grep -cE 'fonts|fontconfig' || true
