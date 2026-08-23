#!/usr/bin/env bash
# Resolve the adb serial of the Pixel Watch 2, however it happens to be attached.
#
#   WATCH=$(./watch-serial.sh) || exit 1
#
# Why this exists: the watch's endpoint is not stable and neither is the way you reach it.
# Wear OS drops Wi-Fi when the screen is off and the phone is in Bluetooth range, DHCP moves
# it, and toggling wireless debugging reassigns the debug port. Hardcoding `<ip>:<port>` (as
# these scripts used to) guarantees a stale serial within days.
#
# What IS stable is the device's own identity: `device:aurora` / `model:Google_Pixel_Watch_2`
# in `adb devices -l`. That holds whether adb attached over mDNS, a manual `adb connect`, or
# USB, so match on it rather than on an address.
#
# Two traps this avoids, both hit on 2026-08-23:
#   - Scanning the subnet for an "android"-looking hostname finds an ESP32 that calls itself
#     `android-5401faaf191c46de`, while the watch broadcasts no hostname at all. Hostname
#     matching picks the decoy.
#   - A dead `<ip>:<port>` transport lingers in `adb devices` as `offline` and shadows the
#     live one. Offline entries are skipped here, and worth `adb disconnect`ing.
#
# If nothing is found: open Settings > Developer options > Wireless debugging ON THE WATCH.
# Merely opening that screen re-advertises the device over mDNS and adb reattaches on its
# own, using the existing pairing — no `adb pair`, no code, no port hunting.
set -uo pipefail

serial=$(adb devices -l 2>/dev/null \
         | grep -w device \
         | grep -E "device:aurora|model:Google_Pixel_Watch_2" \
         | awk '{print $1}' | head -1)

if [ -z "$serial" ]; then
  cat >&2 <<'EOF'
error: no Pixel Watch 2 attached to adb.

  On the watch: Settings > Developer options > Wireless debugging
  Just opening that screen is usually enough — adb reattaches over mDNS.

  Keep the screen awake; the watch drops Wi-Fi when it sleeps near the phone.
EOF
  offline=$(adb devices 2>/dev/null | grep -w offline | awk '{print $1}')
  if [ -n "$offline" ]; then
    echo "" >&2
    echo "stale offline transports (clear these):" >&2
    echo "$offline" | sed 's/^/  adb disconnect /' >&2
  fi
  exit 1
fi

echo "$serial"
