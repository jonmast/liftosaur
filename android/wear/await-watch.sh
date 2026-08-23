#!/usr/bin/env bash
# Wait for the watch to rejoin the network, then install and launch.
#
# Wear OS drops Wi-Fi when the screen is off and the phone is in Bluetooth range, so the
# adb endpoint vanishes without warning. The debug port also changes whenever wireless
# debugging is toggled, so a stale port is rediscovered by scanning rather than assumed.
#
#   ./await-watch.sh [apk] [timeoutSeconds]
#
# Discovery is by device identity (./watch-serial.sh), NOT by address. The previous
# scan-the-subnet-for-a-watchy-hostname approach was tried and abandoned on 2026-08-23: the
# watch broadcasts no hostname, while an ESP32 on the LAN calls itself
# `android-5401faaf191c46de`, so hostname matching reliably picked the wrong device. Port
# scanning fared no better — the watch's debug ports answer TCP but refuse the TLS handshake
# once pairing lapses, which surfaces as `offline`, not as a connection failure.
#
# The reliable recovery is on the watch itself: opening Settings > Developer options >
# Wireless debugging re-advertises it over mDNS and adb reattaches using the existing
# pairing. This script waits for exactly that.
set -uo pipefail

APK="${1:-android/wear/build/outputs/apk/release/wear-release.apk}"
DEADLINE=$(( $(date +%s) + ${2:-900} ))

cd "$(git rev-parse --show-toplevel)" || exit 1

find_endpoint() {
  android/wear/watch-serial.sh 2>/dev/null
}

echo "waiting for the watch to reattach..."
echo "  wake it, and open Settings > Developer options > Wireless debugging"
while [ "$(date +%s)" -lt "$DEADLINE" ]; do
  if D=$(find_endpoint); then
    echo "WATCH BACK: $D"
    adb -s "$D" install -r "$APK" 2>&1 | tail -1
    adb -s "$D" shell "input keyevent KEYCODE_WAKEUP; sleep 1; input tap 192 192; sleep 1; \
      am start -n com.liftosaur.www.fork/com.liftosaur.wear.MainActivity" >/dev/null 2>&1
    sleep 3
    adb -s "$D" shell am broadcast \
      -n com.liftosaur.www.fork/com.liftosaur.wear.RemoteReceiver \
      --es detail hero --es entry 3 --es screen detail >/dev/null 2>&1
    echo "crash lines: $(adb -s "$D" logcat -d -t 120 2>/dev/null | grep -icE 'FATAL|AndroidRuntime')"
    echo "READY on $D"
    exit 0
  fi
  sleep 10
done
echo "TIMED OUT — watch never came back"
exit 1
