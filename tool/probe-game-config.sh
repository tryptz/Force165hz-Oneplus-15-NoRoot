#!/data/data/com.termux/files/usr/bin/bash
# probe-game-config.sh — can a shell-uid process reach a game's settings files?
#
# Shizuku runs apps' calls as the shell user (uid 2000), which is exactly what
# `adb shell` gives you. So whatever this script can read, a Shizuku-backed
# in-app config editor could read too; whatever it can't, only root could.
#
# Usage:  ./tool/probe-game-config.sh com.roblox.client
#
# Read-only: it lists and greps, never writes.

set -u
PKG="${1:-com.roblox.client}"

die() { echo "error: $*" >&2; exit 1; }

d=$(adb devices | awk '/\tdevice$/{print $1; exit}')
[ -z "$d" ] && die "no adb device (adb connect <ip>:<port> first)"
ADB="adb -s $d"

say() { printf '\n=== %s ===\n' "$*"; }

say "package: $PKG"
$ADB shell "pm path $PKG" </dev/null || die "$PKG not installed"

# --- 1. external app dir: Shizuku CAN reach this -----------------------------
say "1. /sdcard/Android/data/$PKG  (reachable via Shizuku)"
$ADB shell "ls -la /sdcard/Android/data/$PKG/ 2>&1 | head -30" </dev/null

say "1b. config-shaped files under it (depth 4)"
$ADB shell "find /sdcard/Android/data/$PKG/ -maxdepth 4 \
  \( -iname '*.json' -o -iname '*.ini' -o -iname '*.xml' -o -iname '*.cfg' \
     -o -iname '*.sav' -o -iname '*settings*' -o -iname '*config*' \) \
  2>/dev/null | head -40" </dev/null

# --- 2. internal data dir: root only ----------------------------------------
say "2. /data/data/$PKG  (root only — expect Permission denied)"
$ADB shell "ls /data/data/$PKG/ 2>&1 | head -10" </dev/null

say "2b. shared_prefs (where Unity/Roblox-style settings usually live)"
$ADB shell "ls /data/data/$PKG/shared_prefs/ 2>&1 | head -20" </dev/null

# --- 3. the vendor's own per-game fps table ---------------------------------
say "3. OxygenOS per-game FPS table"
$ADB shell "getprop persist.oplus.display.gamecustomfps" </dev/null
for f in /my_product/etc/oplus_games_fps.json /data/system/oplus_games_fps.json; do
  echo "--- $f"
  $ADB shell "ls -la $f 2>&1; head -c 400 $f 2>&1" </dev/null; echo
done

say "verdict"
cat <<'EOF'
Look at section 1b:
  * config files listed  -> a Shizuku-backed editor CAN edit them. Send me the
                            listing and I will build the editor around it.
  * nothing but caches   -> the game keeps settings in its private dir, so only
                            root could touch them; Shizuku will not help.
Section 2 is expected to fail on any non-rooted device; that is the whole point.
EOF
