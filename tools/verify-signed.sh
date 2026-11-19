#!/usr/bin/env bash
set -u
OUT=verify-results
mkdir -p "$OUT"
STATUS=0
adb wait-for-device
APK=$(ls apk/Prune-*.apk | head -1)
adb install "$APK" | tee "$OUT/install.txt" || STATUS=1
grep -q Success "$OUT/install.txt" || STATUS=1
adb shell dumpsys package io.github.cyb3rl00m.prune | grep -E "versionName|versionCode|signatures" | tee "$OUT/package.txt"
adb logcat -c
adb shell am start -W -n io.github.cyb3rl00m.prune/.MainActivity | tee "$OUT/start.txt"
sleep 10
adb shell screencap -p /data/local/tmp/launch.png
adb pull /data/local/tmp/launch.png "$OUT/launch.png" || true
adb logcat -d > "$OUT/logcat.txt"
if grep -E "FATAL EXCEPTION" "$OUT/logcat.txt"; then STATUS=1; fi
adb shell pidof io.github.cyb3rl00m.prune > "$OUT/pid.txt" || STATUS=1
echo "status=$STATUS" | tee "$OUT/status.txt"
exit $STATUS
