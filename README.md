# oneplus15-165hz — rootless per-app 165Hz unlock for the OnePlus 15

Forces any app on a OnePlus 15 (CPH2747/CPH2749, OxygenOS 16 / Android 16) to
run its display pipeline at **165 Hz** — no root, no Magisk, no bootloader
unlock. Ships as both a shell tool and a standalone Android app with a UI.

## How it works

OnePlus's game pipeline (Oiface SDK → COSA → WindowManager) unlocks 165 Hz for
whitelisted games through this internal binder call:

```
service:  "oplusscreenmode"
iface:    com.oplus.screenmode.IOplusScreenMode
transact: 0x0c   requestGameRefreshRate(String packageName, int rateId)
```

**The transaction has no caller permission check** — any uid can invoke it,
including `adb shell` or a regular app. Rate ids (same table as the vendor
config `/my_product/etc/refresh_rate_config.xml`):

| rateId | refresh |
|-------:|---------|
| 1      | 90 Hz   |
| 2      | 60 Hz   |
| 3      | 120 Hz (stock cap) |
| 7      | 165 Hz  |

The resulting vote is `min=max=165`, so LTPO dips are suppressed while the
target app is foregrounded; when you leave the app the panel returns to normal
system policy.

The "official" root method (XDA Magisk modules by koaaN/PANL and barsikus007)
reaches the same policy by bind-mounting a patched vendor config — this project
skips root entirely by calling the unguarded IPC directly.

## The app — "165 Armer" (`armer-app/`)

A small Android app (no AndroidX, ~870 KB debug APK):

- lists every installed package with a toggle — arm/disarm 165 Hz per app
- search bar to filter by name/package
- **Arm EVERYTHING** button (system-wide sweep)
- **Re-arm saved** re-applies your selection on every launch
- **Clear all** disarms everything
- armed apps are persisted and **automatically re-applied after reboot**
  (`BOOT_COMPLETED` receiver)

Build & install:

```bash
cd armer-app
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## The shell tool (`setrate.sh`, Termux)

```bash
./setrate.sh <package>            # lock app to 165 Hz
./setrate.sh <package> 120        # revert to stock behavior (rateId mapping applied)
./setrate.sh --list               # show active overrides
./setrate.sh --all                # sweep every installed package
./setrate.sh --watch              # live refresh-decision log
./install.sh                      # set up Termux:Boot persistence
```

Boot persistence (`boot/165hz.sh`) rediscovers the phone over mDNS
(`_adb-tls-connect._tcp`), reconnects ADB and re-arms everything in
`boot/apps.txt`. Log: `~/165hz-boot.log`.

Termux packages needed: `pkg install android-tools` (+ `openjdk-17 d8 python`
for rebuilding / mDNS discovery).

## Self-locking apps

If it's your own app, skip all external tooling and arm it from inside — the
transaction is callable from any app uid (verified):

```kotlin
val binder = Class.forName("android.os.ServiceManager")
    .getMethod("getService", String::class.java)
    .invoke(null, "oplusscreenmode") as android.os.IBinder
val data = android.os.Parcel.obtain(); val reply = android.os.Parcel.obtain()
data.writeInterfaceToken("com.oplus.screenmode.IOplusScreenMode")
data.writeString(packageName)
data.writeInt(7) // rateId 7 = 165 Hz
binder.transact(0x0c, data, reply, 0)
reply.readException()
data.recycle(); reply.recycle()
```

Call it in `onCreate()` (idempotent). Full client: `tool/SetGameRate.java`.

### Important interaction with app-side pins

Any mode/frame-rate pin made *by the target app* (`preferredDisplayModeId`,
`preferredRefreshRate`, `Surface.setFrameRate ≤ 120`) creates a window override
that beats this one ("revised to win override" in the RefreshRate log). Apps
that request nothing get fully locked at 165. For your own apps: don't pin
display attributes if you want the 165 lock.

## Reverse-engineering trail

Found on-device against OxygenOS 16.1:

1. Vendor config caps UI rates via `maxrefreshsettings="3"` in
   `/my_product/etc/refresh_rate_config.xml`; `<extremeHighRate rateId="7">`
   entries reveal id 7 = 165 Hz.
2. Hidden dev option *"165 Hz ultra refresh rate"* (`ExtremeRefreshRateFragment`
   in Settings.apk) flips `Settings.Global app_extreme_high_refresh_switch`,
   but only whitelisted packages benefit.
3. Per-app overrides flow through `OplusDisplayModeService.setAppOverrideRefreshRate`
   — guarded by signature permission `oplus.permission.OPLUS_COMPONENT_SAFE`.
4. The same service also exposes `requestGameRefreshRate(pkg, rateId)` — used by
   the Oiface/COSA game pipeline — **without any permission enforcement**.
5. Verified live: shell uid and regular app uid both receive `result=1`, panel
   votes `165.0 min=165.0` for the package's windows.

## Notes & caveats

- Overrides are runtime state: cleared on reboot (the app's boot receiver /
  Termux boot script handle that). Re-issuing the same rateId toggles it off.
- The app must be foregrounded for its windows to receive the vote.
- The panel can't show frames the app never renders: an engine capped at
  120 fps still renders 120 — but the display pipeline runs at 165, cutting
  latency.
- Video/streaming apps may judder under a hard 165 pin; disarm those in the app.
- Battery use and heat increase proportionally to how many apps you arm.
- A future OxygenOS update may add a permission check to transaction `0x0c`.
  If `SecurityException` appears after an OTA, that's why.

## Credits

Reverse engineered on-device from OxygenOS 16.1 system images
(`oplus-services.jar`, `oplus-framework.jar`, Settings.apk, COSA.apk).
Context: XDA "force 165 Hz" Magisk modules (koaaN/PANL, barsikus007), which
require root — this approach doesn't.

**Use at your own risk.** Undocumented vendor IPC; warranty, battery and
thermal disclaimers apply.
