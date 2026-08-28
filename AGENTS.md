# Contributor and Agent Guide

## Project overview

PillarsMC is a multi-arena Paper minigame plugin. Players occupy generated pillars, receive either weighted items or Lucky Blocks, fight through optional timed events while the world border shrinks, and are eliminated until one active player remains.

- Server API: Paper `1.21.8-R0.1-SNAPSHOT`; `plugin.yml` declares `api-version: 1.21`.
- Java: 21.
- Build: Maven; no Maven wrapper is committed.
- Main class: `org.example.pillars.PillarsPlugin`.
- Descriptor: `src/main/resources/plugin.yml`.
- Runtime input: an `arena_template` directory must exist in the server world container. Each configured arena is copied from it into its own world directory.
- Persistence: Bukkit YAML (`config.yml`, Russian messages in `messages_ru.yml`, `item-pools.yml`) and Gson JSON (`stats.json`).
- Language: Russian only. Do not add English strings or an English fallback unless English support is intentionally reintroduced as a separate change.
- Optional integrations: PlaceholderAPI and TAB are soft dependencies. PlaceholderAPI exposes `%chronicle_*%` values; when TAB is installed, the built-in scoreboard is disabled.

### Dependencies

- Paper API is `provided` by the server.
- PlaceholderAPI is `provided` and optional at runtime.
- Gson 2.11.0 is declared directly and shaded for `stats.json` persistence.

### Package structure

- `org.example.pillars`: plugin bootstrap and the per-arena `GameSession` lifecycle coordinator.
- `command`: `/pillars` and `/p` command routing.
- `entities`: mutable arena configuration and player statistics.
- `enums`: lifecycle, arena mode, item delivery, floor shape, elimination, and reset result values.
- `gameevents`: session event orchestration and individual events.
- `gui`: player arena selection and administrative inventory menus.
- `listeners`: player/session, lobby, GUI, and Lucky Block Bukkit event adapters.
- `managers`: arenas/worlds, sessions, players, items, Lucky Block outcomes, HUD, translations, statistics, teleportation, spawns, and sounds.
- `placeholders`: PlaceholderAPI expansion.
- `ui`: shared legacy UI color constants.
- `src/main/resources`: default configuration, Russian messages, item pools, and plugin descriptor.
- `deployment/TAB`: example TAB integration configuration.

The current implementation is described in `docs/ARCHITECTURE.md` and `docs/GAME_DESIGN.md`. Enforced assumptions are in `docs/INVARIANTS.md`; known problems and recommendations are deliberately kept in `docs/CODEBASE_AUDIT.md`.

## Build and test commands

Run from the repository root:

```powershell
mvn compile
mvn test
mvn clean package
```

- `mvn compile` compiles production sources.
- `mvn test` compiles and runs tests. No `src/test` tree currently exists, so Maven reports that there are no tests.
- `mvn clean package` produces `target/pillarsplugin-1.0-SNAPSHOT.jar`.
- Maven Shade may also produce `target/original-pillarsplugin-1.0-SNAPSHOT.jar`; deploy the JAR without the `original-` prefix.

Do not edit `dependency-reduced-pom.xml`; it is generated Shade output. Do not commit `target/`.

## Development rules

### Dependencies and construction

- Declare every directly imported library in `pom.xml`. Keep Paper and optional server plugins as `provided` and do not shade server APIs.
- Continue explicit constructor injection. Do not add mutable global/static collections, static `Player` references, singleton managers, or service locators.
- Keep match-specific state in `GameSession` or a deliberately extracted per-session collaborator. A global manager must not silently own per-match state without explicit cleanup.
- Add abstractions only where they define a useful boundary or support genuinely different behavior.

### Responsibilities and state

