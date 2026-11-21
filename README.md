# Aro

A clean-room, local-first Android companion app for smart rings. *"Aro"* means **"ring"**
or **"hoop"** in Spanish, Portuguese, and Galician. Aro talks to your ring directly over
Bluetooth Low Energy, stores every health reading privately on the device in Room, and
presents a modern Material 3 (Material You) UI with interactive charts and home screen
widgets.

## Why this exists

Stock smart-ring apps (such as QRing and JRing) phone home to dozens of third-party
servers (vendor clouds, Baidu location/AI, Firebase, speech SDKs, CDN buckets).
**Aro does not.** There is no backend, no analytics, no account, and no internet
permission. All health data stays in a local SQLite database via Room. The only
outward path is **Google Health Connect**, which is off by default and only writes
when you explicitly opt in and grant permissions in Settings.

## Supported ring models & protocols

Aro automatically detects the ring's BLE protocol from its GATT services when connecting:

| Protocol family | Supported models / BLE prefixes | GATT command channel | Framing |
|---|---|---|---|
| **Oudmon / QRing** | `R01`, `R02`, `R03` (tested on `R03_A807`), `R04`, `R05`, `R06`, `R11`, `VK-5098`, `MERLIN`, `Hello Ring`, `RING1`, `boAtring`, `Y25`, `H59`, `DS1314`, `DS520`, `DS12` | `6e40fff0-...` (`6e400002` TX / `6e400003` RX) + `de5bf728-...` (`de5bf72a` / `de5bf729` big-data) | 16-byte additive-checksum command frames + `0xBC` CRC-16/MODBUS big-data frames |
| **JRing / Dialog** | `SMART_RING` (tested on Dialog/Renesas DA1458x), `JRing` | `000056ff-...` (`000033f3` TX / `000033f4` RX) + standard Battery `0x180F` (`0x2A19`) | Fixed 20-byte frames (`[cmd, 19 payload bytes]`, no checksum) |

Multiple rings can be bound simultaneously (**Settings → Add ring**), with one
active ring connected at a time, one-tap switching between rings, and per-ring
hardware capability detection (unsupported metrics are clearly dimmed on the Home
screen as unavailable for that ring).

## Features

- **Activity & Steps**: Concentric daily activity rings (steps, calories, distance),
  step history sync (`0x48` + `0x43` fallback on Oudmon; `0x10`/`0x03` on JRing),
  multi-ring daily step summation when switching rings during the day, and live
  `0x73` push updates while connected.
- **Live Streaming Heart Rate, Blood Pressure & SpO2**: Two-phase on-demand
  measurement (optical PPG acquisition followed by a 15-second live stream with
  pulsing **LIVE** indicator, real-time sparkline, in-place Home card updates,
  and early **Save & Stop**), plus periodic history sync (`0x15` / `0x16` HR,
  `0x0D` / `0x14` BP, `0xBC 0x2A` SpO2).
- **Sleep & Stages**: Full overnight hypnogram and stage breakdown (**Deep**,
  **Light**, **REM**, **Awake**) anchored to local wall-clock midnight via
  Oudmon's `0xBC 0x27` new sleep protocol and `0x44` fallback.
- **Skin & Body Temperature (°C)**: Live dual-channel thermistor measurement
  (`0x37`/`0x38`) on rings equipped with a temperature sensor.
- **Stress (1–100) & Body Battery (5–100)**: On-device derived wellness metrics
  for both ring families — hourly charging during sleep and draining with waking
  HR/activity load (clearly labeled as derived wellness metrics, not clinical vitals).
- **Interactive History Charts & Per-Ring Filter**: Daily, Weekly, and Monthly
  plots for every metric with touch scrubbing, date navigation, tap-to-jump from
  any Home card, and a top **Ring source** dropdown to view any individual ring
  or **All rings (Combined)** (including on-the-fly per-ring Body Battery curves).
- **Card Info Popups (`i`)**: Concise explanations on every Home card covering what
  each metric means, how it is calculated, and reference ranges by age group.
