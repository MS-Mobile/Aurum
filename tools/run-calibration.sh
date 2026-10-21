#!/usr/bin/env sh
# Processes photos on the connected phone exactly as the camera saves them (PhotoCalibrationTest), and pulls the results.
#
# Usage: sh tools/run-calibration.sh <out-dir> <photo.jpg>... [-e <param> <value>]...
#   <param> is an EnhanceParams property (golden, warmth, grain, ...) or scaleHdrBySceneLight; unset params use the preset.
# Example: sh tools/run-calibration.sh out/ a.jpg b.jpg -e golden 0.8 -e grain 0.5
set -e
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1 # Git Bash: keep device paths as they are
ADB="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}/platform-tools/adb"
DEVICE_DIR=/sdcard/Android/data/com.msmobile.aurum/files/calibration

[ $# -ge 2 ] || { sed -n '4,6p' "$0"; exit 1; }
OUT_DIR=$1
shift
PHOTOS=""
ARGS=""
while [ $# -gt 0 ]; do
    if [ "$1" = "-e" ]; then
        ARGS="$ARGS -e $2 $3"
        shift 3
    else
        PHOTOS="$PHOTOS
$1"
        shift
    fi
done

# Release-like build, like run-reference-test.sh, without uninstalling afterwards.
./gradlew :app:installProfiling :app:installProfilingAndroidTest -Paurum.testBuildType=profiling --console=plain -q

# HyperOS (GreezeManager) freezes the background test process after a few seconds unless the app is exempt from
# battery optimisation: exempt it for the run, and undo that afterwards unless it already was.
if ! "$ADB" shell dumpsys deviceidle whitelist | grep -q com.msmobile.aurum; then
    trap '"$ADB" shell dumpsys deviceidle whitelist -com.msmobile.aurum > /dev/null' EXIT
fi
"$ADB" shell dumpsys deviceidle whitelist +com.msmobile.aurum > /dev/null

"$ADB" shell rm -rf "$DEVICE_DIR"
"$ADB" shell mkdir -p "$DEVICE_DIR/in"
echo "$PHOTOS" | while IFS= read -r photo; do
    [ -n "$photo" ] && "$ADB" push "$photo" "$DEVICE_DIR/in/" > /dev/null
done

# shellcheck disable=SC2086 # ARGS is a list of -e pairs
"$ADB" shell am instrument -w -e class com.msmobile.aurum.capture.PhotoCalibrationTest $ARGS \
    com.msmobile.aurum.test/com.msmobile.aurum.HiltTestRunner

mkdir -p "$OUT_DIR"
"$ADB" pull "$DEVICE_DIR/out/." "$OUT_DIR" > /dev/null
echo "Results in $OUT_DIR"
