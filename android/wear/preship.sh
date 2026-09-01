#!/usr/bin/env bash
# Ticket 07: re-measure the spec §4 budgets on the RELEASE build, on the real watch, against
# the real synced account — and print the verdict table.
#
#   ./preship.sh                 build release, size it, install, run the benchmark
#   ./preship.sh --apk-only      just the build + APK size budget (no watch needed)
#   ./preship.sh --fresh         `pm clear` first: first-run empty state + the `seq` test
#   ./preship.sh --no-build      use the APK already in build/outputs
#
# Must run inside `nix develop` — `adb` exists only there, and a bare `adb` on the ambient
# PATH is "command not found", which misreads as an unplugged watch.
#
# Why release and not debug: Compose debug builds carry composition tracking and run
# unoptimized, so latency measured on `debug` is not evidence about the shipped thing.
#
# Why a human is still in the loop: the benchmark runs from the Engine screen, and reaching it
# is a tap on the build-identity line at the bottom of Home. The debug-only `ctl.sh screen
# engine` broadcast does not exist in a release build, and adding an exported trigger to the
# shipped app to save one tap is a worse trade than the tap.
set -uo pipefail

PKG=com.liftosaur.www.fork
ACTIVITY="$PKG/com.liftosaur.wear.MainActivity"
BUDGET_KB=1536   # spec §4: APK delta for engine + bundle

apk_only=0
fresh=0
build=1
for arg in "$@"; do
  case "$arg" in
    --apk-only) apk_only=1 ;;
    --fresh)    fresh=1 ;;
    --no-build) build=0 ;;
    -h|--help)  sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "error: unknown option '$arg'" >&2; exit 1 ;;
  esac
done

repo="$(git rev-parse --show-toplevel)" || exit 1
apk="$repo/android/wear/build/outputs/apk/release/wear-release.apk"

if [ -z "${ANDROID_HOME:-}" ]; then
  echo "error: ANDROID_HOME unset — run inside 'nix develop'" >&2
  exit 1
fi

# ---------------------------------------------------------------------------------------
# 1. Build
# ---------------------------------------------------------------------------------------
if [ "$build" = 1 ]; then
  echo "==> building :wear:assembleRelease"
  (cd "$repo/android" && ./gradlew :wear:assembleRelease) || exit 1
fi

[ -f "$apk" ] || { echo "error: no APK at $apk" >&2; exit 1; }

# ---------------------------------------------------------------------------------------
# 2. APK delta — the one §4 budget that needs no device
# ---------------------------------------------------------------------------------------
# Measured as the *stored* size of the engine's shared library plus the baked bundle and
# prelude, because that is what the download and the on-disk install actually cost. The bundle
# is deliberately stored uncompressed (`noCompress 'js'`) so it can be mmap'd out of the APK
# rather than inflated into the Java heap on every cold start — so its compressed and
# uncompressed sizes are the same number, on purpose.
command -v python3 >/dev/null || { echo "error: python3 not on PATH (needed to size the APK)" >&2; exit 1; }

echo
echo "==> APK contents ($(basename "$apk"))"
python3 - "$apk" "$BUDGET_KB" <<'PY'
import sys, zipfile

apk, budget_kb = sys.argv[1], int(sys.argv[2])
z = zipfile.ZipFile(apk)
by_name = {i.filename: i for i in z.infolist()}

def size(name):
    i = by_name.get(name)
    return (i.compress_size, i.file_size) if i else (0, 0)

engine = [n for n in by_name if n.endswith("libliftosaur_engine.so")]
parts = engine + ["assets/watch-bundle.js", "assets/prelude.js"]

total = 0
for name in parts:
    c, u = size(name)
    total += c
    print(f"  {name:<50} {c/1024:8.1f}KB in APK  ({u/1024:.1f}KB on disk)")

# Not part of the budget, but shipped: the instrumentation fixtures live in main/assets, so a
# release build carries them too. Worth seeing rather than discovering later.
extra = sorted(n for n in by_name if n.startswith("assets/fixture-"))
if extra:
    print("  ---- also shipped (test fixtures, not part of the budget) ----")
    for name in extra:
        c, _ = size(name)
        print(f"  {name:<50} {c/1024:8.1f}KB")

apk_total = sum(i.compress_size for i in z.infolist())
verdict = "OK" if total / 1024 <= budget_kb else "OVER"
print()
print(f"  engine + bundle   {total/1024:8.1f}KB   (budget {budget_kb}KB)   {verdict}")
print(f"  whole APK         {apk_total/1024:8.1f}KB   ({100*total/apk_total:.1f}% is engine + bundle)")
sys.exit(0 if verdict == "OK" else 2)
PY
apk_verdict=$?

