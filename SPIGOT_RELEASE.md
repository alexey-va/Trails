# Trails 2.2.5

Trails naturally turns frequently walked terrain into configurable paths. The 2.2 line is a production rewrite for modern Paper/Purpur with versioned configuration, safe tagged tools, client-only road previews, crash recovery, and a large bundled road catalog.

## Requirements

- Paper or Purpur 1.21.11
- Java 25
- Optional: CoreProtect and PlaceholderAPI

## Highlights

- Weighted, multi-stage trails stored per chunk with bounded checksummed data and crash recovery.
- Gradual configurable path speed boosts.
- `/trails give inspect` and `/trails give advance` issue NBT/PDC-tagged tools; ordinary sticks and shovels do nothing.
- `/trails build start <profile>` records a route and shows a client-only preview before commit.
- Connected gaps, diagonal routes, backwards movement, terrain grading, correct stairs/slabs, clearance, rollback, and exact undo.
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

See `plugin.yml` for the complete permission list. Roads are disabled by default and must be explicitly enabled and scoped to worlds in `roads.yml`.

## Updating from 1.9

Back up the plugin folder and replace the JAR. Trails validates the legacy configuration, writes a versioned backup, then generates `config.yml`, `trails.yml`, and `roads.yml` atomically. A failed migration leaves the old configuration untouched. Legacy per-block PDC keys are intentionally not imported.

The old `/trails road` tree has been removed; use `/trails build`. Trail IDs persisted in chunks should not be renamed casually.

## Safety notes

Road preview blocks are visible only to the builder. Non-full blocks are represented by full-height preview material to avoid client collision glitches; commit still places the real configured block. Commits revalidate loaded chunks, exact block snapshots, surface rules, clearance, form support, and protection events, then roll back a partial failure.

The deployable JAR contains the complete Trails and third-party license texts. Source, detailed configuration documentation, and the changelog are available in the repository.
