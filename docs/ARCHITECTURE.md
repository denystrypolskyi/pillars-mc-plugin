# Current Architecture

This document describes the implementation in the current working tree. It is descriptive, not a recommendation. Engineering concerns and future directions are kept in `CODEBASE_AUDIT.md`.

## High-level architecture

The plugin is a single Paper plugin with one configured world and at most one in-memory `GameSession` per ready arena. Startup arena preparation is asynchronous, so a session may be created on first access after its arena becomes available.

```text
/pillars command       Bukkit/Paper events        Inventory GUI clicks
        \                    |                         /
         +----------- handlers/listeners -------------+
                              |
                       GameSessionManager
                              |
             +----------------+----------------+
             |                                 |
     per-arena GameSession                 global managers
             |                       Arena / Item / Stats / HUD
   session-specific boundaries              / Lucky adapter
 player / presentation / statistics               |
 events / border / tasks                 selector + effect service
             |                                 |
       Paper world/player API       YAML/JSON + Paper API
```

Most collaborators are constructed in `PillarsPlugin.onEnable()` and passed explicitly. There is no global service registry. `StartupSettings` validates and captures startup-scoped values once before managers and sessions are constructed. Each `GameSession` constructs narrow per-session boundaries from those shared managers. Lucky Block effect state is plugin-wide but explicitly owned by `LuckyBlockEffectService` and keyed by sessions, entity UUIDs, block locations, and session-owned task handles.

## Bootstrap and lifecycle

`PillarsPlugin` saves default resources, constructs managers, optionally registers `ChroniclePlaceholderExpansion`, registers five listener instances, and binds the `/pillars` executor/tab completer. `HudManager` is told to use an external scoreboard when TAB is enabled.

Registered listeners are:

- `GameSessionPlayerListener`
- `GuiListener`
- `LuckyBlockOutcomeManager`
- `LuckyBlockListener`
- `LobbyListener`

`PillarsPlugin.onDisable()` coordinates shutdown through retained manager fields. It first stops sessions and restores their temporary player/world state, then clears Lucky Block effects, restores any remaining player snapshots, drains/rolls back arena rebuild work, and finally flushes statistics persistence.

## Component responsibilities

### `PillarsPlugin`

- Owns application construction and listener/command registration.
- Detects PlaceholderAPI and TAB.
- Retains the shutdown-critical manager graph and closes it in dependency order from `onDisable()`.

### `GameSessionManager`

- Owns `Map<String, GameSession>` keyed by arena world name.
- Creates sessions for arenas already ready when it is constructed and uses `computeIfAbsent` for arenas that finish asynchronous startup preparation later. It routes join/leave/force-start/event/admin-reset requests, finds a player's session by scanning sessions, and chooses quick-join targets. Join/spectate transfers validate the destination before removing source membership.
- Updates the global automatic-event setting and existing sessions.
- A session lookup treats active players, eliminated spectators, and admin spectators as members.

### `GameSession`

The main lifecycle and gameplay coordinator. It owns:

- lifecycle state and reset/force-start flags;
- active, eliminated-spectator, and admin-spectator UUID sets;
- occupied spawn locations, pregame freeze locations, damage-credit records, and registered Lucky Block locations;
- countdown, item distribution, ending, reset, and per-player lobby tasks;
- one `ArenaMatchSettings` snapshot containing game mode, delivery mode, item interval, and border duration;
- one per-session `GameEventManager` and `SessionWorldBorderController`.

Important operations include joining/removing players, starting/canceling the countdown, starting/ending/resetting a match, eliminating a player, starting/stopping the border controller, manual reset, event control, and Lucky Block registration.

The coordinator decides transition order but delegates implementation details:

- `SessionPlayerService` performs player normalization, inventory/lobby/spectator operations, teleportation, spawn-pillar creation/cleanup, and reset evacuation.
- `SessionPresentationService` is the session-facing boundary for HUD, titles, broadcasts, and sounds.
- `SessionStatisticsService` is the session-facing persistence boundary for reads and match/kill/win updates.
- `SessionWorldBorderController` owns border mutation and timing.
- `GameEventManager` owns optional event scheduling and event lifecycle.

