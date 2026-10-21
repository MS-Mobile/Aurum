#!/usr/bin/env sh
# Runs the port validation test on the connected phone without uninstalling the app afterwards
# (connectedAndroidTest uninstalls, which makes HyperOS ask for install permission every run).
set -e
cd "$(dirname "$0")/.."
ADB="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}/platform-tools/adb"
./gradlew :app:installProfiling :app:installProfilingAndroidTest -Paurum.testBuildType=profiling --console=plain -q
"$ADB" logcat -c
"$ADB" shell am instrument -w -e class com.msmobile.aurum.processing.PhotoEnhancerReferenceTest \
    com.msmobile.aurum.test/com.msmobile.aurum.HiltTestRunner
"$ADB" logcat -d -s PhotoEnhancer PhotoEnhancerReference | tail -9
