# Trails

Trails turns frequently walked blocks into configurable paths and can apply a gradual speed bonus on those paths.
This repository is the owner-maintained Kotlin rewrite used by the RusCrafting survival server and published through
the existing Trails Spigot resource.

## Compatibility

- Paper/Purpur 1.21.11
- Java 21 or newer
- Existing Trails 1.9 `config.yml`, `players.yml`, locale files, and block metadata
- Optional CoreProtect and PlaceholderAPI integrations

Claim plugins are supported through cancellable Bukkit events instead of private or reflection-based APIs.

## Configuration

Trails 2.1 uses three versioned operator files:

- `config.yml` contains gameplay, world, storage, command, and integration settings.
- `trails.yml` contains structured, weighted trail definitions and their stages.
- `roads.yml` contains the opt-in Roads safety limits, world allowlist, replaceable surfaces, and profiles.

Every duration and percentage states its unit in the key. Trail IDs such as `DirtPath` are persisted in block
metadata and should not be renamed casually.

When a 1.9 `config.yml` is detected, Trails validates it completely, writes `config.v1.backup.yml`, generates the
current files through temporary files, validates the generated result, and replaces `config.yml` last. Schema v2 is
likewise migrated to v3 with `config.v2.backup.yml`; obsolete plugin-specific adapter settings are intentionally
dropped. A failed migration leaves the old file untouched. `players.yml` and the `trails:w` / `trails:n` block keys
are never rewritten by the config migrator.

Bundled locales use MiniMessage. Existing locale files without a `format` key continue to use legacy ampersand
colors and inherit missing bundled messages without being overwritten. Dynamic values such as player names are
inserted as non-parsing placeholders.

Useful operator commands:

- `/trails reload` validates and atomically applies both configuration files.
- `/trails validate` validates files on disk without applying them.
- `/trails status` shows schema versions, world scope, loaded definitions, and integration state.
- `/trails give inspect [player]` gives the tagged inspection stick.
- `/trails give advance [player]` gives the tagged trail-advance shovel.
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
only to the builder and never changes server blocks. Materials without a full-cube collision shape, such as
`DIRT_PATH`, are announced in chat and represented by full-height yellow concrete in the preview so their fake
collision cannot trap the client; commit still places the selected real material. Movement samples up to the configured
segment distance are connected continuously, with interpolated surface height; longer or steeper jumps safely start
a new segment. Existing materials from any configured road profile are valid starting and repainting surfaces. Commit
rechecks loaded chunks, exact block snapshots, the paintable surface allowlist, headroom, and protection events before
applying the whole plan on the server thread. A failed
apply is rolled back; the last commit for up to 10 builders is stored atomically in `road-history.yml`. Undo succeeds
only while every road block still exactly matches the committed snapshot, so it cannot overwrite later edits.

## Build

```bash
./gradlew clean check shadowJar
```

The deployable JAR is written to `build/libs/Trails-2.1.3.jar`.

The Gradle wrapper is pinned to 9.6.1 with official distribution and wrapper checksums. Dependency and plugin versions
are centralized in `gradle/libs.versions.toml`, resolved versions are committed in Gradle lock files, and Maven
repositories are restricted to the groups they are expected to serve. CI runs the complete build on Java 21 and Java
25, while the published classes target Java 21 bytecode.

`arc-core` was evaluated for configuration support and is small enough at runtime, but it is intentionally not a
build dependency: Trails is public while `arc-core` is private, so depending on it would make public CI and external
builds non-reproducible. Trails keeps the required atomic YAML behavior in its own narrow adapter instead.

## Architecture

- `domain` — trail parsing, selection, progression, decay, speed decisions, and road geometry without Bukkit.
- `config` — one configuration owner builds complete validated reload candidates from the versioned split files,
  transactional legacy migration, and MiniMessage/legacy localization.
- `storage` — player preferences, block metadata compatibility, and restart-safe road undo history.
- `bukkit` — listeners, commands, safe road preview/commit orchestration, and a runtime-task supervisor that atomically
  replaces reloadable schedulers while owning walk-speed restoration and shutdown cleanup.
- `integration` — generic Bukkit protection events, CoreProtect logging, and PlaceholderAPI.

## License

The Trails source retains the repository's existing [Unlicense](LICENSE). The deployable JAR also contains relocated
third-party libraries under their own licenses; see `THIRD_PARTY_NOTICES.txt` inside the artifact.