This keeps lifecycle policy in `GameSession` while preventing it from directly implementing presentation, persistent-statistics access, or player/world mutations.

`SessionWorldBorderController` owns border geometry calculation, Bukkit `WorldBorder` mutation, per-tick shrink interpolation, shrink timing exposed to the HUD, the delayed Last Breath handoff, and an idempotent snapshot/restore of the world's pre-match border properties. `GameSession` controls only when that subsystem starts and stops.

### Arena configuration, floors, and worlds

`ArenaManager` owns the arena registry, loads and validates arena configuration, and persists administrator changes. During startup it does not register an arena until its directory, Bukkit world, and generated floor are ready. Rebuild-required floor and symmetrized-spawn edits are held in immutable per-arena drafts; the live `Arena` and YAML are updated only after successful replacement-world activation. Failed rebuilds restore the prior live settings and retain the draft for retry. `ArenaManager` delegates Bukkit world and directory work to `ArenaWorldService` and floor rules/block generation to `ArenaFloorService`.

`ArenaWorldService` creates missing arena worlds from `arena_template` and owns the rebuild transaction. Startup template copies run serially on its file executor; completed directories enter a main-thread queue that activates at most one Bukkit world per tick. Reset validates that the target is a direct child of the world container and is not `arena_template`, rejects occupied worlds, requires unload success, stages a template copy, retains the old arena as a backup, activates the replacement, and recreates the Bukkit world. The backup is deleted only after world/floor initialization succeeds, or restored after failure.

`ArenaFloorService` computes floor columns, queues generation jobs, and applies at most `settings.floorColumnsPerTick` columns across all jobs per server tick. Startup arenas remain absent from the registry, and reset sessions remain in `RESETTING`, until their generation callback succeeds.

`Arena` is mutable configuration/domain data: key, world/display name, spawn list/capacity, minimum players, join availability, item cooldown, border duration, game mode, delivery mode, and floor settings.

Configuration lifetime has explicit code boundaries:

- `StartupSettings` is loaded once in `PillarsPlugin.onEnable()` and shared by every session regardless of when asynchronous arena preparation completes. Lifecycle timing, lobby/pillar/border geometry, floor budget, and event mechanics require restart.
- `ArenaMatchSettings.capture(...)` runs immediately before `RUNNING`. Game mode, delivery mode, item interval, and border duration cannot drift during that match; admin edits apply to the next match.
- `LuckyBlockSettings` is a live façade over the in-memory Bukkit configuration. Admin changes affect the next selected or executed Lucky Block outcome.
- Arena join availability and minimum player count are live during pregame. Floor/spawn drafts activate only after a successful rebuild.

Floor generation supports square, square-ring, and per-spawn islands. Liquid floors receive glass support/borders. Arena directories have `uid.dat` and `session.lock` removed after template copy.

### Items and Lucky Blocks

`ItemManager` owns rarity chances, item-pool YAML, weighted selection, per-player anti-repeat history, persistent-data tags for delivered items/Lucky Blocks, and inventory delivery. In hotbar mode it removes previously tagged delivered items before refilling empty hotbar slots.

`LuckyBlockListener` registers tagged sponge placement, protects registered blocks from pistons/explosions, suppresses their normal drops/experience, and triggers an outcome after an active player completes vanilla block breaking. Mining speed and tool behavior are therefore supplied by Minecraft rather than a plugin task. `LuckyBlockOutcomeManager` is the registered Bukkit adapter and exposes the effect cleanup boundary used by sessions. It delegates weighted category/anti-repeat selection to `LuckyBlockOutcomeSelector` and delegates outcome execution plus temporary fluid/block/entity/task ownership and cleanup to `LuckyBlockEffectService`. The outcome system and admin menu receive the same `LuckyBlockSettings` instance; the GUI neither discovers the plugin statically nor creates a second settings wrapper.

### Game events

Each session has a `GameEventManager`. It schedules random events, keeps anti-repeat history, exposes status, supports administrator-forced events, and starts Last Breath when the border finishes shrinking.

