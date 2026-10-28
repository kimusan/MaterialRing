# Aro

A clean-room, local-first Android companion app for a smart ring. It talks to the
ring directly over
Bluetooth Low Energy, stores every health reading on the device, and shows a modern
Material 3 (Material You) UI.

The original app is a QC Wireless white-label built on the Oudmon BLE SDK. This project
reuses the *wire protocol* (reverse-engineered and verified against the decompiled APK)
but writes every layer from scratch.

## Why this exists

The stock app phones home to a dozen third-party servers (its own cloud, Baidu location
and AI, Firebase, Microsoft speech, several CDN buckets). This app does not. There is no
backend, no analytics, no account. All data lives in a local SQLite database via Room.
The only outward path is Google Health Connect, which is off by default and only writes
when you explicitly turn it on in Settings.

## What it does

- **Scans and binds** the ring by its fixed advertising-name prefix, using
  standard Android bonding (there is no custom pairing handshake to port).
- **Streams live vitals**: real-time heart rate and battery level update on the home
  screen with no polling.
- **Syncs history** over BLE: steps, manual blood pressure, and heart-rate samples. Sync
  is resumable (it re-queries the device's data-distribution bitmap after a dropped link)
  and idempotent (reads upsert on a composite key, so re-pulling a range never creates
  duplicates).
- **Manual blood pressure** in mmHg. **Auto (continuous) blood pressure** is behind a flag
  until the raw-to-mmHg mapping is captured from the physical ring.
- **Profiles**: multiple on-device profiles, instant switching (a query filter, not a
  logout), no cloud account.
- **Body battery**: a derived wellness score (ported from the original's algorithm),
  clearly labeled as a metric, not a clinical vital.

## What it deliberately does not do

- No network at all. No cloud, no websocket, no analytics, no location, no speech.
- No firmware update in v1.
- No media/file transfer (the original's photo/album/ebook handlers are not ring health).

## Architecture

A single Gradle module with package-enforced boundaries:

```
com.kschulz.aro/
  data/ble/            RingBleTransport (GATT), RingCommandClient, RingProtocolCodec,
                       Opcodes, Crc16, SumCheck, ByteOrder, RingPayloads, RingScanner
  data/model/          Room entities: Profile, Ring, StepDay, SleepSession, SleepSegment,
                       HeartRateSample, BloodPressure, BodyBatteryPoint, AppSetting
  data/db/             AroDatabase, DAOs, type converters
  data/repository/     ProfileRepository, DeviceRepository, HealthRepository,
                       SettingsRepository (all profile-scoped)
  data/sync/           SyncEngine, SyncJob (WorkManager), DistributionBitmap, TimeSource
  domain/              BodyBatteryAlgorithm, BodyBatteryUseCase
  ui/                  Compose screens + Material 3 theme (dynamic color)
  di/                  Hilt modules
```

### The BLE layer is the swappable part

The transport (`RingBleTransport`) owns the Android GATT connection and knows nothing
about opcodes or payloads: it writes an already-framed `Frame` and emits raw notify
bytes. The codec (`RingProtocolCodec`) is specific to this ring: it adds the transmit header and
CRC, and strips the receive frame. A different ring brand is a new codec plus transport
binding; nothing above `data/ble` changes.

### Protocol (summary)

The data channel is a serial-port (NUS-style) service. Framing is asymmetric:

- **Phone → device**: `[0xC4 | cmd | len_lo | len_hi | crc16_lo | crc_hi | payload]`
  (CRC-16/MODBUS over the payload; empty payload → `[0xC4|cmd|00|00|FF|FF]`).
- **Device → phone**: `[cmd | payload | sum_checksum]` (additive byte-sum, no 0xC4, no
  length field; bit 0x80 of the cmd byte is the error flag).
- **Byte order is directional**: little-endian on the way out, big-endian on the way in.

The protocol core is pinned by unit tests (`RingProtocolCodecTest`, `SyncEngineTest`):
CRC-16/MODBUS check value, both frame layouts, the sum checksum, the directional byte
order, the payload parsers, and the resumable/idempotent sync loop. The authoritative,
smali-traced write-up of every opcode and byte layout lives in `re/PROTOCOL.md` (a
local-only working document, not committed).

## Building

Requires JDK 17 and the Android SDK (platform 34). Point the SDK at a local install via
`local.properties` (`sdk.dir=...`), which is gitignored.

```sh
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:testDebugUnitTest      # JVM unit tests (protocol core + sync)
```

There are no network tests, because the app has no network.

## Status

The protocol core (frame, checksums, byte order, opcodes) is implemented and unit-tested.
The Room data layer, the GATT transport/command client, the resumable sync engine, and the
body-battery algorithm are implemented. On-device validation against the physical ring is
the remaining step.

## Privacy

All health data is stored on-device in Room and is deletable. Nothing leaves the device
except an explicit, opt-in write to Google Health Connect. See `CONTRIBUTING.md` for the
rule that phone-home domains must never be reintroduced.

## License

MIT. See `LICENSE`.
