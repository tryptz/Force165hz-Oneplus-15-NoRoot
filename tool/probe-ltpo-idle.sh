#!/data/data/com.termux/files/usr/bin/bash
# probe-ltpo-idle.sh — why an armed app never lets the panel idle down to 1 Hz,
# and which knob on THIS build lets it.
#
# The problem, from the code: requestGameRefreshRate sets min = max = <rate>.
# A vote whose floor equals its ceiling is a pin, so while the armed app is
# foregrounded the LTPO ramp has nowhere to go — 165 Hz on a menu, on a paused
# video, on a still screen. What we want instead is a CEILING of 165 with the
# floor left at 1, which is the panel's own idle rate.
#
# This script does not fix that. It finds out which of the candidates below
# produces a vote with min < max on this device, so the winner can be wired
# into the app. It writes only the two AOSP settings keys and restores them on
# exit; everything else it does is a read or a per-app vote you can undo with
# `setrate.sh --clear <pkg>`.
#
# Usage:  ./tool/probe-ltpo-idle.sh [pkg]        (default: com.android.chrome)
#
# No dex needed: `service call` writes the binder's own interface token, so it
# can reach oplusscreenmode directly. If a call answers with a parcel error
# rather than a result, that build wants the app_process route setrate.sh uses.
#
# Read the SAMPLE blocks: min/max is the vote, and "renderFrameRate" or the
# vendor sysfs line is what the panel actually settled on after 3 idle seconds.
# Turn on Developer options -> "Show refresh rate" first; it is the one readout
# that never lies.

set -u
PKG="${1:-com.android.chrome}"

die() { echo "error: $*" >&2; exit 1; }
say() { printf '\n=== %s ===\n' "$*"; }

command -v adb >/dev/null || die "adb not found (pkg install android-tools)"
d=$(adb devices | awk '/\tdevice$/{print $1; exit}')
[ -z "$d" ] && die "no adb device (adb connect <ip>:<port> first)"
ADB="adb -s $d"

# ---------------------------------------------------------------- restore
ORIG_MIN=$($ADB shell settings get system min_refresh_rate </dev/null | tr -d '\r')
ORIG_PEAK=$($ADB shell settings get system peak_refresh_rate </dev/null | tr -d '\r')
restore() {
  say "restoring"
  for kv in "min_refresh_rate $ORIG_MIN" "peak_refresh_rate $ORIG_PEAK"; do
    set -- $kv
    if [ -z "${2:-}" ] || [ "${2:-}" = "null" ]; then
      $ADB shell settings delete system "$1" </dev/null >/dev/null
      echo "  system/$1 deleted (was unset)"
    else
      $ADB shell settings put system "$1" "$2" </dev/null
      echo "  system/$1 = $2"
    fi
  done
  echo "  the $PKG vote is still live — clear it with: ./setrate.sh --clear $PKG"
}
trap restore EXIT INT TERM

# ---------------------------------------------------------------- sampling
# The panel rate is reported in several places and not all of them exist on
# every build, so print whatever answers and let the reader pick.
sample() {
  echo "--- sample: $* (after 3 s untouched) ---"
  sleep 3
  $ADB shell dumpsys display </dev/null | grep -ioE \
    'mActiveModeId=[0-9]+|renderFrameRate=[0-9.]+|refreshRate=[0-9.]+|mRefreshRateInHz=[0-9.]+' \
    | sort -u | sed 's/^/  display: /'
  $ADB shell dumpsys display </dev/null | grep -iE 'vote|priority' | head -12 | sed 's/^/  vote: /'
  $ADB shell dumpsys SurfaceFlinger </dev/null \
    | grep -iE 'refresh rate|ActiveMode|FrameRateMode' | head -4 | sed 's/^/  sf: /'
  for f in /sys/kernel/oplus_display/measured_fps /proc/oplus_display/measured_fps \
           /sys/class/drm/sf-display/measured_fps; do
    v=$($ADB shell "cat $f 2>/dev/null" </dev/null | tr -d '\r')
    [ -n "$v" ] && echo "  $f: $v"
  done
}

say "0. panel: what modes exist"
$ADB shell dumpsys display </dev/null | tr ',' '\n' | grep -oE 'fps=[0-9.]+' | sort -u -t= -k2 -n
echo "(1 Hz will not be listed — the DDIC idles below the lowest mode on its own)"

say "0b. every refresh-rate setting this build carries"
$ADB shell settings list system </dev/null | grep -i refresh
$ADB shell settings list secure </dev/null | grep -i refresh
$ADB shell settings list global </dev/null | grep -iE 'refresh|extreme'

say "1. BASELINE — nothing armed, leave the screen alone"
sample baseline

say "2. PIN — $PKG armed at 165 the way the app does it today"
$ADB shell service call oplusscreenmode 12 s16 "$PKG" i32 7 </dev/null
echo "(foreground $PKG on the phone now, then leave it still)"
sample "pinned at 165"
echo ">> if the sampled rate stays 165 here, that is the bug this script is about"

say "3. CANDIDATE A — AOSP floor/ceiling: min 1, peak 165"
$ADB shell settings put system min_refresh_rate 1.0 </dev/null
$ADB shell settings put system peak_refresh_rate 165.0 </dev/null
$ADB shell settings get system min_refresh_rate </dev/null
sample "min=1 peak=165, still pinned"
echo ">> a floor of 1 cannot win against a pin of 165; this only pays off once"
echo ">> the pin is gone, so check it again after: ./setrate.sh --clear $PKG"

say "4. CANDIDATE B — setAppOverrideRefreshRate, one mode at a time"
# Transaction 25, args (String pkg, int mode, int rateId). The mode values are
# not documented anywhere we can read; this is the whole reason for the script.
# A mode that produces min < max in the vote dump is the per-app answer.
for mode in 0 1 2 3; do
  echo "--- mode=$mode rate=7 (165) ---"
  $ADB shell service call oplusscreenmode 25 s16 "$PKG" i32 "$mode" i32 7 </dev/null
  $ADB shell dumpsys display </dev/null | grep -iE 'vote|override' | head -8 | sed 's/^/  /'
  $ADB shell service call oplusscreenmode 25 s16 "$PKG" i32 "$mode" i32 0 </dev/null >/dev/null
done

say "5. CANDIDATE C — what the vendor's own 165 Hz whitelist does"
$ADB shell dumpsys oplusscreenmode </dev/null | grep -iE 'Oiface|override|white|list' | head -20
echo "(if OnePlus's own three 165 Hz apps idle down, their vote is the shape to copy)"

say "verdict"
cat <<'TXT'
Compare the SAMPLE blocks:
  * pinned at 165 never drops        -> min=max confirmed, the app needs a
                                        ceiling-only vote, not requestGameRefreshRate
  * a mode in step 4 shows min < max -> that is the per-app fix: wire
                                        RateLock.setAppOverride(pkg, rate, mode)
                                        into arm() behind a "let it idle" switch
  * only step 3 moves anything       -> the fix is global, not per app: the app
                                        would set system min_refresh_rate=1 and
                                        peak_refresh_rate=165 and drop the pin
Send the output and the three numbers from the "Show refresh rate" overlay.
TXT
