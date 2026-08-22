# oneplus15-165hz — rootless per-app 165Hz unlock for the OnePlus 15

Forces any app on a OnePlus 15 (CPH2747/CPH2749, OxygenOS 16) to run its display
pipeline at **165 Hz** — no root, no Magisk, no bootloader unlock.

## How it works

OnePlus's game pipeline (Oiface SDK → COSA → WindowManager) unlocks 165 Hz for
whitelisted games through this internal call:

```
service:  "oplusscreenmode"
iface:    com.oplus.screenmode.IOplusScreenMode
transact: 0x0c  requestGameRefreshRate(String packageName, int rateId)
```

**The transaction has no caller permission check.** Any uid — shell, or the app
itself — can invoke it. rateIds: `7=165Hz`, `3=120Hz`, `1=90Hz`, `2=60Hz`.

The resulting vote is `min=max=165`, so LTPO dips are suppressed while the
target app is foregrounded. When you leave the app, the panel returns to normal
system policy (per-window votes).

For reference, the "official" root method (Magisk modules on XDA) achieves the
same result by bind-mounting a patched
`/my_product/etc/refresh_rate_config.xml` — this tool reaches the same policy
through the unguarded vendor IPC instead.

## Requirements

- OnePlus 15 on OxygenOS 16 (untested elsewhere; the policy class is
  `com.android.server.wm.OplusRefreshRatePolicyImpl`)
- ADB access to the device (wireless debugging from the same phone via Termux
  works fine), **or** embed `tool/SetGameRate.dex` in your own app and call the
  binder directly — no ADB needed at all (see "Self-locking apps" below)
- Termux packages: `pkg install android-tools openjdk-17 d8` (d8/jdk only if
  you want to rebuild the dex)

## Usage (Termux)

```bash
git clone <this repo> && cd oneplus15-165hz
./setrate.sh <package>            # lock app to 165 Hz
./setrate.sh <package> 3          # revert to stock 120 Hz behavior
./setrate.sh --list               # show active overrides
./setrate.sh --watch              # live refresh-decision log
```

Find package names with `pm list packages -3` (via adb) or any app-inspector.

## Boot persistence (Termux:Boot)

1. Install the Termux:Boot app and run it once.
2. Edit `boot/apps.txt` — one package name per line.
3. `./install.sh` — copies the boot script and dex into place.

After every reboot the script rediscovers the phone over mDNS
(`_adb-tls-connect._tcp`), reconnects ADB and re-arms all overrides.
Log: `~/165hz-boot.log`.

## Self-locking apps (no ADB, no boot script)

If it's your own app, arm the override from inside it — the transaction is
callable from any app uid:

```kotlin
val binder = Class.forName("android.os.ServiceManager")
    .getMethod("getService", String::class.java)
    .invoke(null, "oplusscreenmode") as android.os.IBinder
val data = android.os.Parcel.obtain(); val reply = android.os.Parcel.obtain()
data.writeInterfaceToken("com.oplus.screenmode.IOplusScreenMode")
data.writeString(packageName)
data.writeInt(7) // rateId 7 = 165 Hz
binder.transact(0x0c, data, reply, 0)
reply.readException() // result int in reply
data.recycle(); reply.recycle()
```

Call it in `onCreate()` (idempotent). See `tool/SetGameRate.java` for the full
parcel layout. Caveat: apps that pin their own display mode / frame rate
(`preferredDisplayModeId`, `Surface.setFrameRate` ≤ 120) override this — the
request-nothing path wins the lock.

## Rebuilding the dex

```bash
cd tool
javac SetGameRate.java && d8 --release --min-api 26 --output . SetGameRate*.class
mv classes.dex SetGameRate.dex
```

## Notes

- Overrides are runtime state: cleared on reboot (boot script handles that) and
  toggled off by re-issuing the same rateId.
- The app must be foregrounded for its windows to receive the vote.
- The panel cannot display frames the app never renders — a game capped at
  120 fps internally will still render 120; the display/latency pipeline runs
  at 165 regardless.
- An OxygenOS update may add a permission check to this transaction at any
  time. If `SecurityException` appears in `setrate.sh` output after an update,
  that's why.

## Credits

Reverse engineering: done on-device against OxygenOS 16.1 / Android 16
(`oplus-services.jar`, `oplus-framework.jar`, Settings.apk, COSA.apk).
Inspired by the XDA 165 Hz modules (koaaN/PANL, barsikus007), which need root —
this approach doesn't.

**Use at your own risk.** This pokes undocumented vendor IPC; warranty and
battery-life disclaimers apply.
