# Battery monitor — concept

Status: **concept / design only**. Nothing here is built yet.

## The question we want answered

"How much extra battery does 165 Armer cost me?"

That is really **two separate questions**, and the design keeps them apart:

| # | Question | What causes it |
|---|---|---|
| A | How much does the **higher refresh rate** cost? | The panel, GPU and CPU drawing more frames |
| B | How much does **the app itself** cost? | The watchdog waking up, binder calls, logcat |

A is almost certainly the big one. B should be close to zero, and it is worth
proving that, because "the watchdog drains my battery" is an easy accusation
to make.

## What we can read without root

All of these work from a normal app, with no permission:

| Signal | API | Unit | Notes |
|---|---|---|---|
| Current right now | `BatteryManager.getIntProperty(BATTERY_PROPERTY_CURRENT_NOW)` | µA | Noisy. Sign and unit vary by phone, see "Calibration" |
| Charge left | `BATTERY_PROPERTY_CHARGE_COUNTER` | µAh | The best signal for long periods |
| Voltage | `ACTION_BATTERY_CHANGED` sticky intent, `EXTRA_VOLTAGE` | mV | Power = voltage × current |
| Temperature | same intent, `EXTRA_TEMPERATURE` | 0.1 °C | Heat raises drain, so we record it |
| Charging? | same intent, `EXTRA_PLUGGED` | — | Samples taken while charging are thrown away |
| Brightness | `Settings.System.SCREEN_BRIGHTNESS` | 0–255 (or more) | Brightness matters more than refresh rate, so we must record it |
| Our own CPU time | `Process.getElapsedCpuTime()` | ms | Answers question B directly |

What the app **already knows** (inside `ArmWatchService`), which is the part
that makes this feature cheap:

- the panel's **real** refresh rate, measured every frame (`panelEmaNs` from
  the Choreographer callback)
- the **armed** rate and whether the vote is **parked** at 120 or **pinned**
- the app on screen (`focusPkg`)
- whether the screen is on (`screenOn`)

So we don't need to guess what rate the phone was at. We measured it.

## Part 1 — The meter (always-on, passive)

### How it samples

- Piggy-back on the watchdog's **existing tick**. No new timer, no wakelock.
- The watchdog only ticks while the screen is on, so the meter only samples
  while the screen is on. That is exactly when the refresh rate matters.
- Each tick: read current, voltage (cached from the sticky intent, which only
  changes every few seconds), brightness, and the measured panel Hz.
- Compute power: `mW = µA × mV / 1_000_000`.

### How it stores (no raw log)

Don't store every sample. Add each one into a **bucket** instead:

```
bucket key = (measured panel Hz band, brightness band)
bucket value = total seconds, total mWh, sample count
```

Panel Hz bands: `≤10` (idle), `11–65`, `66–100`, `101–130`, `131–150`, `151+`.
Brightness bands: 4 bands, so a dim-room hour is not compared with a sunlight
hour.

Why buckets: a few dozen numbers per day instead of thousands of rows. Saving
them is one small write a minute, and the meter never becomes a drain itself.

Keep 14 days, one set of buckets per day.

### Screen-off drain

While the screen is off the watchdog sleeps, so the meter does too. Instead:

1. At screen-off, save `CHARGE_COUNTER` and the time.
2. At screen-on, read it again.
3. The difference ÷ time = idle drain in mA.

No wake-ups at all. This also catches the case of a vote that is accidentally
still held while the screen is off.

### Calibration (OnePlus quirk)

Android says `CURRENT_NOW` is in µA and negative while discharging. Some
OnePlus/OPPO firmware reports **mA** and/or a **positive** number while
discharging. Fix it once, automatically:

1. Take a reading while unplugged. A phone with the screen on draws roughly
   200–2000 mA.
2. If the number is between 200 and 2000, it's in mA. If it's between 200 000
   and 2 000 000, it's in µA.
3. If it is positive while unplugged, flip the sign.
4. Save the result. Show it in **Settings**, the same way the vendor-call
   detection is shown today.

## Part 2 — The battery test (controlled A/B)

The meter shows **correlation**: "165 Hz hours used more power". But 165 Hz
hours might also be gaming hours, so the meter can't prove the rate caused
it. A controlled test can.

