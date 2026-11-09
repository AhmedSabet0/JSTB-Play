#!/bin/bash
# Runs on an Android emulator (GitHub Actions): installs the app, opens it, sends it to the background and back, and
# checks that it is still running. Prints the crash when there is one. Usage: smoke.sh <folder with the APK>
set -u
DIST=${1:-dist}
APK=$(ls "$DIST"/*.apk | head -n 1)
PKG=com.jstb
echo "Installing $APK"
adb install -r "$APK" || { echo "::error::Android refused to install the APK"; exit 1; }
adb logcat -c || true
adb shell am start -W -n "$PKG/com.autoapply.bot.app.MainActivity"
sleep 25
adb shell input keyevent KEYCODE_HOME
sleep 3
adb shell am start -W -n "$PKG/com.autoapply.bot.app.MainActivity"
sleep 10
PID=$(adb shell pidof "$PKG" | tr -d '\r')
adb logcat -d -b crash > "$DIST/crash.txt" 2>/dev/null || true
adb logcat -d > "$DIST/logcat.txt" 2>/dev/null || true
adb exec-out screencap -p > "$DIST/screen.png" 2>/dev/null || true
echo "== JavaScript console"
grep -E "CONSOLE|Uncaught" "$DIST/logcat.txt" | grep -i "$PKG\|chromium" | tail -n 20 || true
if [ -s "$DIST/crash.txt" ] || [ -z "$PID" ]; then
  echo "::error::The app crashed or stopped on start"
  echo "== crash"
  cat "$DIST/crash.txt"
  echo "== AndroidRuntime"
  grep -E -A 40 "FATAL EXCEPTION|AndroidRuntime" "$DIST/logcat.txt" | head -n 120
  exit 1
fi
echo "The app opened, went to the background and came back, and keeps running (pid $PID)"
