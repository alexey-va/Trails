# Trails 2.3.5

Trails naturally turns frequently walked terrain into configurable paths. Version 2.3.5 reduces server-thread storage work, keeps Trails standalone when optional ARC telemetry is unavailable, and includes the biome trails and road fixes added since the previous Spigot release.

## Update notes

### Performance

- Recovery-journal commits and acknowledgements run on a dedicated I/O worker instead of the server tick thread, removing filesystem writes and fsync waits from world-save, chunk-load, and chunk-unload handlers.
- Trail state is decoded once per loaded chunk and served from an in-memory index. Repeated writes to the same chunk coalesce, and encoded state is reused until that chunk changes.
- The periodic flush task processes up to eight dirty chunks per invocation in round-robin order. World-save events can advance another batch; unloading queues that chunk separately.
- Failed writes remain queued for retry, and pending snapshots overlay older disk state when a chunk reloads. As with asynchronous saves, abrupt process or host loss can lose updates that have not reached their first durable journal commit.

### Also included since 2.2.5

- Biome-specific natural trails, subtle progress effects, a localized inspector progress bar, protected worn shoulders, and edge-first idle decay.
- Correct stair alignment across wide-road transition rows, including corners and backwards route capture.
- `/trails debug stats` for loaded trail stages, decay activity, protection vetoes, and average movement-processing cost.
- Fixes for zero-chance progression, walk-counter overflow, transient state cleanup, and corrupt road history.
- Optional ARC telemetry remains optional: Trails works without ARC or its API. The bundled shared runtime is updated to arc-core 2.7.18.

Replace the old JAR with `Trails-2.3.5.jar` and restart the server. Keep your existing configuration; missing bundled keys and definitions merge forward without replacing your values. Requirements remain Paper/Purpur 1.21.11 and Java 25.

## Requirements

- Paper or Purpur 1.21.11
- Java 25
- Optional: CoreProtect and PlaceholderAPI

## Highlights

- Biome-aware meadow, forest, mossy, snow, desert, beach, badlands, mushroom, generic sand, rocky, and universal fallback trails.
- Weighted, multi-stage trail progress stored per chunk with bounded checksummed data and crash recovery.
- Quiet player-only milestone particles, a stage-change sound, and a localized inspector progress bar without chat spam.
- Popular terminal-stage routes can grow one protected pair of worn shoulders, capped at three blocks total.
- Edge-first idle decay waits for a configurable quiet period and remains conservative across restarts.
- Gradual configurable path speed boosts.
- `/trails give inspect` and `/trails give advance` issue NBT/PDC-tagged tools; ordinary sticks and shovels do nothing.
- `/trails build start <profile>` records a route and shows a client-only preview before commit.
- Connected gaps, diagonal routes, backwards movement, terrain grading, whole stair rows aligned to the route and higher terrain, slabs, clearance, rollback, and exact undo.
- 29 bundled road profiles, including eight seven-block-wide `-big` designs with paired lanterns or beacons.
- Generic protection-plugin compatibility through cancellable Bukkit events, without hard dependencies on niche claim plugins.
- Russian, English, and Chinese locales.

## Commands

- `/trails on|off [player]`
- `/trails boost on|off [player]`
- `/trails show`
- `/trails give inspect|advance [player]`
- `/trails build list [profile]`
- `/trails build start <profile> [player]`
- `/trails build commit|cancel|status|undo [player]`
- `/trails reload`, `/trails validate`, `/trails status`
- `/trails debug inspect|pulse|decay|stats` for bounded operator QA from a player or console

See `plugin.yml` for the complete permission list. Roads are disabled by default and must be explicitly enabled and scoped to worlds in `roads.yml`.

## Updating

Back up the plugin folder and replace the JAR. Trails validates legacy configuration and writes versioned backups where a structural migration is required. Existing modern configs are merged forward: missing bundled keys and definitions in `config.yml`, `trails.yml`, and `roads.yml` are copied in once, while your values and unknown custom keys are preserved. A failed migration leaves the old configuration untouched. Legacy per-block PDC keys are intentionally not imported.

The old `/trails road` tree has been removed; use `/trails build`. Trail IDs persisted in chunks should not be renamed casually.

## Safety notes

Road preview blocks are visible only to the builder. Non-full blocks are represented by full-height preview material to avoid client collision glitches; commit still places the real configured block. Commits revalidate loaded chunks, exact block snapshots, surface rules, clearance, form support, and protection events, then roll back a partial failure.

The deployable JAR contains the complete Trails and third-party license texts. Source, detailed configuration documentation, and the changelog are available in the repository.
