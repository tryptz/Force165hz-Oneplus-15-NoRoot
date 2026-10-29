# 165 Armer

[![latest release](https://img.shields.io/github/v/release/tryptz/Force165hz-Oneplus-15-NoRoot?label=latest&style=flat-square)](https://github.com/tryptz/Force165hz-Oneplus-15-NoRoot/releases/latest)
[![downloads](https://img.shields.io/github/downloads/tryptz/Force165hz-Oneplus-15-NoRoot/total?style=flat-square)](https://github.com/tryptz/Force165hz-Oneplus-15-NoRoot/releases)

Rootless per-app refresh-rate unlock for OnePlus phones: **60 / 90 / 120 / 144 /
165 / 185 Hz**. No root, no Magisk, no bootloader unlock.

### [Download the latest APK](https://github.com/tryptz/Force165hz-Oneplus-15-NoRoot/releases/latest/download/arm165.apk)

[Release notes and older builds](https://github.com/tryptz/Force165hz-Oneplus-15-NoRoot/releases) · [Changelog](CHANGELOG.md)

<p align="center">
  <img src="screenshot.png" width="320" alt="165 Armer app screenshot">
  <br>
  <img src="widget.png" width="320" alt="165 Armer home-screen widget">
</p>

## Features

- Pin a refresh rate per app, or arm everything at once
- Search, and filters for armed, games, user and system apps
- Home-screen widget: arm all, clear and pick the rate
- Watchdog that keeps the rate held, and restores armed apps after a reboot
- 165 Hz across the UI with the lock screen kept at 120 Hz ([how](#165-hz-across-the-ui-120-hz-on-the-lock-screen))
- Smoothness test and a live log
- English and 简体中文, with a language switcher in Settings

## How to use

1. Install the APK.
2. Pick a rate at the top.
3. Tap an app to arm it, or press **Arm all**.

Usage access is only needed when more than eight apps are armed.

## Supported phones

| Phone | Highest rate |
|---|---|
| OnePlus 16 (`PYB110`) | 185 Hz |
| OnePlus 15 (`CPH2747`, `CPH2749`, `PLK110`) | 165 Hz |
| OnePlus 15R (`CPH2767`) | 165 Hz |
| OnePlus Nord 6 (`CPH2793`) | 165 Hz |

185 Hz is offered on every phone. Where the panel tops out at 165 Hz, it
runs at 165 Hz.

Other OnePlus/OPPO phones may work if they have the `oplusscreenmode`
service. The app finds the right vendor call for each firmware at launch, and
**Settings** shows how it found it.

## Idle behaviour (LTPO)

| Selected rate | Still screen drops to |
|---|---|
| 60 / 90 / 120 Hz | ≈1 Hz |
| 144 Hz | ≈1 Hz (parks at 120 Hz while idle) |
| 165 Hz | ≈55 Hz on ColorOS 16, ≈33 Hz on ColorOS 17 |

Measured on the OnePlus 15. The OnePlus 15R has an LTPS panel, not LTPO, so a
still screen stays at 60 Hz or above whatever rate is selected.

## 165 Hz across the UI, 120 Hz on the lock screen

ColorOS's own **Screen refresh rate** option makes you pick one of two bad
choices:

| Phone setting | App switching, notification shade | Lock screen |
|---|---|---|
| **High** | capped at 120 Hz | 120 Hz |
| **Standard** | follows the armed rate (165 Hz) | drops to 60 Hz |

You can have both. No adb needed:

1. Open the phone's **Settings → Display → Screen refresh rate** and choose
   **Standard**. The option says "a maximum refresh rate of 60 Hz"; ignore
   that, the armed rate still applies.
2. In 165 Armer, pick **165 Hz** and arm your apps (or **Arm all**).
3. In 165 Armer, open **Settings** and turn on **Lock screen at 120 Hz**.

<p align="center">
  <img src="screenshot-standard.png" width="320" alt="ColorOS Screen refresh rate set to Standard, with the 165 Hz counter showing">
  <br>
  <img src="screenshot-lock120.png" width="480" alt="165 Armer Settings: Lock screen at 120 Hz turned on">
</p>

Now the UI runs at 165 Hz and the lock screen at 120 Hz. Tested on the OnePlus 15.

**How it works:** while the phone is locked, the app holds a 120 Hz vote with
the vendor's `requestRefreshRateWithToken`, which needs no permission. That
vote outranks the 60 Hz that Standard gives the lock screen. The app releases
it when you unlock, so your armed apps are not capped. If the app is killed,
the phone drops the vote by itself. Details are in
[`tool/tx-tables.md`](tool/tx-tables.md).

## Notes

- Higher rates use more battery and run warmer.
- If a video app judders, arm it at 60 Hz.
- An OxygenOS update can close the interface this app uses.
- If an app stays pinned after disarming, reboot.

## How it works

The app calls OnePlus's undocumented
`IOplusScreenMode.requestGameRefreshRate(package, rateId)`. No permission is
needed. Transaction numbers differ between firmware builds, so the app
reads them from the phone at launch. Known tables are in
[`tool/tx-tables.md`](tool/tx-tables.md).

## Without the app (adb / Termux)

- [`setrate.sh`](setrate.sh) pins one app or all of them over adb:
  `setrate.sh <package> [rate]`, `--clear <package>`, `--all`.
- [`install.sh`](install.sh) sets up Termux:Boot to re-pin the apps in
  `boot/apps.txt` after every reboot.
- [`tool/`](tool) holds the helper dex, the probes, and `oplus_tx_table.py`,
  which reads the transaction table out of an OTA.

## Building

```sh
cd armer-app
./gradlew assembleRelease
```

Needs JDK 17+ and the Android SDK (platform 36). The release build comes out
unsigned. Sign it with your own key using `apksigner`.

---

**Use at your own risk.** This uses an undocumented vendor interface.
