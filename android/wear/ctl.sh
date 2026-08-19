#!/usr/bin/env bash
# Drive the prototype's variants over adb, so the 192dp screen doesn't have to be poked.
#
#   ./ctl.sh detail hero|anchored|row     switch the exercise-detail layout
#   ./ctl.sh prompt paged|form            switch the set-completion prompt layout
#   ./ctl.sh worst 0|1                    one-field vs five-field worst case
#   ./ctl.sh screen home|exercises|detail|prompt|variants
#   ./ctl.sh entry 0|1|2                  which exercise detail opens (0=Row,1=Bench,2=Squat)
#   ./ctl.sh reset                        restore the stub workout
#
# Combine freely:  ./ctl.sh detail row entry 1 screen detail
set -euo pipefail

WATCH="${WATCH:-192.168.1.166:36035}"
PKG=com.liftosaur.www.fork
RECEIVER="$PKG/com.liftosaur.wear.RemoteReceiver"

if [ $# -eq 0 ]; then
  sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
  exit 0
fi

args=()
while [ $# -gt 0 ]; do
  case "$1" in
    reset) args+=(--es reset 1); shift ;;
    detail|prompt|worst|screen|entry)
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
