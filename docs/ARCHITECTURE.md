# Current Architecture

This document describes the implementation in the current working tree. It is descriptive, not a recommendation. Engineering concerns and future directions are kept in `CODEBASE_AUDIT.md`.

## High-level architecture

The plugin is a single Paper plugin with one configured world per arena and one lazily created in-memory `GameSession` per arena.

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

Most collaborators are constructed in `PillarsPlugin.onEnable()` and passed explicitly. There is no global service registry. Each `GameSession` constructs narrow per-session boundaries from those shared managers. Lucky Block effect state is plugin-wide but explicitly owned by `LuckyBlockEffectService` and keyed by sessions, entity UUIDs, and block locations.

## Bootstrap and lifecycle

`PillarsPlugin` saves default resources, constructs managers, optionally registers `ChroniclePlaceholderExpansion`, registers five listener instances, and binds the `/pillars` executor/tab completer. `HudManager` is told to use an external scoreboard when TAB is enabled.

Registered listeners are:

- `GameSessionPlayerListener`
- `GuiListener`
- `LuckyBlockOutcomeManager`
- `LuckyBlockListener`
- `LobbyListener`

There is no `onDisable()` implementation in `PillarsPlugin`. Paper cancels plugin-owned scheduler tasks automatically, and `LuckyBlockOutcomeManager` listens for `PluginDisableEvent`, but sessions, players, borders, event modifiers, and in-flight arena reset work do not have a central shutdown path.

## Component responsibilities

### `PillarsPlugin`

- Owns application construction and listener/command registration.
- Detects PlaceholderAPI and TAB.
- Does not retain the manager graph in fields or coordinate shutdown.

### `GameSessionManager`

- Owns `Map<String, GameSession>` keyed by arena world name.
- Creates sessions lazily, routes join/leave/force-start/event/admin-reset requests, finds a player's session by scanning sessions, and chooses quick-join targets. Join/spectate transfers validate the destination before removing source membership.
- Updates the global automatic-event setting and existing sessions.
- A session lookup treats active players, eliminated spectators, and admin spectators as members.

### `GameSession`

The main lifecycle and gameplay coordinator. It owns:

- lifecycle state and reset/force-start flags;
- active, eliminated-spectator, and admin-spectator UUID sets;
- occupied spawn locations, pregame freeze locations, damage-credit records, and registered Lucky Block locations;
- countdown, item distribution, ending, reset, and per-player lobby tasks;
- match snapshots of game mode and item interval;
- one per-session `GameEventManager` and `SessionWorldBorderController`.

Important operations include joining/removing players, starting/canceling the countdown, starting/ending/resetting a match, eliminating a player, starting/stopping the border controller, manual reset, event control, and Lucky Block registration.

The coordinator decides transition order but delegates implementation details:

- `SessionPlayerService` performs player normalization, inventory/lobby/spectator operations, teleportation, spawn-pillar creation/cleanup, and reset evacuation.
- `SessionPresentationService` is the session-facing boundary for HUD, titles, broadcasts, and sounds.
- `SessionStatisticsService` is the session-facing persistence boundary for reads and match/kill/win updates.
- `SessionWorldBorderController` owns border mutation and timing.
- `GameEventManager` owns optional event scheduling and event lifecycle.

This keeps lifecycle policy in `GameSession` while preventing it from directly implementing presentation, persistent-statistics access, or player/world mutations.

`SessionWorldBorderController` owns border geometry calculation, Bukkit `WorldBorder` mutation, per-tick shrink interpolation, shrink timing exposed to the HUD, the delayed Last Breath handoff, and restoration to the plugin's generic post-match border values. `GameSession` controls only when that subsystem starts and stops.

### Arena configuration, floors, and worlds

`ArenaManager` owns the arena registry, loads and validates arena configuration, and persists administrator changes. Rebuild-required floor and symmetrized-spawn edits are held in immutable per-arena drafts; the live `Arena` and YAML are updated only after successful replacement-world activation. Failed rebuilds restore the prior live settings and retain the draft for retry. `ArenaManager` delegates Bukkit world and directory work to `ArenaWorldService` and floor rules/block generation to `ArenaFloorService`.

