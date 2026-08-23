#!/usr/bin/env bash
# Build :wear and sideload it to the watch. Run from anywhere in the repo.
#
# Must run inside `nix develop` (needs ANDROID_HOME, JDK 17, and the GRADLE_OPTS
# that force AGP onto the nix-patched aapt2). See flake.nix. Note `adb` itself only
# exists inside that shell — a bare `adb` on the ambient PATH is "command not found",
# which misreads as an unplugged device.
#
# Wireless adb does NOT survive a watch reboot. When the serial goes stale:
#   nmap -Pn --open -p 30000-49999 <watchIp>   # find the new debug port
#   adb connect <watchIp>:<port>               # re-pair first if that fails
set -euo pipefail

WATCH="${WATCH:-192.168.1.166:36035}"
PKG=com.liftosaur.www.fork
ACTIVITY="$PKG/com.liftosaur.wear.MainActivity"

repo="$(git rev-parse --show-toplevel)"
apk="$repo/android/wear/build/outputs/apk/debug/wear-debug.apk"

if [ -z "${ANDROID_HOME:-}" ]; then
  echo "error: ANDROID_HOME unset — run inside 'nix develop'" >&2
  exit 1
fi

# Concurrent Gradle builds in this repo corrupt :wear intermediates and surface as a
# bogus "mergeDebugResources ... values.xml (No such file or directory)".
if pgrep -f "GradleDaemon" >/dev/null 2>&1 && [ "${FORCE:-}" != "1" ]; then
  echo "note: a Gradle daemon is already running; set FORCE=1 to build anyway" >&2
fi

echo "==> building :wear:assembleDebug"
(cd "$repo/android" && ./gradlew :wear:assembleDebug)

echo "==> installing to $WATCH"
adb -s "$WATCH" install -r "$apk"

# The watch dozes within seconds and `svc power stayon true` is a no-op over wireless
# adb (it only applies to USB power), so hold the screen on for a review session.
adb -s "$WATCH" shell settings put system screen_off_timeout 600000 || true
adb -s "$WATCH" shell input keyevent KEYCODE_WAKEUP || true

echo "==> launching"
adb -s "$WATCH" shell am start -n "$ACTIVITY"

echo
echo "apk: $(du -h "$apk" | cut -f1)"
echo "look at the watch. to grab a screenshot:"
echo "  adb -s $WATCH exec-out screencap -p > shot.png"
