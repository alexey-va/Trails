# Trails

Trails turns frequently walked blocks into configurable paths and can apply a gradual speed bonus on those paths.
This repository is the owner-maintained Kotlin rewrite used by the RusCrafting survival server and published through
the existing Trails Spigot resource.

## Compatibility

- Paper/Purpur 1.21.11
- Java 21 or newer
- Existing Trails 1.9 `config.yml`, `players.yml`, locale files, and block metadata
- Optional Lands, WorldGuard, CoreProtect, PlaceholderAPI, Towny, GriefPrevention, PlayerPlot, RedProtect,
  Residence, LogBlock, and Dynmap integrations

The unfinished legacy road-template editor is intentionally excluded from 2.0.

## Configuration

Trails 2.x uses two versioned operator files:

- `config.yml` contains gameplay, world, storage, command, and integration settings.
- `trails.yml` contains structured, weighted trail definitions and their stages.

Every duration and percentage states its unit in the key. Trail IDs such as `DirtPath` are persisted in block
metadata and should not be renamed casually.

When a 1.9 `config.yml` is detected, Trails validates it completely, writes `config.v1.backup.yml`, generates both
v2 files through temporary files, validates the generated result, and replaces `config.yml` last. A failed
migration leaves the legacy file untouched. `players.yml` and the `trails:w` / `trails:n` block keys are never
rewritten by the config migrator.

Bundled locales use MiniMessage. Existing locale files without a `format` key continue to use legacy ampersand
colors and inherit missing bundled messages without being overwritten. Dynamic values such as player names are
inserted as non-parsing placeholders.

Useful operator commands:

- `/trails reload` validates and atomically applies both configuration files.
- `/trails validate` validates files on disk without applying them.
- `/trails status` shows schema versions, world scope, loaded definitions, and integration state.

## Build

```bash
./gradlew clean check shadowJar
```

The deployable JAR is written to `build/libs/Trails-2.0.0.jar`.

The Gradle wrapper is pinned to 9.5.0 with its official distribution checksum. Dependency and plugin versions are
centralized in `gradle/libs.versions.toml`; Maven repositories are restricted to the groups they are expected to
serve. CI runs the complete build on Java 21 and Java 25, while the published classes target Java 21 bytecode.

`arc-core` was evaluated for configuration support and is small enough at runtime, but it is intentionally not a
build dependency: Trails is public while `arc-core` is private, so depending on it would make public CI and external
builds non-reproducible. Trails keeps the required atomic YAML behavior in its own narrow adapter instead.

## Architecture

- `domain` — trail parsing, selection, progression, decay, and speed decisions without Bukkit.
- `config` — versioned split configuration, transactional legacy migration, and MiniMessage/legacy localization.
- `storage` — player preferences and block metadata compatibility.
- `bukkit` — listeners, commands, schedulers, and particles.
- `integration` — protection, logging, map, and PlaceholderAPI adapters.

## License

The Trails source retains the repository's existing [Unlicense](LICENSE). The deployable JAR also contains relocated
third-party libraries under their own licenses; see `THIRD_PARTY_NOTICES.txt` inside the artifact.
