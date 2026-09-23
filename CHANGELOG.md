# Changelog

## Unreleased

### Fixed

- 144/165 Hz capped at 120 on the OnePlus Turbo 6 / Nord 6 (#20). The watchdog
  now turns on `app_extreme_high_refresh_switch` when a rate above 120 is armed
  and the app holds `WRITE_SECURE_SETTINGS` (granted over adb). Without that
  permission it logs the command to grant it once.
- The `pinned hold` log line said "no focus" when nothing was being voted at
  all. That happens with more than 8 apps armed and no usage access. It now says
  `NOTHING VOTED` and tells you how to fix it.

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
