# 165 Armer (`armer-app/`)

[![latest release](https://img.shields.io/github/v/release/tryptz/Force165hz-Oneplus-15-NoRoot?label=latest&style=flat-square)](https://github.com/tryptz/Force165hz-Oneplus-15-NoRoot/releases/latest)
[![downloads](https://img.shields.io/github/downloads/tryptz/Force165hz-Oneplus-15-NoRoot/total?style=flat-square)](https://github.com/tryptz/Force165hz-Oneplus-15-NoRoot/releases)

### [Download the latest APK](https://github.com/tryptz/Force165hz-Oneplus-15-NoRoot/releases/latest/download/arm165.apk)

[Release notes and older builds](https://github.com/tryptz/Force165hz-Oneplus-15-NoRoot/releases/latest)

Rootless per-app refresh-rate unlock (60 / 90 / 120 / 144 / 165 Hz) for the
OnePlus 15 (CPH2747/CPH2749, OxygenOS 16). No root, no Magisk, no bootloader
unlock.

<p align="center">
  <img src="screenshot.png" width="320" alt="165 Armer screenshot">
</p>

## Features

- Per-app rate pinning, searchable list (All / Armed / Games / User / System)
- Arm all / Re-arm / Clear; the armed set survives reboots
- Watchdog service re-issues votes while the screen is on, so a game that
  pins its own frame rate loses to yours
- Smoothness test (five lanes, 165 to 60) and a live log page
- Material You, edge to edge

## How it works

One unguarded vendor IPC, no permission check:

```
service:  "oplusscreenmode"
iface:    com.oplus.screenmode.IOplusScreenMode
transact: 0x0c   requestGameRefreshRate(String packageName, int rateId)
```

Rate ids from `refresh_rate_config.xml`: `1`=90, `2`=60, `3`=120, `4`=144,
`7`=165. They are not in Hz order. `0` disarms.

Two things about the call shape drive the whole design:

1. **It is a plain set, and it sets min = max.** A vote is a pin at one rate,
   not a ceiling. Re-sending an id the vendor already holds rewrites the same
   pin; it never withdraws it.
2. **The server writes the override onto the named package's live windows,
   and the panel follows the write that landed last.** So a sweep must end on
   the app that is actually on screen. Ending on a background package hands
   the display a vote for windows that do not exist, and the panel falls back
   to its default 1-120 range. That was the original bug: "Arm all" swept in
   package order every second and dissolved the pin it had just set.

Which app is on screen comes from oiface's `getCurrentGamePackage`, so it is
known for games that daemon tracks and unknown for everything else. Where it
is unknown the votes still go out, just without a package to end on.

## Idle behaviour (measured on CPH2749_16.0.9.400)

A vote picks a panel mode, and each mode has its own LTPO floor. The ramp
always works; it just stops at the floor of whatever mode you asked for.

| vote | mode floor | a still screen rests at |
|-----:|-----------:|:------------------------|
|   60 |         30 | 1 Hz, by parking into 120 |
|   90 |         30 | 1 Hz, by parking into 120 |
|  120 |         1  | 1 Hz |
|  144 |        60  | 1 Hz, by parking into 120 |
|  165 |        55  | 55 Hz |


**165 is the only mode that cannot idle**

**The park** swaps a still app's vote for 120, so the panel ramps down while a
vote stays held. Only 144 takes it, and that is a measurement rather than a
design:

- 60, 90 and 120 reach 1 Hz in their own modes, so there is nothing to win,
  and raising one to 120 would undo the ceiling you chose it for.
- 165 would land on a *pin* at 120 (a vote sets min = max), which is higher
  than the 55 Hz it already idles to unaided. Parking it costs idle power
  instead of saving it.
- 144 is what is left, and from there the switch to 120 is clean.

GPU load or measured fps restores the armed rate in about 55 ms; a park that
content undoes within 3 s was the 120 mode's entry churn, so that package is
skipped for 30 s instead of oscillating.

**What the park gate must never read** is the panel at the top of its range.
At the armed rate the panel is reporting our own pin, and its 6 ms frame
pairs also feed the rate-starvation counter. Treating either as motion means
a park vetoed by the vote it was trying to replace. The gate believes the
panel only between the mode's floor and the vote's own rate.

## Verified against the server

Decompiled `oplus-services.jar` (md5 `4ee84ffc…`, byte-identical to the build
this was written on) plus live `service call` probes from an unprivileged uid:

- `requestGameRefreshRate(pkg, 0)` is the vendor's own cancel: the server
  takes the `mOifaceRequestedRates.remove(pkg)` branch, then rewrites the
  override on every live window the package owns. Returns 1, idempotent, no
  reboot. The only mutating call a rootless app can make.
- `setAppOverrideRefreshRate` (0x19), `removeCustomizeRefreshRate` (0x1c),
  `removeAllCustomizeRefreshRate` (0x1d), `getAppOverrideRefreshRate` (0x1a)
  and `getVoteInfo` all enforce `oplus.permission.OPLUS_COMPONENT_SAFE`; a
  live call answers with a `SecurityException` naming it. The "Settings uses
  setAppOverrideRefreshRate" route is closed.
- `oplus_vrr_service` (`com.oplus.vrr.IOPlusRefreshRate`) has zero caller
  checks (0 `enforceCalling` in 12,754 smali lines), and its FRTC path
  (`setFrameRateTargetControl`, tx 0x14) reaches SurfaceFlinger as transaction
  `0x5601`: a per-process frame-rate ceiling that leaves the LTPO floor free.
  A candidate for ceiling-only arming; not needed for the idle ramp above.

### Dead ends, both measured

- **A vote on SystemUI has no effect.** The shade, quick settings, lock screen
  and AOD are overlays with no activity in the stack the override is read
  from. The call is accepted and nothing changes. They stay at 120. The row is
  kept for builds that behave differently.
- **`Settings.System peak_refresh_rate` = 165 lifts nothing** an app does not
  own. The app had a control for it; it was removed rather than left offering
  a global battery cost for no effect.

Between them: nothing rootless found so far reaches 165 outside a foreground
app window.

## Notes

- An armed app must be foregrounded to receive its vote.
- Video apps may judder. Disarm them, or pin at 60 Hz.
- Battery and heat rise with the number of armed apps.
- `android` is never armed. SystemUI and the armer itself are armable by hand
  but skipped by Arm all; arming the armer is the cheapest test that votes are
  landing at all.
- A `SecurityException` after an OTA means the vendor patched the trick.
- An app still pinned after a disarm means the withdraw was refused on that
  build. The log page shows it; a reboot always clears the vendor's state.

**Use at your own risk.** Undocumented vendor IPC; battery and thermal
disclaimers apply.
