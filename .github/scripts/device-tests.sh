#!/usr/bin/env bash
# Runs every instrumented test (app/src/androidTest: section editor, app-wide smoke tests in
# EN/FR/AR, class initialization on ART, PDF engine) on the running emulator and keeps the
# evidence in device-test-output/: am instrument output, full logcat, every FATAL EXCEPTION with
# its stack trace, and the screenshots the test wrote. Exits non-zero when a test failed or the
# app process crashed.
set -u
out=device-test-output
pkg=com.aistudio.smartbubble.prod
mkdir -p "$out"
apk=$(ls app/build/outputs/apk/debug/*.apk | head -1)
test_apk=$(ls app/build/outputs/apk/androidTest/debug/*.apk | head -1)
adb shell getprop ro.build.version.release > "$out/android-version.txt"
adb shell getprop ro.build.version.sdk >> "$out/android-version.txt"
adb install -r -g "$apk"
adb install -r -g "$test_apk"
adb logcat -c
adb shell am instrument -w -r "$pkg.test/androidx.test.runner.AndroidJUnitRunner" | tee "$out/instrument.txt"
adb logcat -d -v threadtime > "$out/logcat.txt"
for d in thesis-device app-device; do
  adb pull "/sdcard/Android/data/$pkg/files/$d" "$out/screenshots-$d" || echo "no $d screenshots pulled"
done
grep -n -A80 "FATAL EXCEPTION" "$out/logcat.txt" > "$out/fatal-exceptions.txt" || true
grep -n "AndroidRuntime\|System.err" "$out/logcat.txt" | head -400 > "$out/androidruntime.txt" || true
# Clipboard access decisions of Android (e.g. "Denying clipboard access ... not in focus") and the
# app's own editor / file messages, to explain a paste or open failure without the full logcat.
grep -n -i "clipboard\|ThesisEditor\|DeliveryManager\|TestRunner: failed" "$out/logcat.txt" | head -300 > "$out/app-events.txt" || true
if [ -s "$out/fatal-exceptions.txt" ]; then echo "::error::The app crashed during the device tests (see fatal-exceptions.txt)."; cat "$out/fatal-exceptions.txt"; fi
if grep -q "FAILURES!!!\|INSTRUMENTATION_FAILED\|Process crashed" "$out/instrument.txt" || ! grep -q "^OK (" "$out/instrument.txt"; then
  echo "::error::Device tests failed."
  exit 1
fi
[ -s "$out/fatal-exceptions.txt" ] && exit 1
exit 0