Implemented events:

- `SuperSmashBrosEvent`: attack knockback multiplier.
- `CosmicDriftEvent`: temporary gravity, jump-strength, and fall-damage attribute modifiers, tracked by affected UUID and removed immediately on elimination/departure.
- `MeteorShowerEvent`: warned display meteors with area damage/knockback; manual selection only.
- `EarthquakeEvent`: temporarily removes/restores warned floor blocks.
- `HuntBeginsEvent`: glowing target and item reward for a qualifying kill/survival.
- `HotPotatoEvent`: holder marker, direct-melee passing, timer, damage, and knockback.
- `LastBreathEvent`: periodic Wither after border completion; terminates normal event scheduling.

Event tasks are generally tracked by the event classes and stopped through `GameEventManager.stopAll()`.

### Player-facing adapters

- `PillarsCommand` routes join, quick-join, leave, force-start, event status/control, menus, and item-pool administration.
- `GameSessionPlayerListener` handles quit, completed cross-world departure, lethal damage, void/floor falls, PvP credit, direct-hit event hooks, movement freeze, and Paper knockback events.
- `LobbyListener` sends joining players to the lobby without destructive normalization, handles action items, session death/respawn, and basic inventory/drop protection.
- `GuiListener` identifies plugin menus from the top `InventoryView`, cancels cross-inventory transfer clicks, and rejects drags that touch the plugin-owned top inventory before dispatching valid top-menu clicks.
- GUI classes implement player arena selection and admin editing of arenas, floors, item rarity/pools, Lucky Block chances, and automatic events. `ArenaMenuItemFactory.arenaDetailsLore(...)` is the single source for arena characteristics shown in both the player selection list and the administrative arena list; only their final action hint differs.

### Supporting managers

- `PlayerManager`: non-destructive lobby entry, per-match player-state snapshots, bounded match normalization, restoration, and lobby/waiting action items.
- `HudManager`: presentation-only façade for messages, titles, action bars, broadcasts, and scoreboard delegation; it owns no gameplay lifecycle or persistence decisions.
- `SessionPresentationService`: session-specific presentation and sound adapter used by `GameSession`.
- `SessionPlayerService`: session-specific player, teleport, inventory, spawn-pillar, and evacuation mutation boundary.
- `SessionStatisticsService`: session-specific boundary over persistent statistics operations.
- `PlayerScoreboardService`: built-in scoreboard creation, per-player scoreboard/team state, updates, and reset. It becomes a no-op renderer when TAB is present.
- `TranslationManager`: Russian-only YAML loading, bundled Russian fallback merging, placeholders, plural forms, and missing-key warnings. Language selection is not configurable.
- `StartupSettings`: immutable validated lifecycle and event settings loaded once during enable.
- `ArenaMatchSettings`: immutable per-match copy of arena mode, delivery, interval, and border duration.
- `LuckyBlockSettings`: live validated Lucky Block configuration used by runtime outcomes and the admin menu.
- `AsyncYamlWriter`: coalesces latest YAML snapshots per file, writes them through temporary-file atomic replacement on one daemon file thread, and completes each request with the durable-write result.
- `StatsManager`: locked in-memory UUID statistics, synchronous Gson JSON loading, and coalesced single-writer asynchronous atomic saves.
- `SpawnManager`: random unoccupied configured spawn allocation.
- `TeleportManager`: named-world lobby and arena teleports.
- `SoundManager`: state/event sound cues.
- `ChroniclePlaceholderExpansion`: `%chronicle_*%` player stats and arena/session values. It reads locked statistics and immutable UUID-keyed session views rather than live Bukkit/session state.

## Game lifecycle

Actual assigned state transitions are:

```text
WAITING --minimum players/force--> STARTING --countdown--> RUNNING
   ^                                 |                       |
   |                                 +--too few players------+ (back to WAITING)
   |                                                         |
   +<--arena rebuild callback-- RESETTING <--delay-- ENDING <-+ one/no active players
```