if [ "$apk_only" = 1 ]; then
  exit "$apk_verdict"
fi

# ---------------------------------------------------------------------------------------
# 3. Install onto the watch
# ---------------------------------------------------------------------------------------
WATCH="${WATCH:-$("$(dirname "$0")/watch-serial.sh")}" || exit 1
echo
echo "==> installing to $WATCH"
adb -s "$WATCH" install -r "$apk" || exit 1

if [ "$fresh" = 1 ]; then
  # Wipes storage.json, the deviceId, and the last-seen `seq`. This is both the first-run
  # empty-state check and the `seq` test from ticket 05: the phone's DataItem bytes have not
  # changed, so a watch that only reacted to *changes* would sit empty forever.
  echo "==> pm clear (fresh install state)"
  adb -s "$WATCH" shell pm clear "$PKG" >/dev/null
fi

# A fresh process is required or the cold-start number is meaningless — the engine is
# initialized once per process and the measurement is recorded where it happens.
adb -s "$WATCH" shell am force-stop "$PKG"
adb -s "$WATCH" shell settings put system screen_off_timeout 600000 >/dev/null 2>&1 || true
adb -s "$WATCH" logcat -c
adb -s "$WATCH" shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
adb -s "$WATCH" shell am start -n "$ACTIVITY" >/dev/null

cat <<'EOF'

==> on the watch:
    1. let Home finish loading (that is the real cold start being measured)
    2. tap the build-identity line at the bottom of Home to open Engine
    3. leave the screen on until the numbers appear

    waiting for the benchmark (5 min timeout)...
EOF

# ---------------------------------------------------------------------------------------
# 4. Collect
# ---------------------------------------------------------------------------------------
# Tee to a file rather than /dev/stderr: this script is often run non-interactively (CI, a
# background shell, anything without a terminal), and there /dev/stderr is not openable —
# `tee` dies instantly, `grep` reads an empty stream, and the script burns its whole timeout
# before blaming the watch for a failure that happened here. The file also survives the run,
# which is the difference between diagnosing a missing benchmark line and re-running blind.
logfile="${TMPDIR:-/tmp}/preship-logcat-$$.log"
echo "    (full logcat: $logfile)"
line=$(timeout 300 adb -s "$WATCH" logcat -s PreShipBench:I EngineSelfTest:I LiftosaurEngine:I \
       | tee "$logfile" | grep -m1 "PRESHIP verdict=")

if [ -z "$line" ]; then
  echo
  echo "error: no benchmark line seen in 5 minutes." >&2
  echo >&2
  if [ ! -s "$logfile" ]; then
    # Nothing at all arrived — not even the engine's own init line. That is a connection or a
    # launch problem, not a sleeping watch.
    echo "  Nothing was captured on logcat at all: $logfile is empty." >&2
    echo "  Check 'adb devices -l' shows the watch as 'device' (not 'offline')," >&2
    echo "  and that $PKG actually launched." >&2
  else
    echo "  Logcat was flowing ($(wc -l < "$logfile") lines) but no PRESHIP line appeared —" >&2
    echo "  so the app ran and the Engine screen was probably never opened." >&2
    echo "  On the watch: tap the build-identity line at the bottom of Home." >&2
    echo "  Last few lines:" >&2
    tail -5 "$logfile" | sed 's/^/    /' >&2
  fi
  echo >&2
  echo "  If the watch slept, it drops Wi-Fi — keep the screen on and re-run." >&2
  exit 1
fi

echo
echo "==> spec §4 budgets, release build, real account"
echo "$line" | tr ' ' '\n' | grep '=' | awk -F= '
  BEGIN {
    b["coldStartMs"]="1500"; b["warmReadMs"]="50"; b["mutationMs"]="1000"
    b["engineAnonKb"]="8192"; b["sessionAnonKb"]="65536"
  }
  {
    budget = ($1 in b) ? "  (budget " b[$1] ")" : ""
    printf "  %-16s %s%s\n", $1, $2, budget
  }'

echo
echo "$line" | grep -q "verdict=PASS" && echo "  VERDICT: PASS" || echo "  VERDICT: FAIL"
echo
echo "Still human-judged, and not in the table above:"
echo "  - did the watch suspend mid-workout with the screen on? (§5.1 wake-lock risk)"
echo "  - did the first-run empty state render sanely? (--fresh, no phone nearby)"
