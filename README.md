# Trails

Trails turns frequently walked blocks into configurable paths and can apply a gradual speed bonus on those paths.
This repository is the owner-maintained Kotlin rewrite used by the RusCrafting survival server and published through
the existing Trails Spigot resource.

## Compatibility

- Paper/Purpur 1.21.11
- Java 25
- Existing Trails 1.9 `config.yml`, `players.yml`, and locale files
- Optional CoreProtect and PlaceholderAPI integrations

Claim plugins are supported through cancellable Bukkit events instead of private or reflection-based APIs.

## Configuration

Trails 2.2 uses three versioned operator files:

- `config.yml` contains gameplay, world, storage, command, and integration settings.
- `trails.yml` contains structured, weighted trail definitions and their stages.
- `roads.yml` contains the opt-in Roads safety limits, world allowlist, weighted palettes, periodic forms, and profiles.

Every duration and percentage states its unit in the key. Trail IDs such as `DirtPath` are persisted in the owning
chunk and should not be renamed casually.

When a 1.9 `config.yml` is detected, Trails validates it completely, writes `config.v1.backup.yml`, generates the
current files through temporary files, validates the generated result, and replaces `config.yml` last. Schema v2 is
likewise migrated to v3 with `config.v2.backup.yml`; obsolete plugin-specific adapter settings are intentionally
dropped. A failed migration leaves the old file untouched. `players.yml` is never rewritten by the config migrator.
Legacy per-block `trails:w` / `trails:n` data is intentionally not read or migrated; Trails 2.2 starts block progress
in a compact checksum-protected chunk-PDC v1 store.

Bundled locales use MiniMessage. Existing locale files without a `format` key continue to use legacy ampersand
colors and inherit missing bundled messages without being overwritten. Dynamic values such as player names are
inserted as non-parsing placeholders.

Useful operator commands:

- `/trails reload` validates and atomically applies both configuration files.
- `/trails validate` validates files on disk without applying them.
- `/trails status` shows schema versions, world scope, loaded definitions, and integration state.
- `/trails give inspect [player]` gives the tagged inspection stick.
- `/trails give advance [player]` gives the tagged trail-advance shovel.
- `/trails road list [profile]` shows localized descriptions for all road profiles or one selected profile.
- `/trails road start <profile> [player]` starts a client-only road preview while the player walks.
- `/trails road commit [player]`, `cancel`, `status`, and `undo` manage the bounded road plan.

Giving tools requires `trails.tools.give` (operator by default). Only items issued by Trails carry the required
persistent tag and activate the listeners, so ordinary sticks and shovels are ignored. Tagged tools can be used by
players with `trails.info-tool` or `trails.trail-tool`; both are granted by default, while protection checks remain
active for the advance tool.

Before a player-caused material transition, Trails fires a cancellable `EntityChangeBlockEvent` with the exact target
material. By default it follows with a compatibility `BlockPlaceEvent` for claim plugins that only guard conventional
building; this probe can be disabled under `integrations.protection-events` if a server has an incompatible observer.
Cancellation (or `BlockPlaceEvent.canBuild() == false`) vetoes the transition without a hard dependency. Natural
decay uses `BlockFadeEvent`. Ordinary step-counter increments do not emit world-change events.

Roads are disabled by default and require `trails.roads.manage` (operator by default). A fake-block preview is sent
only to the builder and never changes server blocks. Its default budget is 2048 blocks for 10 minutes. Starting a
preview emits one compact apply instruction. Materials without a full-cube collision shape, such as `DIRT_PATH`,
stairs, slabs, fences, and lanterns, are represented by full-height yellow concrete so fake collision cannot trap the
client; commit still places the selected real block data.

