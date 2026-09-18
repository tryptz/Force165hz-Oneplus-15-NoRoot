# Changelog

## 1.0.8

Supersedes 1.0.7, which was never released.

### Added

- **Home-screen widget.** The hero card — armed count, rate breakdown, fill bar
  and the rate selector — with **Arm all** and **Clear** top left and the
  measured panel rate opposite them. Resizable, and it follows the wallpaper
  like the card it mirrors.
- **Usage access row and switch** in Settings, with both ways to grant it.
  Needed only if you arm more than 8 apps; the row says which case you are in.

### Fixed

- **A game could take the panel for good.** On a quiet screen the watchdog
  issued no votes at all, so the first time an engine re-pinned its own frame
  rate ours was gone with nothing to put it back. It only showed on apps the
  vendor daemon does not track, which read gpu=0 and fps=0 forever.
- **Arm all did not work while arming one app did.** Above 8 armed apps the
  sweep cycled background packages 16 at a time — about seven minutes before it
  reached the game, and each burst ended the pass on a package with no live
  windows, which drops the pin. Only the app on screen is voted now.
- **Nothing could see what was on screen** unless the vendor already tracked it
  as a game. Usage access answers for any app.
- **Disarm left an app pinned until reboot** (#10). Re-issuing an app's own id
  never withdrew the vote; rate id 0 is the vendor's cancel.

### Changed

- **Games are never parked.** Parking walks a vote down to 120 and back on
  motion — right for a browser, wrong for a game, which renders continuously
  and is the reason the rate was pinned. Decided by what the package is, not by
  probes that read 0 for anything untracked.
- **Our vote lands on top of the engine's.** With min = max held, a focused game
  on a rate between the mode's idle floor and the pinned rate is on a rate
  neither end of our vote asked for — re-voted on the spot, and at most 1 s
  behind otherwise.
- **The app on screen is registered with oiface** before it is voted, so a build
  that only applies a game rate to what it recognises as a game applies ours.
  Scoped to that one package, and only what we added is ever removed.
- Removed the FPS overlay's leftover "Display over other apps" permission.

### Known issue

On some builds the vote is accepted (`res=1`) and the panel stays at 120, the
phone's ordinary maximum. The log now prints `extreme=` (the OPlus master
switch), `vendorGame=` and `registerGame(...) added=`, which tell a vote that
lost apart from a vote that was never eligible.

## 1.0.6

See the [release notes](https://github.com/tryptz/Force165hz-Oneplus-15-NoRoot/releases/tag/1.0.6).