The pregame countdown runs entirely during `STARTING`; there is no separate countdown lifecycle state.

### WAITING

Players may join if the arena is open and below spawn capacity. A spawn is occupied, a bedrock or sponge pillar is created after snapshotting the exact replaced block data, the player is teleported/frozen, and waiting action items are installed. Reaching the minimum calls `startCountdown(false)`.

### STARTING

A synchronous one-second task announces the countdown. If the player count falls below the minimum, a non-forced countdown is canceled. New players may still join. Force-start bypasses the minimum check. At zero, the task starts the game.

### RUNNING

The session captures one `ArenaMatchSettings` value containing game mode, item-delivery mode, item interval, and border duration. It then re-prepares occupied pillars, increments games played, normalizes players for survival, starts item/Lucky Block distribution, begins border shrink, and schedules random events if enabled. Later admin changes to those four arena values apply only to the following match.

### ENDING

Winner evaluation is guarded by state. Distribution, border, and events stop. All remaining active players become spectators. A sole online remaining player receives a win; otherwise the match is announced without a winner. After the spectator delay, online participants receive individual lobby countdowns. The reset is scheduled independently after the configured reset delay.

### RESETTING

Before session collections/tasks are cleared, both automatic and manual reset restore administrative spectators and return every tracked online participant plus every player physically present in the arena world to the lobby. The arena is then rebuilt from `arena_template`. `ArenaRebuildResult` distinguishes success from template/path, occupancy, unload, filesystem, and world-load failures. Only success returns the session to `WAITING`; failure leaves it unavailable in `RESETTING`, clears the in-progress flag, and permits an administrator to retry.

## Player lifecycle

### Server join

`LobbyListener` teleports the player to the configured lobby world, installs lobby action items only in free inventory slots, and applies the lobby scoreboard. Existing inventory and player attributes are not cleared. There is no reconnect-to-match restoration.

### Session join and countdown

Joining or spectating another arena first performs a side-effect-free destination admission check. A rejected full, closed, unavailable, or wrong-state target leaves current membership unchanged and sends the existing rejection feedback. After successful preflight, the old membership is removed and the target operation runs synchronously. A successful join snapshots the mutable pre-match player state, normalizes the player for the match, assigns an available spawn/pillar, teleports them, freezes movement, and gives waiting items. Join is allowed in `WAITING` and `STARTING` only.

### Death and elimination

Uncancelled lethal damage is evaluated at high event priority and intercepted before Bukkit death where possible; the event is canceled and `GameSession.playerDeath()` converts the player to spectator. Damage canceled by an earlier protection/combat handler is ignored. Falling below the computed elimination height uses the same path. If an actual Bukkit death occurs for a session member, drops/XP are suppressed and the player is converted or returned on respawn.

The plugin records recent PvP damage for ten seconds and resolves direct/projectile killers. Kill credit is accepted only when the candidate is still an active participant and is not the victim. Eliminated players receive a leave item and can spectate until return/reset.

### Leave, quit, and external movement

`/p leave` or the leave item removes membership, restores the pre-match snapshot, and returns the player to the lobby. Quit restores the snapshot and its original location without applying lobby inventory items. Leaving during a running game triggers event notification and winner evaluation. A completed move to another world is rechecked on the next server tick; if the player is still registered and remains outside the arena world, it is routed through the normal leave path and returned to the lobby. Same-world teleports are not treated as departures because the plugin itself uses them for spawn, freeze, elimination, and spectator behavior.

### Admin spectating

An administrator may spectate a running session without joining the active roster. The prior location/game mode are restored on normal exit, match end, and disconnect.

### Reset

Automatic and manual reset use the same evacuation boundary: administrative spectators are restored, tracked online participants and all current arena-world occupants are returned through normal lobby cleanup, then session state is cleared and rebuilding begins. The world service independently refuses to unload an occupied world, so a missing lobby or unsuccessful teleport cannot proceed into directory replacement.

### Plugin shutdown

