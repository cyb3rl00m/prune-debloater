#!/usr/bin/env bash
# Runs inside the Android TV emulator job on GitHub Actions: installs the signed release APK, checks that it
# appears in the TV launcher, opens it and drives it with D-pad key presses only (no touch), taking screenshots.
set -u
APKS="${1:-apks}"
OUT="tv-results"
mkdir -p "$OUT/screens"
STATUS=0
PKG=io.github.cyb3rl00m.prune

shot() { adb shell screencap -p /data/local/tmp/s.png; adb pull /data/local/tmp/s.png "$OUT/screens/$1.png" >/dev/null || true; }
key() { adb shell input keyevent "$@"; sleep 1; }

adb wait-for-device
adb shell getprop ro.build.characteristics > "$OUT/characteristics.txt"
adb shell getprop ro.build.version.release > "$OUT/android-version.txt"
REL=$(ls "$APKS"/dist/Prune-*.apk | head -1)
adb install -r "$REL" || STATUS=1

echo "== Listed in the TV launcher?"
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER | tee "$OUT/leanback.txt"
grep -q "$PKG/" "$OUT/leanback.txt" || { echo "Prune is not in the TV launcher"; STATUS=1; }

echo "== Launch and navigate with the D-pad"
adb logcat -c
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -n "$PKG/.MainActivity" | tee "$OUT/start.txt"
sleep 10
shot 01-home
key KEYCODE_DPAD_DOWN; key KEYCODE_DPAD_DOWN; shot 02-home-focus
# Bottom navigation: move to the last row and right to the Debloat tab, then open it.
for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do adb shell input keyevent KEYCODE_DPAD_DOWN; done; sleep 1
key KEYCODE_DPAD_RIGHT; key KEYCODE_DPAD_CENTER; sleep 2; shot 03-after-dpad

adb logcat -d > "$OUT/logcat.txt"
if grep -E "FATAL EXCEPTION|AndroidRuntime: Process: $PKG" "$OUT/logcat.txt"; then echo "Crashed on TV"; STATUS=1; fi
adb shell pidof "$PKG" >/dev/null || { echo "Not running after navigation"; STATUS=1; }
echo "== Exit status $STATUS"
exit $STATUS
