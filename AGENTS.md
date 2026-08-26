# AGENTS.md — Trails

Kotlin/Paper rewrite of the owner-maintained Trails plugin.

## Contract

- Keep Bukkit identity `Trails` and legacy data keys compatible with 1.9.
- Target Paper/Purpur 1.21.11 and Java 21 bytecode. Builds and tests may also run on newer JDKs.
- Keep the public repository independently buildable. Shared `arc-core` test utilities must come from versioned,
  publicly readable Reposilite releases and require no credentials for consumers.
- Keep domain decisions independent of Bukkit. Bukkit listeners adapt events into the domain engine.
- Preserve `config.yml`, `players.yml`, locale keys, `trails:w`, and `trails:n` migration compatibility.
- Do not restore the legacy road template/editor subsystem. Roads 2.2 is a separate bounded preview/commit workflow:
  client-only preview, safe-solid or allowlisted surfaces, weighted palettes, direction-aware height transitions,
  bounded periodic forms, exact preflight, rollback, optional duplication-safe survival compensation, and
  conflict-safe persisted undo.

## Tests

- Kotlin tests use Kotest and MockK; platform lifecycle tests use the shared `arc-core-paper-testing` MockBukkit
  runtime so global server ownership and cleanup stay consistent.
- No ignored or disabled tests.
- Cover parser validation, progression, decay, speed restoration, protection events, persistence migration, road
  geometry/history/commit/undo, weighted palettes, periodic forms, compensation, commands, localization parity,
  reload, lifecycle cleanup, and artifact identity.

## Build

```bash
./gradlew clean check shadowJar
```

The build is standalone and must not require private repositories or credentials.