Each session cancels all owned lifecycle, distribution, event, border, and Lucky Block outcome tasks; removes that session's tracked entities, fluids, and temporary blocks; restores administrative spectators, active/eliminated participant snapshots, and exact pregame pillar blocks; then clears temporary match state. Global Lucky Block shutdown provides an idempotent fallback for any remaining effect state. Arena shutdown cancels queued floor/world activation, drains filesystem operations, and rolls back staged swaps that have not been activated. YAML and statistics writers then flush their latest pending snapshots.

## State ownership

| State | Scope | Owner | Lifetime/persistence |
|---|---|---|---|
| Live arena configuration | Per arena | `Arena` + `ArenaManager` | YAML; floor/spawns change only after successful rebuild |
| Pending floor/spawn draft | Per arena | `ArenaManager` | Temporary until successful rebuild or plugin restart |
| Startup arena preparation | Per configured arena | `ArenaWorldService` + `ArenaFloorService` | Arena unavailable until directory, world, and floor complete |
| Startup configuration snapshot | Plugin-wide | `StartupSettings` | Immutable from enable until disable |
| Active arena match settings | Per running match | `GameSession` / `ArenaMatchSettings` | Captured at `RUNNING`, cleared on reset/shutdown |
| Lucky Block settings | Plugin-wide | `LuckyBlockSettings` backed by Bukkit config | Live; the next outcome observes admin updates |
| Pending YAML snapshots | Per target file | `AsyncYamlWriter` | Coalesced until atomic background write/shutdown flush |
| Session lifecycle/roster/tasks | Per arena | `GameSession` | Temporary |
| Session player/world mutations | Per arena | `SessionPlayerService` | Stateless boundary |
| Session presentation | Per arena | `SessionPresentationService` | Stateless boundary |
| Session statistics operations | Per arena | `SessionStatisticsService` | Stateless boundary over `StatsManager` |
| Pre-match player snapshots | Per player UUID | `PlayerManager` | Temporary; removed on lobby return or disconnect |
| Original pregame pillar blocks | Per participant UUID/session | `GameSession` + `SpawnManager` | Temporary; restored on pregame leave/shutdown or discarded when a full arena reset owns cleanup |
| Border geometry/timing/tasks and original border snapshot | Per session | `SessionWorldBorderController` | Temporary; stopped and restored at match end/reset |
| Event state/tasks/history | Per match/session | `GameEventManager` and event | Temporary |
| Lucky Block locations | Per session | `GameSession` | Temporary |
| Lucky outcome anti-repeat history | Global maps keyed by session/player | `LuckyBlockOutcomeSelector` | Temporary |
| Lucky entities/fluids/temporary blocks/delayed tasks | Global maps keyed by session/location/UUID | `LuckyBlockEffectService` | Temporary; removed immediately by per-session cleanup or global shutdown fallback |
| Item pools/rarity | Plugin-wide | `ItemManager` | YAML |
| Item anti-repeat history | Per player UUID | `ItemManager` | Memory only |
| Kills/wins/games | Per player UUID | `StatsManager` | `stats.json` |
| Translations/missing-key set | Plugin-wide | `TranslationManager` | YAML + memory |
| GUI viewer/menu/page state | Per open menu | Inventory holder | Temporary; arena-list page is retained through nested administrative navigation |

## Threading and scheduling

Most code and all Bukkit event handling run synchronously. Session countdowns, distribution, event mechanics, delayed Lucky Block outcome cleanup, and player return countdowns use the Bukkit scheduler on the main thread. Every Lucky Block delayed cleanup task is registered to its `GameSession`, unregistered after execution, and canceled by direct session cleanup before reset; Lucky Block mining itself is vanilla. `SessionWorldBorderController` owns the main-thread per-tick border interpolation task and delayed Last Breath start.

`GameSession` publishes immutable placeholder views whenever roster or lifecycle presentation state changes. `GameSessionManager` publishes its session list as an immutable snapshot. PlaceholderAPI/TAB callbacks may therefore run asynchronously without touching Bukkit `Player`, mutable session collections, arena spawn lists, or translation YAML; statistics reads remain protected by `StatsManager`'s lock.

