#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$(dirname "$0")"
pkg install -y openjdk-17 d8 >/dev/null 2>&1 || true
javac -nowarn SetGameRate.java
d8 --release --min-api 26 --output . SetGameRate*.class
mv classes.dex SetGameRate.dex
echo "built SetGameRate.dex"
