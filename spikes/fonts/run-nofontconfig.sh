#!/bin/bash
# Font spike, Linux without system fonts or fontconfig. Each case runs in a private mount namespace (unshare -m)
# that hides fonts and/or libfontconfig for that one process only; nothing on the server is changed.
set -u
D=$(cd "$(dirname "$0")" && pwd)
J=/usr/local/WowzaStreamingEngine/java/bin/java
LIB=$(ldconfig -p | awk '/libfontconfig.so.1 /{print $NF; exit}')
EMPTY=$D/empty
mkdir -p "$EMPTY"
echo "libfontconfig=$LIB"
run() {
	name=$1; hf=$2; hl=$3
	echo "===== $name (hide font folders=$hf, hide libfontconfig=$hl)"
	sudo unshare -m bash -c "
		mount --make-rprivate /
		if [ $hf = 1 ]; then for d in /usr/share/fonts /usr/local/share/fonts /var/cache/fontconfig; do [ -d \$d ] && mount --bind $EMPTY \$d; done; fi
		if [ $hl = 1 ]; then mount --bind /dev/null $LIB; fi
		cd $D
		echo '--- full spike (logical fonts, registration, rendering)'
		sudo -u ubuntu env HOME=$EMPTY $J -Djava.awt.headless=true -cp classes FontSpike fonts-fira out-$name 2>&1 | grep -E 'families_before|^Serif |^SansSerif |^Dialog |Exception|Error|at java|FiraSans-Regular.ttf|Fira Sans +(plain|bold) |families_after|heap_used' | head -20
		echo '--- bundled font only'
		sudo -u ubuntu env HOME=$EMPTY $J -Djava.awt.headless=true -cp classes BundledOnly fonts-fira/FiraSans-Regular.ttf bundled-$name.png 2>&1 | head -8
	"
}
run baseline 0 0
run nofonts 1 0
run nolib 0 1
run neither 1 1
echo "===== after: leftover mounts in the main namespace (expect none)"
findmnt -rn -o TARGET | grep -cE 'fonts|fontconfig' || true