Arena reset checks occupants and unloads synchronously. Startup copies, template staging, directory swapping/rollback, and backup cleanup run on `ArenaWorldService`'s dedicated single-thread executor. Bukkit world creation remains main-thread-only and is limited to one queued startup activation per tick. Floor block mutations also remain on the main thread but share the configured `floorColumnsPerTick` budget across all startup/reset jobs. An arena is unavailable until those steps complete. Shutdown cancels activation/floor queues, waits up to 30 seconds for filesystem work, and rolls back pending reset activations.

Statistics mutations occur under a lock and schedule a coalesced deep snapshot on `StatsManager`'s dedicated single-thread writer. `AsyncYamlWriter` captures YAML strings on the main thread, coalesces repeated writes per target, and performs temporary-file atomic replacement in its own single-thread executor. Statistics/YAML loading and small bundled-resource creation remain synchronous during enable; runtime admin filesystem writes do not block the server thread. Editing a floor/spawn draft itself performs no file I/O.

`CompletableFuture<Boolean>` is used only to return YAML persistence results from the file-writer thread. Bukkit-facing completion handlers are marshalled back to the main server thread. No database or HTTP client is present.

## Persistence

- `config.yml`: global settings and arena definitions. Startup settings are validated once in `PillarsPlugin.onEnable()`. Arena admin settings update the live `Arena`, with match-bound values taking effect on the next match. Lucky Block settings and automatic-event enablement update live. Durable runtime writes are coalesced and atomically replaced by `AsyncYamlWriter`; floor/spawn drafts are queued only after successful rebuild activation.
- `item-pools.yml`: common/rare/legendary material weights. Loaded once at startup and retained in memory. Migrations/admin edits update runtime maps immediately and queue a coalesced atomic background write. Commands report success only after durable replacement; failures are logged and tell the administrator that the runtime-only change will be lost on restart.
- `messages_ru.yml`: copied into the plugin data folder, loaded at startup, and backed by the bundled Russian defaults. English is not loaded or supported.
- `stats.json`: UUID-keyed kills, wins, and games played. Loaded synchronously at enable. Mutations are coalesced into full immutable snapshots written asynchronously through `stats.json.tmp`; atomic replacement is used when supported, with replacement fallback, and shutdown flushes pending state.
- Arena worlds: transient copies of `arena_template`; successful resets discard world changes by replacing the directory.

## Configuration behavior

Global configuration covers lifecycle timing, border sizing, pillar height, floor columns per tick, lobby world, rarity/anti-repeat, Lucky Block probabilities/effect lifetimes, and game-event settings. Each arena configures its Russian display name, world name, spawns, join availability, minimum players, item cooldown, border duration, game mode, delivery mode, and floor.

Configuration lifetime is explicit:

- **Startup:** lifecycle delays, lobby world, pillar height, border geometry, and detailed event mechanics are captured by `StartupSettings` and require restart.
- **Pregame live:** join availability and minimum player count affect subsequent admission/countdown checks.
- **Next match:** game mode, delivery mode, item interval, and border duration are copied into `ArenaMatchSettings` at the `RUNNING` transition.
- **Live next operation:** Lucky Block settings, automatic-event enablement, rarity percentages, and item pools affect subsequent outcomes/distributions through their owning managers.
- **Successful rebuild:** floor and spawn drafts become live and persistent only after replacement-world activation.

There is no general reload command. Editing YAML on disk alone never changes the in-memory configuration; startup-scoped changes require restart.

## External integrations

- Paper API, including `EntityPushedByEntityAttackEvent` and transient attributes.
- PlaceholderAPI soft dependency. Identifier: `chronicle`; placeholders include kills, wins, games, win-rate forms, and arena/session status values. Session values come from immutable snapshots safe for asynchronous consumers such as TAB.
- TAB soft dependency. `deployment/TAB/config.yml` demonstrates consuming the placeholders. Presence of TAB disables the internal scoreboard.
- Gson is a direct shaded dependency for `stats.json`.

## Unclear / Requires Confirmation

No unresolved integration-threading assumption remains in the current implementation; placeholder callbacks do not require the primary server thread.
