# Aro Clean-Room App — Design

Date: 2026-10-27
Status: proposed, pending review
Companion: `re/PROTOCOL.md` (BLE protocol, authoritative), `re/CAPTURE_RUNDOWN.md`
(open auto-BP item)

## Goal

A local-first, privacy-respecting Android app that talks to a smart ring
directly over BLE, stores all health data on-device, and exposes a modern Material 3
UI. No cloud, no analytics, no third-party location or speech. Any data shared outward
(Google Health Connect) is an explicit user toggle, off by default.

The original app is a QC Wireless white-label built on the Oudmon BLE SDK. We reuse
the *protocol* (verified in `re/PROTOCOL.md`) but write every layer from scratch.

## Non-negotiables

- All health data lives in Room on-device. Nothing leaves the device except an
  opt-in Health Connect export the user turns on.
- None of the phone-home domains from `re/PROTOCOL.md` §9 appear anywhere.
- Profile switching is instant (a query filter), never a logout/timeout.
- Sync survives a dropped connection: resume from the device distribution bitmap
  (0x46) and upsert by a unique key so re-pulls cannot create duplicate records.
- The BLE layer is swappable: a different ring brand is a new codec + transport,
  not a rewrite.

## Tech stack

| Concern | Choice |
|---------|--------|
| Language | Kotlin |
| UI | Jetpack Compose, Material 3, dynamic (Material You) colors |
| Async | Kotlin Coroutines + Flow |
| BLE | Android `BluetoothLeScanner` + `BluetoothGatt`, wrapped in our own transport |
| Persistence | Room (SQLite) |
| Background sync | WorkManager |
| DI | Hilt |
| Health export | `androidx.health.connect.client`, opt-in |
| Icons | Vector, crisp, consistent 24dp grid |

## Module layout (single Gradle module, package-enforced boundaries)

```
com.kschulz.aro/
  data/ble/            RingBleTransport, RingProtocolCodec, Frame, Opcodes, Crc16, SumCheck
  data/model/          Ring, Profile, StepDay, SleepSession, SleepSegment,
                       HeartRateSample, BloodPressure, BodyBatteryPoint
  data/db/             AroDatabase, Room entities, DAOs, TypeConverters
  data/repository/     DeviceRepository, HealthRepository, ProfileRepository,
                       BodyBatteryRepository
  data/sync/           SyncEngine, SyncJob (WorkManager), DistributionBitmap
  data/healthconnect/  HealthConnectExporter (opt-in, write-only)
  domain/              UseCases (thin, one per screen intent)
  ui/
    device/            scan, bind, connection state
    home/              dashboard: steps, HR, BP, sleep, body battery
    history/           per-metric detail + range picker
    settings/          profiles, health-connect toggle, about
  di/                  Hilt modules
```

## BLE layer

### RingBleTransport (Android GATT, brand-agnostic)
- `connect(BluetoothDevice)`, `disconnect()`, `connectionState: Flow<ConnectionState>`
- `write(frame: Frame)` -> writes the framed bytes to the `de5bf72a-...` write char.
- `notifies: Flow<ByteArray>` -> raw notify payloads from `de5bf729-...`.
- Owns CCCD enable (write `00002902` = `01 00`) and reconnection.
- No knowledge of opcodes or payloads.

### RingProtocolCodec (ring-specific, swappable)
- `Opcodes` object: the full table from `re/PROTOCOL.md` §4.
- TX: `encode(cmd, payload): Frame` -> `[0xC4 | cmd | len_lo | len_hi | crc16_lo | crc16_hi | payload]`; empty payload -> `[0xC4|cmd|00|00|FF|FF]`.
- RX: `decode(raw: ByteArray): Parsed` -> strips cmd byte (mask `& 0x7F` for error flag) and trailing sum-checksum; verifies `Σ(data[0..n-2]) & 0xFF == data[n-1]`.
- Byte order helpers are **separate and directional**: `toLeBytes(int)` for TX,
  `fromBeBytes(byte[], offset): int` for RX. Do not unify.
- `Crc16.calc(payload)` init 0xFFFF poly 0xA001 reflected (TX only).
- A second brand = a new `RingProtocolCodec` + `RingBleTransport` binding. Nothing
  above `data/ble` changes.

### Command correlation
The app keeps a `Channel<PendingRequest>` per outstanding opcode. Because the RX
frame carries the cmd byte, responses are routed back to the awaiting coroutine by
opcode. A write with no expected response (e.g. a switch command) just fires.

## Data model (Room)

Every health table is scoped by `profileId`.

- `Profile(id PK, name, isDefault, createdAt)`
- `StepDay(profileId, date, steps, distanceMeters, calories, durationMin, PK(profileId,date))`
- `SleepSession(profileId, startEpoch, endEpoch, isNap, PK(profileId,startEpoch))`
- `SleepSegment(sessionId FK, stage, durationMin)`
- `HeartRateSample(profileId, epochMs, bpm, PK(profileId,epochMs))`
- `BloodPressure(profileId, epochMs, sbp, dbp, source, PK(profileId,epochMs,source))`
  where `source in {MANUAL, AUTO}`
- `BodyBatteryPoint(profileId, epochHour, score, PK(profileId,epochHour))`

