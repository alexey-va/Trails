# Changelog

## 2.3.0 — 2026-08-27

### Added

- Select natural trail styles by biome, with bundled meadow, forest, moss, sand, rocky, and universal fallback paths.
- Show subtle progress particles at configurable milestones and play a quiet sound when a trail advances, without chat spam.
- Show a localized ten-segment progress bar with the tagged inspector; sneaking keeps detailed chat output.
- Widen fully worn popular routes to one protected pair of shoulders, capped at three blocks total.
- Delay natural decay until a configurable inactivity window and prefer route ends without making closed routes permanent.
- Add bounded `/trails debug inspect|pulse|decay` commands for players and console under the operator-only `trails.debug` permission.

### Compatibility

- Merge newly bundled `config.yml` keys into existing installations once, preserving operator values and unknown keys.
- Keep existing tracked trail identities stable when a biome or definition selection context changes.

## 2.2.6 — 2026-08-27

### Fixed

- Make height-transition stairs ascend toward the higher road block instead of facing downhill.
- Cover every lane of the seven-block-wide `royal-big` profile with a direction regression test.

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
