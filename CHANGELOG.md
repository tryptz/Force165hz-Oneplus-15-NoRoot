# Changelog

## 1.1.2

### Added

- **Camera at 120 Hz** (issue #28). On Standard the camera's buttons and
  menus ran at 60 (its viewfinder is 60 on either mode). The lock-screen
  switch now covers the camera too, renamed **Lock screen and camera at
  120 Hz**: the same 120 Hz hold while the camera is on screen, released when
  you leave it. The camera half needs usage access to see the camera.

## 1.1.1

### Added

- **Lock screen at 120 Hz** (issue #26), in Settings. On ColorOS the phone's
  own *Screen refresh rate* option caps SystemUI (app switching, the shade)
  at 120 on High, and drops the lock screen to 60 on Standard. With the phone
  on Standard and this on, the app holds a 120 Hz display vote while the phone
  is locked and releases it on unlock. No adb, no permission.

### Changed

- **165 Hz idles to 33 Hz on ColorOS 17** (55 on ColorOS 16). The watchdog,
  the note on the main screen and Help now use the floor of the phone's own
  build, so motion between 33 and 65 Hz is no longer read as a still screen.

## 1.1.0

### Added

- **185 Hz.** New option in the selector, per-app picker and widget. Runs at
  185 on the OnePlus 16; on phones that top out at 165 it runs at 165.
- **简体中文.** Full Simplified Chinese translation.
- **Language switcher** in Settings: System default, English or 简体中文.
- **OnePlus 15R** (`CPH2767`) recognised as a 165 Hz phone.
- **Update checker.** Checks GitHub for a newer release once a day, or
  from **Settings → Updates**, and offers the download.

## 1.0.8

### Added

- **Home-screen widget.** The hero card — armed count, rate breakdown, fill bar
  and the rate selector — with **Arm all** and **Clear** top left and the
  measured panel rate opposite them. Resizable, and it follows the wallpaper
  like the card it mirrors. Tapping a rate sets what a sweep will arm at;
  tapping the count opens the app.

### Fixed

- The widget failed to inflate on first release of it: a plain `View` divider
  and a `setSelected` call, neither of which RemoteViews allows.

## 1.0.7

### Fixed

- Games stopped being forced: once an engine re-pinned its own frame rate, the
  watchdog never voted again on a quiet screen. It re-lands the vote every pass.
- Arm all didn't work while arming one app did. Above 8 armed apps the sweep
  cycled background packages — ~7 minutes before reaching your game, and each
  burst dropped the pin. Only the app on screen is voted now.
- The watchdog couldn't see what was on screen unless the vendor already tracked
  it as a game. Usage access fixes that (needed only above 8 armed apps).
- Disarm left an app pinned until reboot (#10).

### Changed

- Games are never parked — no ramping down the rate you picked.
- Our vote re-lands the moment the panel shows an engine took it back, and at
  most 1 s behind otherwise.
- The app on screen is registered with oiface before it's voted, so builds that
  only apply game rates to recognised games apply ours.
- Usage access row + switch in Settings, with both grant routes.
- Removed the FPS overlay's leftover "Display over other apps" permission.

### Known issue

On some builds the vote is accepted but the panel stays at 120. The log prints
`extreme=`, `vendorGame=` and `registerGame(...) added=` to show whether the
vote lost or was never eligible.

## 1.0.6

See the [release notes](https://github.com/tryptz/Force165hz-Oneplus-15-NoRoot/releases/tag/1.0.6).
