#!/data/data/com.termux/files/usr/bin/bash
# setrate.sh — OnePlus 15 rootless per-app 165 Hz unlocker
#
# Reverse-engineered chain (OxygenOS 16.1):
#   oplusscreenmode binder -> IOplusScreenMode::requestGameRefreshRate
#   transaction 0x0c, args (String pkg, int rateId), NO caller permission check.
#   rateIds: 1=90 2=60 3=120 4=144 7=165 (same ids as refresh_rate_config.xml).
#   The call is a set, not a toggle: handing it an id it already holds only
#   writes the same pin back. Withdraw one with rateId 0 (--clear <pkg>);
#   a reboot clears the vendor's whole map.
#
# usage:  setrate.sh <pkg> [rate]      pin (rate as Hz or rateId, default 165)
#         setrate.sh --clear <pkg>     withdraw that app's pin
#         setrate.sh --all             pin every package at 165
#         setrate.sh --list|--watch    dump the vendor's map / follow the log

set -u

DEX=/data/local/tmp/SetGameRate.dex
DIR="$(cd "$(dirname "$0")" && pwd)"
LOCAL_DEX="$DIR/tool/SetGameRate.dex"

die() { echo "error: $*" >&2; exit 1; }

pick_device() {
  # prefer an already-connected device
  local d
  d=$(adb devices | awk '/\tdevice$/{print $1; exit}')
  if [ -z "$d" ]; then
    echo "no adb device connected." >&2
    echo "connect first, e.g.:  adb connect <phone-ip>:<port>" >&2
    echo "(wireless debugging port changes after reboot — use boot/165hz.sh)" >&2
    exit 1
  fi
  ADB="adb -s $d"
}

ensure_dex() {
  if ! $ADB shell "[ -f $DEX ]" >/dev/null 2>&1; then
    [ -f "$LOCAL_DEX" ] || die "$LOCAL_DEX missing — build it (see README)"
    adb push "$LOCAL_DEX" $DEX >/dev/null || die "push failed"
  fi
}

arm() { # arm <pkg> <rateId>
  # </dev/null: adb otherwise eats the caller's stdin inside loops
  $ADB shell CLASSPATH=$DEX app_process / SetGameRate "$1" "$2" </dev/null
}

case "${1:-}" in
  ""|-h|--help)
    sed -n '2,16p' "$0"; exit 0 ;;
  --clear)
    [ $# -ge 2 ] || die "usage: $0 --clear <pkg>"
    pick_device
    ensure_dex
    OUT=$(arm "$2" 0)
    echo "$OUT"
    case "$OUT" in
      *"result=1"*) echo "[ok] $2 released" ;;
      *) echo "[??] vendor refused rateId 0 — reboot to clear it" ;;
    esac
    exit 0 ;;
  --list)
    pick_device
    $ADB shell dumpsys oplusscreenmode | grep -E 'OifaceRequested|override list'
    exit 0 ;;
  --watch)
    pick_device
    $ADB shell logcat -s RefreshRate | grep --line-buffered -iE 'rateId=|requestRefreshRate|changing from'
    exit 0 ;;
  --all)
    pick_device
    ensure_dex
    ok=0; fail=0
    for pkg in $($ADB shell pm list packages | tr -d '\r' | sed 's/package://' | grep -v '^android$'); do
      if arm "$pkg" 7 2>/dev/null | grep -q "result=1"; then
        ok=$((ok+1))
      else
        fail=$((fail+1)); echo "  [skip] $pkg"
      fi
    done </dev/null
    echo "[done] armed=$ok skipped=$fail"
    $ADB shell dumpsys oplusscreenmode | grep OifaceRequested | head -c 400; echo
    exit 0 ;;
esac

PKG="$1"
RATE="${2:-7}"
case "$RATE" in 165) RATE=7;; 144) RATE=4;; 120) RATE=3;; 90) RATE=1;; 60) RATE=2;; off|none) RATE=0;; esac

command -v adb >/dev/null || die "adb not found (pkg install android-tools)"
adb devices | grep -q . || true
pick_device
ensure_dex

OUT=$(arm "$PKG" "$RATE")
echo "$OUT"
case "$OUT" in
  *"result=1"*) echo "[ok] $PKG -> rateId $RATE" ;;
  *)            echo "[??] unexpected response — run '$0 --watch' and check logcat -s RefreshRate" ;;
esac