`ArenaWorldService` creates missing arena worlds from `arena_template` and owns the rebuild transaction. Reset validates that the target is a direct child of the world container and is not `arena_template`, rejects occupied worlds, requires unload success, stages a template copy, retains the old arena as a backup, activates the replacement, and recreates the Bukkit world. The backup is deleted only after world/floor initialization succeeds, or restored after failure.

`Arena` is mutable configuration/domain data: key, world/display name, spawn list/capacity, minimum players, join availability, item cooldown, border duration, game mode, delivery mode, and floor settings.

Floor generation supports square, square-ring, and per-spawn islands. Liquid floors receive glass support/borders. Arena directories have `uid.dat` and `session.lock` removed after template copy.

### Items and Lucky Blocks

`ItemManager` owns rarity chances, item-pool YAML, weighted selection, per-player anti-repeat history, persistent-data tags for delivered items/Lucky Blocks, and inventory delivery. In hotbar mode it removes previously tagged delivered items before refilling empty hotbar slots.

`LuckyBlockListener` registers tagged sponge placement, protects registered blocks from pistons/explosions, suppresses their normal drops/experience, and triggers an outcome after an active player completes vanilla block breaking. Mining speed and tool behavior are therefore supplied by Minecraft rather than a plugin task. `LuckyBlockOutcomeManager` is the registered Bukkit adapter. It delegates weighted category/anti-repeat selection to `LuckyBlockOutcomeSelector` and delegates outcome execution plus temporary fluid/block/entity ownership and cleanup to `LuckyBlockEffectService`.

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
- GUI classes implement player arena selection and admin editing of arenas, floors, item rarity/pools, Lucky Block chances, and automatic events.

### Supporting managers

- `PlayerManager`: non-destructive lobby entry, per-match player-state snapshots, bounded match normalization, restoration, and lobby/waiting action items.
- `HudManager`: presentation-only façade for messages, titles, action bars, broadcasts, and scoreboard delegation; it owns no gameplay lifecycle or persistence decisions.
- `SessionPresentationService`: session-specific presentation and sound adapter used by `GameSession`.
- `SessionPlayerService`: session-specific player, teleport, inventory, spawn-pillar, and evacuation mutation boundary.
- `SessionStatisticsService`: session-specific boundary over persistent statistics operations.
- `PlayerScoreboardService`: built-in scoreboard creation, per-player scoreboard/team state, updates, and reset. It becomes a no-op renderer when TAB is present.
- `TranslationManager`: Russian-only YAML loading, bundled Russian fallback merging, placeholders, plural forms, and missing-key warnings. Language selection is not configurable.
- `StatsManager`: in-memory UUID statistics and whole-file Gson JSON load/save.
- `SpawnManager`: random unoccupied configured spawn allocation.
- `TeleportManager`: named-world lobby and arena teleports.
- `SoundManager`: state/event sound cues.
- `ChroniclePlaceholderExpansion`: `%chronicle_*%` player stats and arena/session values.

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

Players may join if the arena is open and below spawn capacity. A spawn is occupied, a bedrock or yellow-glazed-terracotta pillar is created, the player is teleported/frozen, and waiting action items are installed. Reaching the minimum calls `startCountdown(false)`.

### STARTING

A synchronous one-second task announces the countdown. If the player count falls below the minimum, a non-forced countdown is canceled. New players may still join. Force-start bypasses the minimum check. At zero, the task starts the game.

### RUNNING

The session snapshots the arena game mode and item interval, re-prepares occupied pillars, increments games played, normalizes players for survival, starts item/Lucky Block distribution, begins border shrink, and schedules random events if enabled.

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

## State ownership

