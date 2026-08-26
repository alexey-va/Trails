# AGENTS.md — Trails

Kotlin/Paper rewrite of the owner-maintained Trails plugin.

## Contract

- Keep Bukkit identity `Trails` compatible with 1.9. Block persistence starts fresh with the chunk-PDC v1 schema;
  do not add a reader or migration for the removed `trails:w` / `trails:n` format.
- Target Paper/Purpur 1.21.11 and Java 25 bytecode.
- Keep the public repository independently buildable. Shared `arc-core` runtime and test artifacts must come from
  versioned, publicly readable Reposilite releases and require no credentials for consumers.
- Keep domain decisions independent of Bukkit. Bukkit listeners adapt events into the domain engine.
- Preserve `config.yml`, `players.yml`, and locale-key migration compatibility.
- Do not restore the legacy road template/editor subsystem. Roads 2.2 is a separate bounded preview/commit workflow:
  client-only preview, safe-solid or allowlisted surfaces, weighted palettes, direction-aware height transitions,
  bounded periodic forms, exact preflight, rollback, optional duplication-safe survival compensation, and
  conflict-safe persisted undo.

## Tests

- Kotlin tests use Kotest and MockK; platform lifecycle tests use the shared `arc-core-paper-testing` MockBukkit
  runtime so global server ownership and cleanup stay consistent.
- Plugin lifecycle, task ownership, and health diagnostics use the relocated `arc-core-paper` runtime; do not add a
  second local lifecycle supervisor.
- No ignored or disabled tests.
- Cover parser validation, progression, decay, speed restoration, protection events, chunk persistence, road
  geometry/history/commit/undo, weighted palettes, periodic forms, compensation, commands, localization parity,
  reload, lifecycle cleanup, and artifact identity.

## Build

```bash
./gradlew clean check shadowJar
```

The build is standalone and must not require private repositories or credentials.