Movement samples up to the configured segment distance are connected continuously over the nearest safe surface.
The complete route is rebuilt as one plan, so a later turn removes obsolete cross-sections instead of accumulating
crosses and stair clusters. Bounded Ramer-Douglas-Peucker smoothing removes small steering noise; configure it with
`movement.smoothing.enabled` and `movement.smoothing.tolerance-blocks`, or set the tolerance to `0.0` for the exact
captured centerline. The effective tolerance never exceeds half the profile width, so every captured point remains
inside the road; one-block profiles therefore stay exact. Preview updates are coalesced to the road manager tick and
sent as a client-only diff. Flying is captured by default when terrain is within the configured search depth. A
longer jump still paints only its landing
row instead of silently losing the road or rasterizing the gap; teleports cancel the session. `safe-solid` replacement
accepts ordinary solid terrain, including stone, while always excluding block entities, ores, liquids, waterlogged
blocks, unbreakable and technical blocks, plus the configurable protected list. The legacy explicit allowlist mode
remains available. Gentle cross-slopes are graded to one row height, filling supported one-block depressions and
excavating only ordinary replaceable terrain. `limits.max-cross-slope-blocks` bounds that work and prevents wide
profiles from painting detached outer strips on cliffs. An isolated one-row height spike or depression is flattened,
while sustained rises still receive transitions. `clearance.height-blocks` and the strict `clearance.materials`
allowlist remove harmless plants and snow from the walking space; preview, protection checks, compensation, rollback,
and undo cover those removals exactly.

Each lane and height transition accepts either one material or a weighted map whose integer percentages total 100,
for example `{COBBLESTONE: 70, MOSSY_COBBLESTONE: 30}`. The selection is stable for the lifetime of one preview.
One-block height changes can use bottom slabs or direction-aware bottom stairs; stairs face along the road toward the
higher end. A height transition is emitted only when the centerline changes height and a lane follows the same step,
which prevents isolated side slopes from producing sideways stair tangles. Periodic forms are reusable rotated
structures with forward, lateral, and vertical offsets. Their interval, side alternation, placements, and weighted
materials are configurable. The bundled `lantern_lane` profile places an alternating cobblestone-wall, fence, and
lantern post every 12 blocks. The bundled catalog now contains 21 profiles, including forest, cherry, alpine, royal,
ancient-tuff, frozen, badlands, volcanic, prismarine, End, and soul-lit themes. Forms must remain outside road lanes
and are skipped as a whole if any target is occupied, any part intersects any current or later road column, or the
complete form would exceed the preview limit.

Commit rechecks loaded chunks, exact block snapshots, surface safety, headroom, form space, and protection events
before applying the whole plan on the server thread. A failed apply is rolled back. Survival builders with
`trails.roads.collect-drops` can receive removed ordinary blocks when the option is enabled; inventory overflow is
dropped at the builder and owner-locked. A compensated commit is deliberately not undoable, preventing item
duplication. Other last commits for up to 10 builders are stored atomically in `road-history.yml`; undo succeeds only
while every road block still exactly matches the committed snapshot, so it cannot overwrite later edits.

## Build

```bash
./gradlew clean check shadowJar
```

The deployable JAR is written to `build/libs/Trails-2.2.0.jar`.

The Gradle wrapper is pinned to 9.6.1 with official distribution and wrapper checksums. Dependency and plugin versions
are centralized in `gradle/libs.versions.toml`, resolved versions are committed in Gradle lock files, and Maven
repositories are restricted to the groups they are expected to serve. CI and published classes target Java 25.

Released `arc-core` runtime and test artifacts are resolved anonymously from the public RusCrafting Reposilite
repository. Trails shades and relocates `arc-core-paper` for lifecycle, task ownership, and bounded runtime-health
diagnostics; tests use `arc-core-paper-testing` to own MockBukkit's process-global lifecycle consistently. Production
persistence remains in Trails' narrow adapters. Trail block state is decoded once per loaded chunk, served from an
in-memory index, and coalesced into one bounded binary chunk payload every second, on chunk unload, world save, or
plugin shutdown.

## Architecture

- `domain` — trail parsing, selection, progression, decay, speed decisions, and road geometry without Bukkit.
- `config` — one configuration owner builds complete validated reload candidates from the versioned split files,
  transactional legacy migration, and MiniMessage/legacy localization.
- `storage` — player preferences, chunk-local trail state, and restart-safe road undo history.
- `bukkit` — listeners, commands, safe road preview/commit orchestration, and a runtime-task supervisor that atomically
  replaces reloadable schedulers while owning walk-speed restoration and shutdown cleanup.
- `integration` — generic Bukkit protection events, CoreProtect logging, and PlaceholderAPI.

## License

The Trails source retains the repository's existing [Unlicense](LICENSE). The deployable JAR also contains relocated
third-party libraries under their own licenses; see `THIRD_PARTY_NOTICES.txt` inside the artifact.