- Commands and listeners should validate Bukkit input and delegate. Lifecycle and gameplay decisions belong in sessions/services, not GUI click handlers.
- Keep each manager focused. `GameSession` coordinates state transitions through session-specific player, presentation, statistics, border, and event collaborators; do not move their implementation details back into the coordinator. `LuckyBlockOutcomeManager` is only a Bukkit adapter; keep selection in `LuckyBlockOutcomeSelector` and temporary world-effect ownership in `LuckyBlockEffectService`.
- Use UUID as the canonical identity in session, cache, scheduled-task, and persistence state. Resolve an online `Player` only when invoking Paper APIs.
- Avoid retaining `Player`, `Entity`, `World`, `Chunk`, or `Inventory` beyond a short, explicit lifecycle. If retention is necessary, define ownership and cleanup.
- Preserve `docs/INVARIANTS.md`; update code and documentation together when behavior intentionally changes.

### Bukkit/Paper and scheduling

- Treat world, entity, player, inventory, scoreboard, attributes, and scheduler state as main-thread-only unless Paper explicitly documents otherwise.
- Move large filesystem copies/deletions and other blocking I/O off the main thread. Capture plain immutable input, then return to the main thread before using Bukkit state.
- Store every session/event delayed or repeating task and cancel it idempotently at completion, reset, and disable.
- Add explicit `onDisable` cleanup for player mutations, attributes, displays/entities, temporary blocks/fluids, borders, tasks, and in-flight resets.
- Never delete an arena directory unless world unload succeeded and every player has been moved out. A failed reset must remain unavailable and report failure.

### Player lifecycle

- Define join, leave, quit, reconnect, death, teleport/world-change, elimination, victory, reset, and disable behavior for every player-affecting feature.
- Restore all temporary effects on every exit path, including attributes, potion effects, glow, game mode, flight, inventory policy, scoreboard, velocity/fall state where relevant, and event-specific state.
- Do not assume maximum health is 20 or that other plugins have not changed player attributes.
- Validate a destination session before removing a player from a current session.
- If this plugin is deployed on a shared/non-dedicated server, snapshot and restore pre-game player state. Otherwise document the dedicated-server assumption operationally.

### Persistence, configuration, and errors

- Keep YAML/JSON access behind manager or repository boundaries. Gameplay components should consume validated values.
- Define whether each configuration value is startup-scoped, session-scoped, match-snapshotted, or live. Reload/update all affected instances consistently.
- Use atomic file replacement for durable data where practical. Do not report a save as successful when persistence failed.
- Log exceptions with context and stack traces. Avoid broad catches unless a boundary must contain arbitrary failure.
- Use the plugin logger, Russian player-facing messages in `messages_ru.yml`, and configurable gameplay values instead of standard output or hardcoded user-facing text.

### API and GUI behavior

- Prefer Adventure components over deprecated legacy string APIs when touching chat, titles, menus, lore, scoreboards, or display text.
- GUI listeners must consider the complete `InventoryView`. Cancel shift-click, number-key swaps, double-click/collect, and drag paths into plugin-owned inventories.
- Respect cancellation from other plugins. Do not eliminate a player in response to canceled damage.
- Ensure any optional integration has a functional fallback and does not expose main-thread-only state unsafely.

## Change discipline

- Do not modify or refactor unrelated code while implementing a feature or fix.
- Preserve existing working-tree changes; inspect `git status` and `git diff` before and after work.
- Make the smallest coherent change and preserve documented gameplay unless behavior change is explicit.
- Update `docs/ARCHITECTURE.md` for component/lifecycle/threading/persistence changes.
- Update `docs/GAME_DESIGN.md` for player-visible behavior changes.
- Update `docs/INVARIANTS.md` when a required guarantee changes.
- Update `docs/CODEBASE_AUDIT.md` when a risk is fixed or materially altered.

## Verification

- Do not inspect, add, modify, or run tests unless explicitly requested.
- Do not run Maven test/build verification unless explicitly requested.
- Do not inspect or summarize `git diff` unless explicitly requested.
- Do not perform additional read-only verification passes unless explicitly requested.
