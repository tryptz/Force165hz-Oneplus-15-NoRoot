# Changelog

## 1.1.0

### Added

- **185 Hz on the OnePlus 16.** rateId 8, read from its own OTA. The selector,
  the per-app picker and the widget build from the panel's ceiling, so the 185
  segment only appears where the panel can show it. A saved 185 pick on a
  165 Hz phone falls back to 165 at launch instead of voting nothing.
- **OnePlus Nord 6 support** (#17). Its build numbers the vote 11, not 12, so
  every arm failed with "Parcel data not fully consumed". The app now asks the
  build which code it uses, at launch, before the first tap.
- When the build won't say, the app probes a package nothing has installed,
  and on a numbering nobody has seen, sweeps the interface for a transaction
  that behaves like the vote.
- Settings shows how the vote's code was found: named by the build, its proxy,
  probed, swept, or assumed.
- Simplified Chinese (zh-CN) translation of the main UI and dialogs.

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
