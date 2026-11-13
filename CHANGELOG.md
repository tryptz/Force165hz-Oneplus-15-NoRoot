# Changelog

## 1.1.5

### Fixed

- **App list refreshes by itself** (issue #36). An app installed or removed
  while 165 Armer was open did not show up, or go away, until the app was
  restarted. The list now reloads when that happens.
- **Starting after a reboot** (issue #41). At boot the app sent every saved
  vote before it started the watchdog, which with Arm all could take long
  enough for the phone to kill it first. The watchdog now starts first and
  sends the votes in the background. It also restarts after an update of the
  app. If it still does not start, opening the app writes one line to the log
  saying whether the phone delivered the boot signal at all.

### Added

- **Arm all includes apps you install later** (issue #36). After Arm all, a
  newly installed app is armed at the same rate. Clear ends that. An
  uninstalled app is dropped from the armed list.
- **Idle ramp for 165 Hz** (issue #37), in Settings, off by default and
  experimental. For screens that stay at 165 Hz when nothing needs it, like
  the OnePlus 15R and Ace 6T. A quiet screen is given the 120 Hz vote, the
  way 144 Hz already works: the mode in which the phone ramps down by itself,
  to its idle rate on a still screen and to 30 or 60 Hz while video plays.
  165 Hz comes back when content needs more than 120. Leave it off on the
  OnePlus 15 and 16.

### Changed

- **An armed app is voted the moment it comes to the front** (issues #39,
  #40). It could wait up to five seconds for its turn, showing the phone's
  own rate (60 Hz on Standard) until then. This shortens that wait; it does
  not cover a popup opened inside the same app.
- **Video no longer ends the idle ramp.** Once a quiet screen has the
  120 Hz vote (144 Hz apps, and 165 Hz with the new switch), the armed rate
  came back as soon as the app reported 45 fps, which a 60 fps video does. It
  now comes back when the app fills the 120 Hz vote (112 fps or more), so
  video settles at 30 or 60 Hz inside it. GPU load, and the screen held at
  the top of the vote for three seconds, bring the armed rate back as before.
- **The log says which vendor call the lock-screen hold used** (issue #42),
  and whether the phone named it or the app assumed it, so a log shared after
  a system update shows whether the update moved it.

## 1.1.4

### Added

- **Split view and floating windows at the armed rate** (issue #32). With
  two apps on screen, in split view or with one as a floating window, the
  phone dropped to 120 Hz whatever was armed, on Standard and High alike.
  While two apps are on screen and one is armed, the app now holds the
  screen at the highest armed rate among them, and lets go when you are back
  to one app. Needs usage access to see both apps. While it holds, a still
  screen does not idle down, so it costs a little battery for as long as the
  split lasts.

### Changed

- **Help page rewritten.** Six short cards on using the app (getting
  started, lock screen and camera, usage access, battery, checking it works,
  troubleshooting) instead of eighteen on its internals. The internals are in
  the README and `tool/tx-tables.md`. The Usage access rows in Settings now
  also say it is used for the camera, split view and floating windows.

## 1.1.3

### Fixed

- **Screen-off animation at 120 Hz** (issue #31). With *Lock screen and
  camera at 120 Hz* on, the animation of the screen going off (most visible
  as the clock moving into the Always-On display) still ran at 60. The hold
  only began once the screen was fully off. It now begins as the screen
  starts going off, as soon as the panel changes mode.

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
