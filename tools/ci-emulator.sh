#!/usr/bin/env bash
# Runs inside the Android emulator job on GitHub Actions.
#  1. Instrumented tests (debug build): engine tests that run Prune's real pm/appops/settings
#     commands as the shell user, plus UI tests that walk every screen and take screenshots.
#  2. Smoke test of the minified, signed release APK: install, launch, check for crashes.
set -u
APKS="${1:-apks}"
OUT="emulator-results"
mkdir -p "$OUT/screens"
STATUS=0

adb wait-for-device
adb shell getprop ro.build.version.release > "$OUT/android-version.txt"
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global animator_duration_scale 1

echo "== Install debug + test APKs"
adb install -r -t "$APKS/app/build/outputs/apk/debug/app-debug.apk" || STATUS=1
adb install -r -t "$APKS/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" || STATUS=1
adb shell rm -rf /data/local/tmp/prune-shots
adb shell mkdir -p /data/local/tmp/prune-shots
adb shell chmod 777 /data/local/tmp/prune-shots

echo "== Instrumented tests"
adb logcat -c
adb shell am instrument -w -r \
  -e notClass io.github.cyb3rl00m.prune.ShizukuRealTest \
  io.github.cyb3rl00m.prune.debug.test/androidx.test.runner.AndroidJUnitRunner \
  | tee "$OUT/instrumentation.txt"
if grep -qE "FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed" "$OUT/instrumentation.txt"; then STATUS=1; fi
if ! grep -qE "^OK \(" "$OUT/instrumentation.txt"; then STATUS=1; fi
adb logcat -d > "$OUT/logcat-instrumentation.txt"
adb pull /data/local/tmp/prune-shots/. "$OUT/screens/" || true

echo "== Release APK smoke test"
REL=$(ls "$APKS"/dist/Prune-*.apk | head -1)
adb install -r "$REL" || STATUS=1
adb logcat -c
adb shell am start -W -n io.github.cyb3rl00m.prune/.MainActivity | tee "$OUT/release-start.txt"
sleep 8
adb shell screencap -p /data/local/tmp/release-launch.png
adb pull /data/local/tmp/release-launch.png "$OUT/screens/release-launch.png" || true
adb logcat -d > "$OUT/logcat-release.txt"
if grep -E "FATAL EXCEPTION|AndroidRuntime: Process: io.github.cyb3rl00m.prune" "$OUT/logcat-release.txt"; then
  echo "Release build crashed"; STATUS=1
fi
if ! adb shell pidof io.github.cyb3rl00m.prune >/dev/null; then
  echo "Release build is not running after launch"; STATUS=1
fi

# ---------------------------------------------------------------------------------------------
# Real Shizuku. Everything above uses a UiAutomation shell; this part runs the code path users
# actually hit: Prune -> Shizuku server -> user service / remote process.
# ---------------------------------------------------------------------------------------------
UI_XML=/tmp/ui.xml
dump_ui() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$UI_XML" >/dev/null 2>&1; }
# tap_text REGEX [tries]: finds the text on screen (scrolling down between tries) and taps it.
tap_text() {
  local tries="${2:-15}"
  for _ in $(seq 1 "$tries"); do
    dump_ui
    if XY=$(python3 tools/ci_ui.py center "$1" "$UI_XML"); then adb shell input tap $XY; sleep 2; return 0; fi
    adb shell input swipe 540 1600 540 900 300; sleep 1
  done
  echo "Text not found on screen: $1"; return 1
}
# wait_text REGEX [seconds]: prints the matching text once it appears.
wait_text() {
  local secs="${2:-60}"
  for _ in $(seq 1 "$secs"); do
    dump_ui
    if python3 tools/ci_ui.py text "$1" "$UI_XML"; then return 0; fi
    sleep 1
  done
  return 1
}

SHIZUKU_APK=$(ls "$APKS"/shizuku/*.apk 2>/dev/null | head -1)
if [ -z "$SHIZUKU_APK" ]; then
  echo "Shizuku APK missing: real Shizuku tests not run"; STATUS=1
else
  echo "== Start Shizuku ($(basename "$SHIZUKU_APK"))"
  basename "$SHIZUKU_APK" > "$OUT/shizuku-version.txt"
  adb install -r "$SHIZUKU_APK" || STATUS=1
  adb shell monkey -p moe.shizuku.privileged.api -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 6
  adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh > "$OUT/shizuku-start.txt" 2>&1
  if ! adb shell ps -A | grep -q shizuku_server; then
    # Older layouts: run the starter binary from the app's native library folder.
    DIR=$(adb shell pm path moe.shizuku.privileged.api | head -1 | sed -e 's/^package://' -e 's#/base.apk.*##' | tr -d '\r')
    adb shell "$DIR/lib/x86_64/libshizuku.so" >> "$OUT/shizuku-start.txt" 2>&1
  fi
  sleep 3
  adb shell ps -A | grep shizuku_server | tee -a "$OUT/shizuku-start.txt" || { echo "Shizuku server did not start"; STATUS=1; }
  adb shell input keyevent KEYCODE_HOME

  echo "== Instrumented tests with real Shizuku (debug build)"
  adb logcat -c
  adb shell am instrument -w -r -e shizuku required \
    -e class io.github.cyb3rl00m.prune.ShizukuRealTest \
    io.github.cyb3rl00m.prune.debug.test/androidx.test.runner.AndroidJUnitRunner \
    | tee "$OUT/instrumentation-shizuku.txt"
  if ! grep -qE "^OK \(3 tests\)" "$OUT/instrumentation-shizuku.txt"; then STATUS=1; fi
  adb logcat -d > "$OUT/logcat-shizuku.txt"
  grep -E "PruneShizukuTest|ShizukuShell|Prune" "$OUT/logcat-shizuku.txt" | head -50 > "$OUT/shizuku-summary.txt" || true

  echo "== Release APK with real Shizuku: Set up -> Authorize -> Test connection"
  adb shell am force-stop io.github.cyb3rl00m.prune
  adb logcat -c
  adb shell am start -W -n io.github.cyb3rl00m.prune/.MainActivity >/dev/null
  sleep 6
  RELEASE_OK=0
  if tap_text '^Set up$' && tap_text '^Authorize Prune in Shizuku$' 3 && tap_text '(?i)^allow' 5; then
    sleep 3
    adb shell screencap -p /data/local/tmp/release-setup.png
    adb pull /data/local/tmp/release-setup.png "$OUT/screens/release-shizuku-setup.png" >/dev/null || true
    if tap_text '^Test connection$' 5; then
      RESULT=$(wait_text '^(Connected|Connection failed)' 60)
      echo "$RESULT" | tee "$OUT/release-connection.txt"
      case "$RESULT" in "Connected: uid 2000"*) RELEASE_OK=1 ;; esac
    fi
  fi
  adb shell screencap -p /data/local/tmp/release-connection.png
  adb pull /data/local/tmp/release-connection.png "$OUT/screens/release-shizuku-connection.png" >/dev/null || true
  adb logcat -d > "$OUT/logcat-release-shizuku.txt"
  [ "$RELEASE_OK" = 1 ] || { echo "Release build could not run commands through Shizuku"; STATUS=1; }
fi

echo "== Exit status $STATUS"
exit $STATUS
