# `IOplusScreenMode` transaction tables per build

Read with `oplus_tx_table.py table <oplus-framework.jar> com.oplus.screenmode.IOplusScreenMode`
(fetch the jar out of a published full OTA: `partitions` → `fetch system` → `fsck.erofs --extract`).
A build shifts the whole table when it adds or removes a method ahead of another — every
`TRANSACTION_` constant is printed from the jar itself, so a stale assumption shows up immediately.

## OnePlus 15 (CPH2747/CPH2749, OxygenOS 16) — baseline

`requestGameRefreshRate` = **12 (0x0c)**, `(String, int) -> Z`, rateIds `1=90 2=60 3=120 4=144 7=165`.

Nord 6 (issue #17) shifts it to 11; every table printed so far on the flagship ladder keeps 12.

## OnePlus 16 (PYB110 / OP64E1L1, CN 17.0.0.152, Android 17, 2026-10)

Source: full OTA `ota_full-PYB110_17.0.0.152` (payload manifest sha256-verified), jar
`system/framework/oplus-framework.jar` — sha256 `cd338a3912c0cca6393e9b0fcb0162c9fa5f776021ea0890996e728093c05ce3`,
byte-identical to the KB2000 Android-17 port of the same jar. **36 methods, no renumbering.**

    12  0x0c  requestGameRefreshRate(Ljava/lang/String;, I) -> Z   ← the app's vote, unchanged
    17  0x11  overrideWindowRefreshRate(IBinder, I)
    18  0x12  keepHighRefreshRate(I)
    25  0x19  setAppOverrideRefreshRate(String, I, I)

### rateId ladder on the OnePlus 16 (`my_product/etc/refresh_rate_config.xml`, v20260902)

Vectors are now **5-wide** (`mode_auto - 90hz - 60hz - 120hz - game`); the header comment still
describes only the first four columns — OPPO didn't update it.

| rateId | Hz | first-class in `refresh_rate_config.xml` as |
|---|---|---|
| 1 | 90 | `<!--游戏高帧90Hz-->` |
| 2 | 60 | base settings column |
| 3 | 120 | default tier (`defaultRateId="1-1-2-3"`, `maxrefreshsettings="3"`) |
| 4 | 144 | 王者荣耀 (Honor of Kings), 香肠派对 |
| 7 | 165 | LoL Mobile, CoD Mobile, Real Racing 3, Brawl Stars… |
| **8** | **185** | **和平精英 (PUBG Mobile), 无畏契约 (Valorant Mobile), CF, 暗区突围, Minecraft, Brawl Stars…** |

~122 packages are pinned at `8-1-2-8-8`; the base config's own comment marks the block
`<!--游戏高帧185Hz-->`. 185 Hz therefore ships as a **first-class vendor rate id**, not an
extension — the app can pin it the same way as 165:

    requestGameRefreshRate(pkg, 8)   # 185 Hz
    requestGameRefreshRate(pkg, 7)   # 165 Hz — still valid, unchanged

`sys_dynamic_frame_config.xml` stays capped at `targetFramerate="120"` — the 185 Hz tier
exists only in the vendor game-rate channel, which is exactly what the vote drives.

## OnePlus 15 (OxygenOS, Android 17) — the display-wide vote

Source: full OTA `system` partition (payload manifest sha256-verified),
`system/framework/oplus-services.jar` and `oplus-framework.jar`. Same table as the baseline.

    13  0x0d  requestRefreshRateWithToken(Z, I, IBinder) -> Z   ← Lock screen at 120 Hz

`OplusDisplayModeService` passes it straight to `OplusRefreshRatePolicyImpl` with **no
permission check**, which puts a fixed vote at priority 70 (`PRIORITY_MEMC`) in
`OplusRefreshRateCore`. The core resolves the panel to the highest-priority fixed vote, and
the lock screen's own pick is a priority-0 window vote, so this one wins. The token is linked
to the caller's death, so the vote cannot outlive the app. On builds with the
`oplus.software.display.game.memc_enable`, FRC or AI-SR display features the call goes to the
MEMC chip's feedback instead and votes nothing.

Why the lock screen is 60 on Standard (`getNormalModePickPreferredId`): while the keyguard
shows, the pick is `getKeyguardRefreshRateId()` (= 120), except when the mode
(`Settings.Secure oplus_customize_screen_refresh_rate`, 2 = Standard) is Standard, where it is
SystemUI's configured rate instead.

Closed to an unprivileged caller on this build: `setAppOverrideRefreshRate` and the other
override-store calls (`oplus.permission.OPLUS_COMPONENT_SAFE`), `requestRefreshRateCommon`,
`setFollowFocusEnable` and `getVoteInfo` (`com.oplus.permission.safe.WINDOW`).
