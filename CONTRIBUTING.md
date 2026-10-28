# Contributing

Thanks for helping. This project is a privacy-first reimplementation, so a few rules
matter more here than usual.

## The non-negotiables

1. **No phone-home.** The original app's telemetry domains must never appear in this code
   base. If you add a network dependency, an `Intent` to a third party, an SDK that
   phones home, or a hardcoded host, the change is rejected. The app has no backend.
   Google Health Connect is the single, opt-in, write-only outward path.
2. **Data stays on-device.** Health records live in Room. Do not add a cloud sync, an
   account, or a server. Profile switching is a local query filter, never a logout.
3. **Keep the protocol layer swappable.** `data/ble` separates the transport (GATT,
   brand-agnostic) from the codec (ring-specific). New ring brands add a codec and a
   transport binding; they do not touch the layers above.
4. **Sync must stay idempotent and resumable.** Reads upsert on a composite primary key.
   Do not introduce inserts that can duplicate a re-pulled range.
5. **Body battery is a metric, not a vital.** Keep it labeled as a derived wellness
   score. Do not present it (or any ring-derived BP) as a medical measurement.

## Conventions

- Kotlin. Compose + Material 3 for UI. Coroutines/Flow for async. Hilt for DI.
- Prefer small, single-purpose commits with a conventional-commit subject
  (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`) and a one or two line body on the
  *why*.
- Run the JVM tests before pushing: `./gradlew :app:testDebugUnitTest`.
- The protocol core must stay covered. If you change framing, checksums, byte order, or
  an opcode layout, update `re/PROTOCOL.md` and the tests in the same change.

## What you do not need to do

There is no CI config, no lint gate, and no build farm to appease. Make the tests pass
and keep it honest about what is validated against the physical ring versus what is still
assumed.

## Local-only files

`CLAUDE.md`, `AGENTS.md`, `GEMINI.md`, `.claude/`, the XAPK (`QRing/`), and the
decompilation work area (`re/`) are gitignored and must stay out of the repo. Do not
force-add them.
