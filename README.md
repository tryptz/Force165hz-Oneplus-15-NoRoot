# 165 Armer (`armer-app/`)

[![latest release](https://img.shields.io/github/v/release/tryptz/Force165hz-Oneplus-15-NoRoot?label=latest&style=flat-square)](https://github.com/tryptz/Force165hz-Oneplus-15-NoRoot/releases/latest)
[![downloads](https://img.shields.io/github/downloads/tryptz/Force165hz-Oneplus-15-NoRoot/total?style=flat-square)](https://github.com/tryptz/Force165hz-Oneplus-15-NoRoot/releases)

### [Download the latest APK](https://github.com/tryptz/Force165hz-Oneplus-15-NoRoot/releases/latest/download/arm165.apk)

[Release notes and older builds](https://github.com/tryptz/Force165hz-Oneplus-15-NoRoot/releases/latest)

Rootless per-app refresh-rate unlock (60 / 90 / 120 / 144 / 165 Hz) for the
OnePlus 15 (CPH2747/CPH2749, OxygenOS 16). No root, no Magisk, no bootloader
unlock.

<p align="center">
  <img src="screenshot.png" width="320" alt="165 Armer app screenshot">
  <br>
  <img src="widget.png" width="320" alt="165 Armer home-screen widget">
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

Rate ids: `1`=90, `2`=60, `3`=120, `4`=144, `7`=165. They are not in Hz order.
`0` disarms. The framework names them in `OplusRefreshRateConstants`, which
also carries `5`=72 and `6`=30 — real ids this app does not offer — and bounds
the set at `1..7`, which is the range the server's `checkRefreshRateId`
accepts.

`0x0c` is one build's numbering, not the interface's. AIDL counts a method by
its position, so a build declaring one method fewer ahead of the vote shifts
it, and every code after it, down by one. So which phone this is gets settled
at launch, before anything is armed — by the app, the watchdog and the boot
receiver alike, whichever starts first — and in this order:

1. **What the build declares.** The stub that dispatches is on the phone, so
   the app reads the number out of that build's own
   `IOplusScreenMode$Stub.TRANSACTION_requestGameRefreshRate`, and
   `getGameList` and `setAppOverrideRefreshRate` with it.
2. **What its own proxy sends**, for a build whose argument list differs too:
   the vote is marshalled by the proxy that build generates rather than by us.
3. **A probe of the codes the vote has been found at** — 12, then 11 — for a
   build that will not name its own transactions. What it sends is a withdrawal
   on a package name nothing has installed: the vendor's cancel on a package
   holding no vote changes nothing, and a code whose method takes different
   arguments is refused by `enforceNoDataAvail` before that method ever runs.
4. **The numbers above**, and a log line naming the device, the build and that
   build's whole transaction table, which is what a report from an unknown
   build has to carry.

Settings names which of those four it was, so "the vendor closed the call" and
"this app is dialling the wrong number" are not the same row.

**Measured, and not what rung 1 was written for.** On a OnePlus 15 running
OxygenOS 16 the constant cannot be read: `oplus-framework.jar` is on the boot
classpath (it is in `bootclasspath.pb`) and the class loads, but the field
behind `TRANSACTION_requestGameRefreshRate` is a non-SDK member and the read is
refused. The Settings row says `found by probe`, at 12, which is the right code
there. So rung 3 is the one carrying this on OxygenOS 16, not the fallback it
reads as — which is also why it probes a package nobody owns rather than this
app's own, and why the lookup now logs the exception it failed with. A build
numbering the vote at something other than 12 or 11 would land on rung 4, and
that log line is what would make the next number knowable.

## The Nord 6, from its own jar (CPH2793_16.0.5.1200)

Issue #17: every arm on a OnePlus Nord 6 failed with
`BadParcelableException: Parcel data not fully consumed, unread size: 8`.
Recovered from that exact build's `oplus-framework.jar` and `oplus-services.jar`
(pulled out of the full OTA), the reason is the whole table sliding by one:

| method | OnePlus 15 | Nord 6 |
|:-------|-----------:|-------:|
| `requestGameRefreshRate(String, int)` | 12 | **11** |
| `getGameList(Bundle)` | 14 | 13 |
| `setAppOverrideRefreshRate(String, int, int)` | 25 | 24 |

Transaction 12 on the Nord 6 is
`requestRefreshRateWithToken(boolean, int, IBinder)`. Handed the vote's parcel
it reads an int, an int and a binder, then rejects the tail of the package name
it never read — the 8 bytes the exception named.

What is on the other side of the call is the same as here:

- `OplusDisplayModeService.requestGameRefreshRate` has no caller check and
  hands straight to `OplusRefreshRatePolicyImpl.requestGameRefreshRate`, which
  has none either — the same `mOifaceRequestedRates` put / `remove` on rate 0,
  then `DisplayContent.forAllWindows` writing the override onto that package's
  live windows. So the cancel and the vote-last ordering both carry over.
- The rate ids are the same ids. `OplusRefreshRateConstants` on that build
  reads `REFRESH_RATE_90 = 1`, `_60 = 2`, `_120 = 3`, `_144 = 4`, `_72 = 5`,
  `_30 = 6`, `_165 = 7`, bounded `1..7` — so `7` means 165 Hz there as it does
  here, and `checkRefreshRateId` accepts everything this app sends.
- 165 is a mode that phone has, and a list it keeps. `my_product/build.prop`
  has `persist.oplus.display.ogfr.exclusive=144,165`, and
  `my_product/etc/refresh_rate_config.xml` gives 45 packages `rateId="7-1-2-7"`
  — Call of Duty Mobile, Clash of Clans, Brawl Stars, Standoff 2, Minecraft,
  Real Racing 3, Subway Surfers among them — each with `adfr="true"` and
  `disableViewOverride="true"`. Four more get `4-0-0-4`, 144 Hz: Honor of
  Kings, CrossFire, Mobile Legends. That list is what "165 in selected games"
  means on the device, and it is per-package, which is exactly what the vote
  writes.
- `setAppOverrideRefreshRate` and `removeCustomizeRefreshRate` carry no
  permission check in either class on that build, unlike the OnePlus 15 where
  a live call answers `SecurityException`. Static read only; whether they are
  reachable from an unprivileged uid there is unprobed.

Nothing here was measured on the device — it is what the build's own jars say.
`tool/oplus_tx_table.py` is how they were read: it range-fetches one partition
out of a published full OTA (650 MB of a 7.9 GB package for `system_ext`,
600 MB for `system`), checks what it rebuilt against the sha256 in the
payload's own manifest, and prints any AIDL interface's transaction table out
of a jar. Point it at another device's OTA to answer the same question there.

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

- Usage access is needed only if you arm more than eight apps. An armed app
  must be foregrounded to receive its vote, and up to eight armed apps every
  one of them is voted on each pass, so a single game — or a handful — needs
  nothing else. Above eight only the app on screen is voted,
  because a slice of hundreds ends the pass on a background package and drops
  the pin it just set, and identifying that app needs usage access: Settings ›
  Apps › Special app access › Usage access, or
  `adb shell appops set tf.arm165 GET_USAGE_STATS allow`. So it is Arm all
  that wants the grant; `adb logcat -s Arm165` says so once when the armed set
  is past the bound without it.
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