- **Configurable Continuous Monitoring**: Choose automatic background measurement
  intervals in **Settings** for Heart Rate (`Off`, `10m`, `15m`, `30m`, `1h`),
  SpO2 (`Off`, `30m`, `1h`), and Blood Pressure (`Off`, `30m`, `1h`, `2h`).
- **4 System-Themed Android Home Screen Widgets**:
  - *Aro • Today (Numbers)* (`2×2`)
  - *Aro • Weekly Steps (Graph)* (`4×2`)
  - *Aro • Sleep & Vitals* (`4×2`)
  - *Aro • Monthly Activity (Graph)* (`4×2`)
  All adapt automatically to Light/Dark mode and Android 12+ Material You dynamic colors.
- **On-Device Profiles & Goals**: Multiple local profiles with instant switching,
  physical stats (height, weight, birth year, gender, BP baseline), and custom goals
  synced to the ring.
- **Google Health Connect (Opt-In)**: Write-only export with runtime permission
  prompts and idempotent `clientRecordId` deduplication for Steps, Distance,
  Calories, Heart Rate, Sleep (with stages), Blood Pressure, and SpO2.
- **Pull-to-Sync, Top-Bar Controls & About Dialog**: Pull down on Home or History
  to sync, use Sync / Reconnect in the top app bar, and view version, author, and
  project links under **Settings → About Aro**.

## What it deliberately does not do

- No network at all — no cloud backend, no telemetry, no analytics, no account, no location tracking.
- No firmware flashing (DFU/SUOTA) to avoid bricking user hardware.
- No unrelated media/photo/ebook transfer handlers.

## Architecture

Single Gradle module with package-enforced boundaries:

```
com.kschulz.aro/
  data/ble/            RingBleTransport (GATT), RingCommandClient, RingProtocol,
                       RingFeatureSupport, RingProtocolCodec (Oudmon 16B + 0xBC),
                       JRingProtocol (20B), GattUuids, Opcodes, Crc16, SumCheck,
                       ByteOrder, RingPayloads, RingScanner
  data/model/          Room v6 entities (with ringMac tagging): Profile, Ring,
                       StepDay, SleepSession, SleepSegment, HeartRateSample,
                       BloodPressure, Spo2Sample, TemperatureSample, StressSample,
                       BodyBatteryPoint, AppSetting
  data/db/             AroDatabase (with MIGRATION_1_2 .. MIGRATION_5_6), DAOs
  data/repository/     ProfileRepository, DeviceRepository, HealthRepository
                       (profile-scoped + per-ring/combined queries), SettingsRepository
  data/sync/           SyncEngine (Oudmon + JRing paths), SyncJob (WorkManager),
                       DistributionBitmap, TimeSource (local-midnight anchoring)
  data/healthconnect/  HealthConnectExporter (androidx.health.connect:connect-client)
  domain/              BodyBatteryAlgorithm, BodyBatteryUseCase
  ui/                  Compose screens (Home, History + Charts, Scan, Settings,
                       ProfileEditorDialog, MetricInfoDialog, AboutDialog) + Material 3 theme
  widget/              4 AppWidgetProviders + WidgetChartRenderer + WidgetDataLoader
  di/                  Hilt modules
```

## Building & Testing

Requires JDK 17 and the Android SDK (platform 34). Point the SDK at a local install
via `local.properties` (`sdk.dir=...`), which is gitignored.

```sh
./gradlew :app:assembleDebug          # build debug APK
./gradlew :app:testDebugUnitTest      # run JVM unit tests (codecs, parsers, sync, body battery)
```

There are no network tests because the app has no network layer.

## Privacy

All health data is stored locally in Room and can be deleted at any time. Nothing
leaves the device except an explicit, opt-in write to Google Health Connect. See
`CONTRIBUTING.md` for the rule that phone-home domains must never be reintroduced.

## License

MIT. See `LICENSE`.
