#!/data/data/com.termux/files/usr/bin/bash
# 165hz.sh (Termux:Boot) — reapplies per-app 165 Hz overrides after reboot.
# Flow: mDNS-discover phone's wireless-debugging port -> adb connect ->
#       push dex if needed -> arm rateId 7 for each package in apps.txt.
LOG=$HOME/165hz-boot.log
DIR="$(cd "$(dirname "$0")" && pwd)"
REPO="$DIR/../oneplus15-165hz"          # repo cloned into $HOME
APPS="$REPO/boot/apps.txt"
DEX=/data/local/tmp/SetGameRate.dex
LOCAL_DEX=$(ls "$REPO"/tool/SetGameRate.dex "$HOME/rootless-165hz/rate/classes.dex" 2>/dev/null | head -1)

{
echo "=== $(date) ==="
command -v termux-wake-lock >/dev/null && termux-wake-lock

command -v adb >/dev/null || { echo "adb missing"; exit 1; }

# --- discover the phone over mDNS (wireless debugging advertises itself) ---
PORTS=$(timeout 25 python3 - <<'PY'
from zeroconf import Zeroconf, ServiceBrowser
import socket, time
found = []
class L:
    def add_service(self, zc, t, name):
        i = zc.get_service_info(t, name)
        if i and i.addresses:
            found.append((socket.inet_ntoa(i.addresses[0]), i.port))
    def update_service(self, *a): pass
    def remove_service(self, *a): pass
zc = Zeroconf()
ServiceBrowser(zc, ["_adb-tls-connect._tcp.local.", "_adb-tcp-connect._tcp.local."], L())
time.sleep(18)
for ip, port in found:
    print(f"{ip}:{port}")
zc.close()
PY
) || PORTS=""
echo "discovered: ${PORTS:-none}"

TARGET=""
for p in $PORTS; do
  adb connect "$p" 2>/dev/null | grep -q connected && TARGET="$p" && break
done
[ -z "$TARGET" ] && { echo "no adb target found"; exit 1; }
echo "connected to $TARGET"

# --- ensure dex on device ---
if ! adb -s "$TARGET" shell "[ -f $DEX ]" </dev/null 2>/dev/null; then
  [ -n "$LOCAL_DEX" ] && [ -f "$LOCAL_DEX" ] && adb -s "$TARGET" push "$LOCAL_DEX" $DEX
fi

# --- reapply overrides ---
while read -r app; do
  [ -z "$app" ] && continue
  case "$app" in \#*) continue;; esac
  R=$(adb -s "$TARGET" shell CLASSPATH=$DEX app_process / SetGameRate "$app" 7 </dev/null)
  echo "$app -> $R"
done < "$APPS"

# --- master switch for the whitelisted-apps feature (harmless if set) ---
adb -s "$TARGET" shell settings put global app_extreme_high_refresh_switch 1 </dev/null

adb -s "$TARGET" shell dumpsys oplusscreenmode </dev/null | grep OifaceRequested
echo "done $(date)"
} >> "$LOG" 2>&1