| State | Scope | Owner | Lifetime/persistence |
|---|---|---|---|
| Live arena configuration | Per arena | `Arena` + `ArenaManager` | YAML; floor/spawns change only after successful rebuild |
| Pending floor/spawn draft | Per arena | `ArenaManager` | Temporary until successful rebuild or plugin restart |
| Session lifecycle/roster/tasks | Per arena | `GameSession` | Temporary |
| Session player/world mutations | Per arena | `SessionPlayerService` | Stateless boundary |
| Session presentation | Per arena | `SessionPresentationService` | Stateless boundary |
| Session statistics operations | Per arena | `SessionStatisticsService` | Stateless boundary over `StatsManager` |
| Pre-match player snapshots | Per player UUID | `PlayerManager` | Temporary; removed on lobby return or disconnect |
| Border geometry/timing/tasks | Per session | `SessionWorldBorderController` | Temporary; stopped at match end/reset |
| Event state/tasks/history | Per match/session | `GameEventManager` and event | Temporary |
| Lucky Block locations | Per session | `GameSession` | Temporary |
| Lucky outcome anti-repeat history | Global maps keyed by session/player | `LuckyBlockOutcomeSelector` | Temporary |
| Lucky entities/fluids/temporary blocks | Global maps keyed by session/location/UUID | `LuckyBlockEffectService` | Temporary |
| Item pools/rarity | Plugin-wide | `ItemManager` | YAML |
| Item anti-repeat history | Per player UUID | `ItemManager` | Memory only |
| Kills/wins/games | Per player UUID | `StatsManager` | `stats.json` |
| Translations/missing-key set | Plugin-wide | `TranslationManager` | YAML + memory |
| GUI viewer/menu state | Per open menu | Inventory holder | Temporary |

## Threading and scheduling

Most code and all Bukkit event handling run synchronously. Session countdowns, distribution, event mechanics, delayed Lucky Block outcome dispatch, and player return countdowns use the Bukkit scheduler on the main thread. Lucky Block mining itself is vanilla. `SessionWorldBorderController` owns the main-thread per-tick border interpolation task and delayed Last Breath start.

Arena reset checks occupants and unloads synchronously. Template staging, directory swapping/rollback, and backup cleanup run asynchronously; world creation and floor generation return to the main thread. Startup arena template copies and floor generation are synchronous. Stats and YAML saves are synchronous, including calls originating from match start, kills/wins, commands, ordinary GUI settings, and successful floor/spawn rebuild commits. Editing a floor/spawn draft itself performs no file I/O.

No `CompletableFuture`, database, or HTTP client is present.

## Persistence

- `config.yml`: global settings and arena definitions. Loaded during manager/session construction. Live-safe admin settings write synchronously; floor/spawn drafts write only after successful rebuild activation.
- `item-pools.yml`: common/rare/legendary material weights. Loaded at startup, migrated from legacy config keys if present, and rewritten after admin changes.
- `messages_ru.yml`: copied into the plugin data folder, loaded at startup, and backed by the bundled Russian defaults. English is not loaded or supported.
- `stats.json`: UUID-keyed kills, wins, and games played. Loaded synchronously at enable and rewritten in full for every increment.
- Arena worlds: transient copies of `arena_template`; successful resets discard world changes by replacing the directory.

## Configuration behavior

Global configuration covers lifecycle timing, border sizing, pillar height, lobby world, rarity/anti-repeat, Lucky Block probabilities/effect lifetimes, and game-event settings. Each arena configures its Russian display name, world name, spawns, join availability, minimum players, item cooldown, border duration, game mode, delivery mode, and floor.

Configuration lifetime is mixed: some values are captured when managers/sessions are constructed, game mode and interval are snapshotted at match start, and some mutable arena values (notably delivery mode) are read during a running match. There is no general reload command.

## External integrations

- Paper API, including `EntityPushedByEntityAttackEvent` and transient attributes.
- PlaceholderAPI soft dependency. Identifier: `chronicle`; placeholders include kills, wins, games, win-rate forms, and arena/session status values.
- TAB soft dependency. `deployment/TAB/config.yml` demonstrates consuming the placeholders. Presence of TAB disables the internal scoreboard.
- Gson is a direct shaded dependency for `stats.json`.

## Unclear / Requires Confirmation

- Whether PlaceholderAPI/TAB requests are guaranteed to execute on the primary server thread; the expansion reads mutable session maps without synchronization.
- Whether switching item-delivery mode during a running match is intended to take effect immediately; game mode is explicitly snapshotted but delivery mode is read live.
