# 165 Armer (`armer-app/`)

Rootless per-app refresh-rate unlock (60 / 90 / 120 / 144 / 165 Hz) for the
OnePlus 15 (CPH2747/CPH2749, OxygenOS 16). No root, no Magisk, no bootloader
unlock.

<p align="center">
  <img src="screenshot.png" width="320" alt="165 Armer screenshot">
</p>

## Features

- Per-app rate pinning with a searchable app list (All / Armed / Games /
  User / System filters) and arm-at-rate selector
- **Arm all / Re-arm / Clear** sweeps; armed set persists across reboots
- **Watchdog service** re-issues every vote every 5 s while the screen is on,
  beating games that pin their own frame rate
- **FPS overlay** in the status bar: real panel Hz plus a foreground game's
  rendered fps (needs "Display over other apps")
- Follows the system theme and wallpaper (Material You), edge to edge

## How it works

Calls the unguarded vendor IPC (no permission check):

```
service:  "oplusscreenmode"
iface:    com.oplus.screenmode.IOplusScreenMode
transact: 0x0c   requestGameRefreshRate(String packageName, int rateId)
```

Rate ids from `refresh_rate_config.xml`: `1`=90, `2`=60, `3`=120, `4`=144,
`7`=165 — note the ids are not in Hz order, 90 comes before 60. The vote
is `min=max=<rate>` while the app is foregrounded. Re-issuing an app's
pinned id removes the override. Ids/transactions were recovered from
`oplus-framework.jar` with `jadx`.

## Build & install

```bash
proot-distro login ubuntu -- bash -lc '
  export ANDROID_HOME=/root/android-sdk
  cd /data/data/com.termux/files/home/Force165hz-Oneplus-15-NoRoot/armer-app
  ./gradlew assembleDebug --offline'
cp app/build/outputs/apk/debug/app-debug.apk ~/storage/downloads/arm165-debug.apk
```

Output: `app/build/outputs/apk/debug/app-debug.apk` (debug-signed, package
`tf.arm165`). Tap to install from Downloads; grant the notification
permission on first launch.

## Notes

- The armed app must be foregrounded to receive the vote.
- Video apps may judder — disarm them or pin at 60 Hz.
- Battery and heat increase with the number of armed apps.
- A `SecurityException` after an OTA means the vendor patched the trick.

**Use at your own risk.** Undocumented vendor IPC; battery/thermal
disclaimers apply.
