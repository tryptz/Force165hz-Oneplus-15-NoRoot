#!/data/data/com.termux/files/usr/bin/bash
# probe-vendor.sh — read-only report on the OPlus vendor services the app uses.
#
# Everything here only reads state; nothing is written and no binder
# transaction is issued. Use it to confirm on-device what the static analysis
# of oplus-framework.jar predicted:
#
#   oplusscreenmode  com.oplus.screenmode.IOplusScreenMode   (unguarded)
#     12 requestGameRefreshRate(String pkg, int rateId) -> boolean
#     14 getGameList(Bundle) -> boolean
#     25 setAppOverrideRefreshRate(String pkg, int mode, int rate) -> boolean
#     26 getAppOverrideRefreshRate(String pkg, int mode) -> int
#     27 getAppOverrideRefreshRateList() -> Bundle
#
#   oplusoiface      com.oplus.oiface.IOIfaceService         (native guard, unknown)
#     851  getFPS(String pkg, int type) -> int
#     863  getGpuLoad() -> float
#     1007 setGameModeStatus(int status, String pkg)
#     1009 getCurrentGamePackage() -> String
#     1010 setInstalledGameList(String[])
#     1011 getInstalledGameList() -> String[]

set -u
ADB=${ADB:-adb}

hdr() { printf '\n===== %s =====\n' "$1"; }

command -v "$ADB" >/dev/null || { echo "adb not found (pkg install android-tools)"; exit 1; }
"$ADB" get-state >/dev/null 2>&1 || { echo "no adb device; run 'adb connect <ip>:<port>' first"; exit 1; }

hdr "vendor services present"
"$ADB" shell service list 2>/dev/null | grep -iE 'oplus|oiface|game|screen|display|refresh'

hdr "screenmode state (per-app overrides + votes)"
"$ADB" shell dumpsys oplusscreenmode 2>/dev/null | grep -iE 'override|oiface|vote|game|refresh' | head -40

hdr "oiface state"
"$ADB" shell dumpsys oiface 2>/dev/null | head -30 ||
  echo "(no dumpsys oiface — the daemon may not expose one)"

hdr "refresh / game related global settings"
"$ADB" shell settings list global 2>/dev/null | grep -iE 'refresh|game|fps|render|hyper'

hdr "app_extreme_high_refresh_switch"
"$ADB" shell settings get global app_extreme_high_refresh_switch 2>/dev/null

hdr "is WRITE_SECURE_SETTINGS granted to tf.arm165?"
"$ADB" shell dumpsys package tf.arm165 2>/dev/null |
  grep -i 'WRITE_SECURE_SETTINGS' ||
  echo "not granted — run:
  adb shell pm grant tf.arm165 android.permission.WRITE_SECURE_SETTINGS"

hdr "display modes the panel actually advertises"
"$ADB" shell dumpsys display 2>/dev/null | grep -iE 'mode [0-9]+:|refreshRate' | head -20

echo
echo "Render rate vs display rate: while a boosted game is foregrounded, run"
echo "  $ADB shell dumpsys SurfaceFlinger --latency \"<layer name>\""
echo "A 165 Hz panel with a 60 fps game still shows ~16.6ms frame intervals there."
