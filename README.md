# 165 Armer (`armer-app/`)

Rootless per-app 165 Hz unlock for the OnePlus 15 (CPH2747/CPH2749,
OxygenOS 16 / Android 16). No root, no Magisk, no bootloader unlock.

## What the APK does

- Lists every installed app with its icon and a toggle — arm/disarm **165 Hz**
  per app
- Search by app name or package, plus **All / Armed / User / System** filters
- A status card up top: how many apps are armed, coverage across everything
  installed, and whether the watchdog is live
- **Arm all** (system-wide sweep), **Re-arm** (re-applies your saved selection)
  and **Clear** (disarms everything) in a floating action bar
- Follows the system light/dark theme, draws edge to edge under the status and
  navigation bars
- Armed apps are persisted and automatically re-applied after reboot
  (`BOOT_COMPLETED` receiver)
- **Watchdog foreground service** re-issues the 165 Hz vote for all armed apps
  every 5 s while the screen is on — this beats games (Unity/Unreal etc.) that
  pin their own frame rate on focus and would otherwise override a one-shot vote.
  Pauses when the screen is off.

Under the hood it calls the unguarded vendor IPC:

```
service:  "oplusscreenmode"
iface:    com.oplus.screenmode.IOplusScreenMode
transact: 0x0c   requestGameRefreshRate(String packageName, int rateId)
```

Rate id `7` = 165 Hz. The resulting vote is `min=max=165`, so the target app's
windows run at 165 while foregrounded and return to normal system policy when
you leave.

## Build (inside proot-distro Ubuntu)

```bash
proot-distro login ubuntu -- bash -lc '
  export ANDROID_HOME=/root/android-sdk
  cd /data/data/com.termux/files/home/oneplus15-165hz/armer-app
  ./gradlew assembleDebug --offline'
```

Output: `app/build/outputs/apk/debug/app-debug.apk`
(~2.6 MB, debug-signed, package `tf.arm165`, versionCode 4).

## Install

Copy to Downloads from Termux, then tap to install:

```bash
cp app/build/outputs/apk/debug/app-debug.apk ~/storage/downloads/arm165-debug.apk
```

Open **Files → Downloads → arm165-debug.apk** and allow "install unknown apps"
when prompted. If an older copy was signed with a different key, uninstall it
first. The app asks for the notification permission on first launch — grant it,
the watchdog's silent notification is what keeps the re-arming service alive in
the background.

## Notes

- Overrides are runtime state — cleared on reboot (the boot receiver re-applies).
- The armed app must be foregrounded to receive the vote.
- Apps that pin their own frame rate are handled by the watchdog, which keeps
  re-voting every 5 s so our vote lands after theirs; a stubborn game may still
  need a few seconds after gaining focus before it locks at 165.
- Video/streaming apps may judder under a hard 165 pin — disarm those.
- Battery and heat increase with the number of armed apps.
- A future OxygenOS update may add a permission check; a `SecurityException`
  after an OTA means the trick was patched.

**Use at your own risk.** Undocumented vendor IPC; battery/thermal disclaimers apply.
