#!/data/data/com.termux/files/usr/bin/bash
# install.sh — one-time setup for boot persistence (Termux:Boot)
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"

command -v adb >/dev/null || { echo "pkg install android-tools first"; exit 1; }

# 1. boot script
mkdir -p ~/.termux/boot
cp "$DIR/boot/165hz.sh" ~/.termux/boot/165hz.sh
chmod +x ~/.termux/boot/165hz.sh

# 2. default app list if absent
[ -f "$DIR/boot/apps.txt" ] || cat > "$DIR/boot/apps.txt" <<EOF
# one package name per line, locked to 165 Hz after every reboot
com.example.app
EOF

echo "installed ~/.termux/boot/165hz.sh"
echo "edit $DIR/boot/apps.txt to choose apps, then test with:"
echo "  bash ~/.termux/boot/165hz.sh && tail ~/165hz-boot.log"
