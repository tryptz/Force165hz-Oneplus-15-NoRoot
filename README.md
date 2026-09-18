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
- **Watchdog service** re-issues every vote at least every 5 s while the
  screen is on, beating games that pin their own frame rate. A vote parked by
  LTPO idle is the one exception — it is held down at 120 deliberately.
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
applies while the app is foregrounded, and the call is a plain set: handing
the vendor an id it already holds re-writes the same pin, it does not
withdraw it. Disarming asks for rate id `0` — the server-side verified
cancel (see below).

### Verified against the server (CPH2749_16.0.9.400)

Decompiled `oplus-services.jar` (md5 `4ee84ffc…`, byte-identical to the
build this was written on) and live `service call` probes from an
unprivileged uid:

- `requestGameRefreshRate(pkg, 0)` is the vendor's own cancel: the server
  takes the `mOifaceRequestedRates.remove(pkg)` branch and then rewrites the
  override on every live window the package owns. Returns 1, idempotent,
  no reboot needed. This is the only mutating call a rootless app can make.
- `setAppOverrideRefreshRate` (0x19), `removeCustomizeRefreshRate` (0x1c),
  `removeAllCustomizeRefreshRate` (0x1d), `getAppOverrideRefreshRate` (0x1a)
  and `getVoteInfo` all enforce `oplus.permission.OPLUS_COMPONENT_SAFE` —
  a live call answers with a `SecurityException` naming it. The
  "Settings uses setAppOverrideRefreshRate" route is closed to us.
- `oplus_vrr_service` (`com.oplus.vrr.IOPlusRefreshRate`) has zero caller
  checks (verified: 0 `enforceCalling` in 12,754 smali lines), and its FRTC
  path (`setFrameRateTargetControl`, tx 0x14) reaches SurfaceFlinger as
  transaction `0x5601` — a per-process frame-rate ceiling that leaves the
  LTPO floor free. Candidate for a future ceiling-only arm; not needed for
  the idle ramp below.

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
- Battery and heat increase with the number of armed apps. The panel's LTPO
  floor depends on the mode the vote selects — measured on this build:
  60→30 Hz, 90→30 Hz, 120→1 Hz, 165→55 Hz — so a held 165 vote can never
  idle below 55 even on a still screen. With **LTPO idle** on (the chip next
  to FPS), the watchdog watches GPU load and the vendor's measured fps:
  after a couple of quiet ticks it downgrades the vote to 120 Hz, the one
  rate whose mode spans 1–120, so a parked screen ramps to 1 Hz while the
  vote itself stays held (game-engine self-pins still lose to ours). A
  `GPU_ACTIVE` load (or ≥45 measured fps) puts the armed rate back within
  ~1.5 s; the hysteresis band between the two thresholds holds state so a
  loading burst cannot flap the vote. Turn it off to hold the armed rate
  permanently.
- A `SecurityException` after an OTA means the vendor patched the trick.
- If an app stays pinned after a disarm, the vote withdraw was refused on
  that build — `adb logcat -s Arm165` logs it; a reboot always clears the
  vendor's state.

**Use at your own risk.** Undocumented vendor IPC; battery/thermal
disclaimers apply.
