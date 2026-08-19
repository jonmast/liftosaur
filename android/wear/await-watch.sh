#!/usr/bin/env bash
# Wait for the watch to rejoin the network, then install and launch.
#
# Wear OS drops Wi-Fi when the screen is off and the phone is in Bluetooth range, so the
# adb endpoint vanishes without warning. The debug port also changes whenever wireless
# debugging is toggled, so a stale port is rediscovered by scanning rather than assumed.
#
#   ./await-watch.sh [apk] [timeoutSeconds]
set -uo pipefail

IP="${WATCH_IP:-192.168.1.166}"
LAST_PORT="${WATCH_PORT:-36035}"
APK="${1:-android/wear/build/outputs/apk/release/wear-release.apk}"
DEADLINE=$(( $(date +%s) + ${2:-900} ))

cd "$(git rev-parse --show-toplevel)" || exit 1

discover_ip() {
  # A reboot or new DHCP lease moves the watch, so fall back to finding it by hostname
  # rather than giving up on the last-known address.
  local found
  found=$(nmap -sn "${SUBNET:-192.168.1.0/24}" 2>/dev/null \
          | grep -iB2 -E "watch|aurora" \
          | grep -oE "192\.168\.[0-9]+\.[0-9]+" | head -1)
  [ -n "$found" ] && echo "$found"
}

find_endpoint() {
  if ! ping -c1 -W1 "$IP" >/dev/null 2>&1; then
    local moved
    moved=$(discover_ip) || return 1
    [ -n "$moved" ] || return 1
    echo "watch moved: $IP -> $moved" >&2
    IP="$moved"
  fi
  if timeout 10 adb connect "$IP:$LAST_PORT" 2>&1 | grep -q "^connected\|already connected"; then
    echo "$IP:$LAST_PORT"; return 0
  fi
  local port
  port=$(timeout 120 nmap -Pn -T4 --open -p 30000-49999 "$IP" 2>/dev/null \
         | grep -oE "^[0-9]+/tcp" | cut -d/ -f1 | head -1)
  [ -n "$port" ] || return 1
  timeout 10 adb connect "$IP:$port" 2>&1 | grep -q "^connected" || return 1
  echo "$IP:$port"
}

echo "waiting for watch at $IP (wake it to bring Wi-Fi back)..."
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