The composite PKs on the sample/reading tables are what make sync idempotent: an
`INSERT OR REPLACE` (or `ON CONFLICT DO UPDATE`) keyed on them means re-pulling an
already-synced range changes nothing.

## Sync engine (the resilient part)

Flow, driven by a `SyncJob` (WorkManager, periodic + on-demand) and a manual "sync now":

1. `connect()` (reconnect if needed).
2. `cmd 0x46 QUERY_DATA_DISTRIBUTION` -> `DistributionBitmap` of unsynced day ranges.
3. For each unsynced range, issue the matching history commands:
   - steps `0x48` (today) / `0x43` (someday detail) / `0x07` (per-day total)
   - sleep `0x44` -> `SleepNewProtoResp` (segments + main + nap)
   - HR `0x15` -> 13-byte records
   - manual BP `0x14` -> streamed `[ts|sbp|dbp]` packets until sentinel/50
   - auto BP `0x0D` -> header + 13-byte value records (only if mapping known)
4. Upsert each into Room by its PK.
5. Re-query `0x46` at the end to confirm the bitmap cleared; if the connection dropped
   mid-way, the next run simply re-queries and continues, and the upsert key prevents
   duplicates.

This is exactly the resumable, duplicate-free behavior required.

## Real-time path

`0x1E REAL_TIME_HEART_RATE` (single byte) and `0x03 GET_DEVICE_ELECTRICITY_VALUE`
(battery) are observed live from `transport.notifies`, filtered by the codec, and
exposed as `Flow`s into the home screen. No polling.

## Body battery (ported)

`BodyBatteryAlgorithm` from the original (weights extracted in `re/PROTOCOL.md` §8)
becomes `domain/BodyBatteryUseCase`. Inputs: sleep projection, HR series, step energy.
Output: an hourly `score` (0-100) stored in `BodyBatteryPoint` and rendered as a tile
on the home screen. It is a derived wellness metric, clearly labeled as such, and is
not presented as a clinical vital.

## Blood pressure

- Manual (0x14): implement now. Returns sbp/dbp in mmHg directly.
- Auto (0x0D): code the parser now, but keep it behind a feature flag that is off
  until the raw->mmHg mapping CSV from `re/CAPTURE_RUNDOWN.md` exists. The mapping
  becomes a small pure function `rawToMmHg(raw): Pair<Int,Int>` (or LUT) loaded from
  the captured calibration, unit-tested against the CSV rows.

## Health Connect (opt-in)

- Off by default. A toggle in Settings.
- On enable: request `health.WRITE_STEPS`, `health.WRITE_HEART_RATE`,
  `health.WRITE_SLEEP`, `health.WRITE_TOTAL_CALORIES_BURNED` (the same write scopes the
  original manifest already lists). No read scopes.
- `HealthConnectExporter` copies Room data to Health Connect records. Write-only.
- No network, no account, no server.

## What we deliberately do NOT port

- Body-battery is the only "algorithm" we port, and only because the user asked.
- Media/file handlers (FileHandle, RecordHandle, AlbumHandle, EbookHandle,
  AvatarHandle) — not ring health.
- All phone-home domains (§9). Baidu location/AI, Firebase, Microsoft speech, QC cloud,
  websocket channel, watch-face and guide CDNs.
- No custom pairing cipher exists to port (the bind is standard Android bonding +
  name-prefix discovery using the ring's fixed advertising prefix).

## Screens (Material 3, dynamic color)

1. **Scan / Bind** — device discovery by prefix, connect, first-time baseline
   (set device time `0x01`, set phone OS `0x04`).
2. **Home** — tiles: today's steps/calories/distance, current HR, latest BP, last
   night's sleep, body-battery sparkline. Pull-to-sync.
3. **History** — per-metric list with a range picker; reads Room.
4. **Settings** — profiles (add/switch/delete, instant), Health Connect toggle,
   device info (FW/HW revision via the DEVICE_INFO service chars), about.

## Testing

- Unit: `Crc16`, `SumCheck`, directional byte-order helpers, `DistributionBitmap`,
  `rawToMmHg`, `BodyBatteryUseCase`, `SyncEngine` upsert idempotency (feed the same
  range twice, assert no duplicate rows).
- Instrumented: `RingProtocolCodec.encode/decode` round-trip against the exact frame
  bytes captured during the live session.
- No network tests (there is no network).

## Commits & handoff

- Conventional commits, body 2-3 lines on the *why*, author `Kim Schulz
  <kim@schulz.dk>`.
- AI-assistant files (CLAUDE.md, .claude/, AGENTS.md, GEMINI.md) stay gitignored and
  never committed (project git policy).
- Handoff files stay local: `re/PROTOCOL.md`, `re/CAPTURE_RUNDOWN.md`, and a
  `docs/DEV_STATE.md` that records dev state so another AI resumes without rework.

## Open items (blocked on the ring)

- Auto-BP raw->mmHg mapping (`re/CAPTURE_RUNDOWN.md`).
- Confirm 0x46 distribution bit layout (which bits = which days) during capture.
- Decode the two trailing bytes of the 0x44 sleep request (date semantics) during
  capture.

These do not block scaffolding, manual BP, HR, steps, sleep, or body battery.