Put a **Battery test** button next to the existing smoothness test
(`FpsTestActivity`):

1. Ask the user to unplug, then lock the brightness at a fixed level for the
   test (and put it back afterwards).
2. Show the same moving content as the smoothness test.
3. Switch the rate in **blocks**: 120 → 165 → 120 → 165 … 60 seconds each,
   for 8–10 minutes in total.
4. Throw away the first 10 seconds of each block (the rate is still changing
   and the fuel gauge lags).
5. Average the power of each block.

Why alternate instead of "5 min at 120, then 5 min at 165": the phone warms
up and the battery voltage drops over time. Both make later blocks look
worse. Alternating spreads that drift evenly over both rates, so it cancels
out.

Result card:

```
120 Hz   1 840 mW
165 Hz   2 110 mW
Extra    +270 mW  (+15 %)
≈ 35 min less screen-on time per full charge
```

(Those numbers are placeholders, not measurements.)

Optional second run: **still screen** instead of moving content. This
measures what the parking logic saves, since a parked still screen should
idle at 1 Hz.

## Part 3 — The app's own cost (question B)

Cheap and exact, no estimation needed:

- `Process.getElapsedCpuTime()` at screen-on and screen-off → CPU ms the app
  used per screen-on hour.
- Count the watchdog ticks and the binder calls per hour.
- Show: "165 Armer used 0.4 s of CPU in the last hour (≈ 0.01 %)".

If that number ever gets large, it is a bug (for example, the "Arm all"
storm described in `ArmWatchService`). So this also works as a regression
alarm.

## Part 4 — Lab mode (adb, for exact numbers)

For people who want measurements they can put in the README. A script in
`tool/`, like the existing `probe-*.sh` ones:

- `dumpsys batterystats --reset`, use the phone, then
  `dumpsys batterystats --charged tf.arm165` → the system's own estimate for
  the app.
- **To check:** whether this phone exposes power rails (ODPM) to Perfetto's
  `android.power` data source. If it does, we get the **display rail on its
  own**, which is the cleanest possible answer to question A. Pixels expose
  them; Snapdragon OnePlus phones need testing.
- **To check:** whether `pm grant tf.arm165 android.permission.DUMP` works on
  ColorOS (it is a "development" permission, like `WRITE_SECURE_SETTINGS`
  which the app already uses). If so, the app could read batterystats itself.

## What the user sees

A **Battery** page, opened from Settings (same style as the Log page):

```
Now                     1 920 mW  ·  165 Hz  ·  34 °C

Today (screen on 3 h 12 m)
  Panel rate     Time     Avg power
  Idle (≤10 Hz)  48 m       610 mW
  101–130 Hz     1 h 05 m  1 450 mW
  151+ Hz        1 h 19 m  1 980 mW

Screen off       9 h 40 m    38 mA

165 Armer itself  0.9 s CPU today

[ Run battery test ]
```

## Things that can go wrong

| Problem | What we do about it |
|---|---|
| `CURRENT_NOW` is noisy and the gauge updates every few seconds | Only show averages over ≥ 1 minute |
| Brightness dominates the result | Brightness bands in the meter, fixed brightness in the test |
| Charging corrupts the numbers | Drop every sample while plugged in |
| Some firmware returns 0 or garbage for current | Fall back to `CHARGE_COUNTER` deltas (slower but reliable); hide the "Now" line |
| The meter itself drains battery | No own timer, no wakelock, bucketed storage; Part 3 measures it anyway |

## Build order

1. **Calibration + "Now" line** in Settings. Smallest useful step, proves the
   readings are sane on the OnePlus 15.
2. **Meter buckets + Battery page.**
3. **App self-cost line.**
4. **Battery test (A/B).**
5. **Lab script** in `tool/`.

## Files it would touch

- `ArmWatchService.kt` — call the meter from the existing tick, and at
  screen on/off.
- new `BatteryMeter.kt` — calibration, buckets, storage.
- new `BatteryActivity.kt` + layout — the page.
- `FpsTestActivity.kt` / `FpsTestView.kt` — reuse the moving content for the
  test.
- `SettingsActivity.kt` — entry row and calibration status.
- `values/strings.xml` and the 简体中文 strings.
- `AndroidManifest.xml` — no new permissions needed for parts 1–3.
