# AGENTS.md — Trails

Kotlin/Paper rewrite of the owner-maintained Trails plugin.

## Contract

- Keep Bukkit identity `Trails` and legacy data keys compatible with 1.9.
- Target Paper/Purpur 1.21.11 and Java 21 bytecode. Builds and tests may also run on newer JDKs.
- Keep the public repository independently buildable. The private `arc-core` was evaluated and rejected as a
  dependency despite its small runtime footprint because public CI and contributors cannot resolve it.
- Keep domain decisions independent of Bukkit. Bukkit listeners adapt events into the domain engine.
- Preserve `config.yml`, `players.yml`, locale keys, `trails:w`, and `trails:n` migration compatibility.
- The experimental road editor/template subsystem is excluded from 2.0 and must not be reintroduced incidentally.

## Tests

- Kotlin tests use Kotest and MockK; platform lifecycle tests may use MockBukkit.
- No ignored or disabled tests.
- Cover parser validation, progression, decay, speed restoration, protection composition, persistence migration,
  commands, localization parity, reload, lifecycle cleanup, and artifact identity.

## Build

```bash
./gradlew clean check shadowJar
```

The build is standalone and must not require private repositories or credentials.
