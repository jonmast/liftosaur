#!/usr/bin/env bash
# Drive the app over adb, so the 192dp screen doesn't have to be poked to reach a screen.
#
#   ./ctl.sh screen home|exercises|detail|engine
#   ./ctl.sh entry 0|1|2                  which exercise detail opens
#   ./ctl.sh reseed                       reset to the bundled fixture storage (debug only)
#
# Combine freely:  ./ctl.sh reseed entry 1 screen detail
#
# Why this exists: screenshots of this app are impossible (the doze dream composites above
# our window, and uiautomator sees no Compose nodes), so every visual check is a human
# looking at their wrist. Reaching the screen under review must not also be manual.
set -euo pipefail

WATCH="${WATCH:-$("$(dirname "$0")/watch-serial.sh")}"
PKG=com.liftosaur.www.fork
RECEIVER="$PKG/com.liftosaur.wear.RemoteReceiver"

if [ $# -eq 0 ]; then
  sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'
  exit 0
fi

args=()
while [ $# -gt 0 ]; do
  case "$1" in
    reseed) args+=(--es reseed 1); shift ;;
    screen|entry)
      [ $# -ge 2 ] || { echo "error: $1 needs a value" >&2; exit 1; }
      args+=(--es "$1" "$2"); shift 2 ;;
    *) echo "error: unknown option '$1'" >&2; exit 1 ;;
  esac
done

# The app must be foreground for the nav change to be visible; waking first avoids
# broadcasting into a dozing screen and wondering why nothing happened.
adb -s "$WATCH" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb -s "$WATCH" shell am broadcast -n "$RECEIVER" "${args[@]}" >/dev/null
echo "sent: ${args[*]}"
