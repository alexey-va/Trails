# Changelog

## 2.2.5 — 2026-08-27

### Fixed

- Keep stair transitions aligned with the dominant road heading on diagonal raster steps, including routes captured while moving backwards.
- Persist trail progress through chunk-PDC write failures with a bounded, checksummed recovery journal.
- Cancel vanilla block interaction for every tagged Trails tool, including the inspection stick.
- Reject scheduler and cooldown intervals that could overflow or create unusable runtimes.
- Refuse unsupported periodic road forms instead of placing floating posts at cliff edges.
- Retire undo history durably before changing the world, preventing a stale undo record from being replayed after a failed history write.

### Release hardening

- Package complete Unlicense, Apache-2.0, and bStats MIT texts in the deployable JAR.
- Declare every shaded runtime dependency and exact version in `THIRD_PARTY_NOTICES.txt`.
- Upgrade the shared runtime to the Apache-2.0-licensed arc-core 2.0.2 release.

## 2.2.4 — 2026-08-26

- Renamed the Roads command tree from `/trails road` to `/trails build`.
- Added eight wide `-big` road profiles and 29 bundled profiles in total.
- Added safe full-height client previews for non-full collision blocks.
- Added connected route capture, smoothing, terrain grading, direction-aware height transitions, periodic forms, rollback, and restart-safe undo.
- Replaced direct integrations for niche claim plugins with cancellable Bukkit protection events.
