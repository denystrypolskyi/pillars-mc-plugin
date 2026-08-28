# Codebase Audit

This is a fresh review of the current working tree, including uncommitted production changes visible on 2026-08-28. It describes concrete risks; it does not redefine current gameplay. Priorities are:

- **P0:** crash, corruption, severe server issue, or fundamentally broken gameplay.
- **P1:** incorrect gameplay, lifecycle/threading/resource leak, or major state inconsistency.
- **P2:** architectural debt that materially obstructs future development.
- **P3:** limited-impact maintainability/code-quality issue.
- **P4:** optional cleanup.

## Priority overview

| Priority | Finding |
|---|---|
| P1 | Plugin disable lacks coordinated restoration and reset cancellation |
| P1 | Stats persistence is synchronous, whole-file, and non-atomic |
| P2 | Pregame pillar cleanup can erase pre-existing arena blocks |
| P2 | Configuration lifetime is inconsistent and undocumented in code |
| P2 | No automated coverage exists for critical lifecycle/game rules |
| P2 | Startup and administrative paths perform potentially expensive synchronous I/O/world edits |

## P0 findings

No open P0 finding remains from this review.

## Resolved: unsafe arena reset and false-success callback

The reset boundary now rejects non-direct arena paths and the template path itself, occupied worlds, and failed unloads before filesystem mutation. It copies the template to a unique staging directory, keeps the old arena as a backup through world/floor initialization, restores the backup on failed activation where possible, and reports a typed `ArenaRebuildResult`. `GameSession` returns to `WAITING` only on `SUCCESS`; all failures remain unavailable in `RESETTING` and can be retried manually. Relevant code: `ArenaManager.resetArena(...)`, `GameSession.resetArenaInternal(...)`, and `GameSessionManager.resetArenaManually(...)`.

## P1 findings

## Resolved: automatic reset evacuation

Automatic and manual reset now share `GameSession.evacuateArenaPlayers()`. Before session collections and countdown tasks are cleared, it restores administrative spectators, collects every tracked online participant plus every player physically present in the arena world, and sends them through the normal lobby-reset path. `ArenaWorldService` still refuses to unload or replace an occupied world, so a missing lobby or failed/cancelled teleport cannot progress into filesystem mutation.

## Plugin disable has no complete lifecycle cleanup

### Severity

High (P1)

### Category

Lifecycle, resource management

### Location

- `PillarsPlugin` (no `onDisable()`)
- `GameSession`, `GameSessionManager`
- all `gameevents`
- `LuckyBlockOutcomeManager.onPluginDisable(...)`

### Current behavior

Only Lucky Block outcome cleanup explicitly reacts to plugin disable. Paper cancels scheduler tasks, but no coordinator restores players, attributes, glow, borders, temporary event blocks/displays, or reconciles an asynchronous arena copy already in progress.

### Why this is a problem

Task cancellation does not undo mutations already applied to persistent Bukkit objects. Reload/disable can leave players modified or world structures/borders inconsistent, while async work may later schedule into a disabled plugin or leave a partial arena directory.

### Example scenario

The plugin reloads during Cosmic Drift or Earthquake. Scheduled restoration never executes; transient modifiers or removed blocks survive until another independent reset/reconnect.

### Recommended direction

Retain the manager graph in the plugin, add idempotent shutdown methods, stop sessions/events, restore/evacuate players and worlds, remove temporary effects, and coordinate/cancel reset work.

### Priority

Fix soon. Scope: **Medium**. Behavior risk: **Medium**.

## Resolved: Lucky Block fluid registry has stable identity

`LuckyBlockEffectService.TemporaryFluid` is now an ordinary identity-based class. Mutating its tracked location set no longer changes hash equality after registration, so delayed/session/disable cleanup can find the effect and remove its fluid blocks and location mappings.

## Resolved: Cosmic Drift player lifecycle cleanup

`CosmicDriftEvent` now records every UUID that receives its transient gravity, jump-strength, and fall-damage modifiers. It implements `PlayerLifecycleGameEvent` and removes all three modifiers immediately when that player is eliminated or removed from the session without ending the event for remaining players. Normal event stop iterates the tracked UUID set rather than current session membership and then clears the registry.

## Resolved: lethal handling respects event cancellation

`GameSessionPlayerListener.onDamage(...)` now runs at `EventPriority.HIGHEST` with `ignoreCancelled = true`. Protection and combat listeners at ordinary earlier priorities can cancel or modify damage before Pillars evaluates `getFinalDamage()`. Pillars still cancels an uncancelled lethal event itself and converts the active player through the existing spectator-elimination path.

## Stats writes block the main thread and are not atomic

### Severity

High (P1)

### Category

Persistence, performance, error recovery

### Location

- `StatsManager.incrementKill(...)`
- `incrementWin(...)`
- `incrementGamesPlayed(...)`
- `saveStats()`
- `GameSession.startGame()`

### Current behavior

Each counter increment truncates and rewrites all of `stats.json` synchronously. Match start invokes one save per active player. Writes do not use a temporary file/atomic replacement; load failure logs and starts with an empty map.

### Why this is a problem

Disk latency pauses the server tick. A crash or failed write after truncation can corrupt all statistics, and the next startup silently continues from empty in-memory data.

### Example scenario

A 12-player match starts and performs 12 full JSON writes in one tick on slow storage. A crash during one write leaves malformed JSON and all stats are ignored on restart.

### Recommended direction

Mutate counters in memory, batch/debounce saves off-thread using an immutable snapshot, and atomically replace the file. Save once for a multi-player game-start update and preserve/log failed data.

### Priority

Fix soon. Scope: **Medium**. Behavior risk: **Medium**.

## Resolved: external world changes leave the session

`GameSessionPlayerListener` now observes completed `PlayerChangedWorldEvent` transitions. If a registered player remains outside their session's arena world on the next server tick, the listener routes them through `GameSessionManager.leaveSession(...)`, which applies the existing removal, event notification, winner evaluation, player cleanup, lobby return, and presentation behavior. The deferred membership/world recheck exempts plugin-owned leave, reset evacuation, and rapid return transitions that have already cleared membership or returned the player to the arena.

## Resolved: match normalization snapshots and restores player state

Server join no longer clears inventory, armor, effects, experience, health, food, game mode, flight, glow, velocity, or fall state. Lobby action items are placed only into free slots and do not overwrite existing items.

Immediately before session normalization, `PlayerManager` captures a deep per-UUID snapshot of storage, armor, extra inventory, selected slot, health/absorption, hunger, experience, fire/fall/velocity, potion effects, game mode, flight, glow, and location. Normal leave, ending countdown, transfer, and reset evacuation restore that snapshot before applying lobby presentation. Disconnect restores the snapshot and original location before the server persists the player. Administrative spectator disconnect now restores its separately captured location/game mode instead of discarding them.

Match health normalization and restoration are capped to the player's current maximum health, so an external attribute provider lowering the maximum below 20 cannot make `setHealth(...)` fail for that reason. Coordinated plugin-disable restoration remains tracked by the separate open shutdown finding.

## Resolved: destination-first session transfer validation

`GameSessionManager.joinSession(...)` now obtains the target and calls its side-effect-free `canAcceptPlayer()` before removing any current membership. `spectateSession(...)` likewise resolves the target session and checks `canAdminSpectate(...)` first. Rejected destinations invoke the existing target handler only to produce its established failure feedback, leaving the source session untouched. Requests targeting the player's current session are idempotent and do not perform a leave/rejoin cycle.

## Resolved: floor and spawn rebuild drafts

`ArenaManager` now keeps floor edits and spawn symmetrization in a per-arena `ArenaRebuildDraft`. Administrative floor menus render and modify the draft, while gameplay continues reading the last successfully built live `Arena`. A reset snapshots the live settings and applies the draft only after the session enters `RESETTING`; successful world/floor activation persists the applied values and clears that exact draft. Any rebuild failure restores the previous live settings and retains the draft for a later retry. Edits made while a rebuild is in progress remain as a newer draft rather than being discarded by the older operation's callback.

## P2 findings

## Resolved: kill credit requires an active participant

`GameSessionPlayerListener.resolveEligibleKiller(...)` now centralizes validation for direct damage, recent damage, and void elimination. A candidate must be a different player who is still active in the same session; eliminated and administrative spectators cannot receive kill credit.

## Pregame pillar cleanup does not restore original blocks

### Severity

Medium (P2)

### Category

World state, cleanup

### Location

- `GameSession.preparePlayerPillar(...)`
- pregame branch of `removePlayer(...)`

### Current behavior

Pillar construction overwrites blocks without snapshots. Pregame leave changes bedrock/yellow-glazed blocks in the pillar column to air rather than restoring original block data.

### Why this is a problem

If the template contains those materials or non-air terrain in the column, ordinary arena blocks can be erased before a full reset.

### Example scenario

A decorative bedrock block intersects a configured pillar column; a waiting player leaves and the decoration becomes air.

### Recommended direction

Snapshot exact `BlockData` for temporary pillar positions or constrain/validate spawn columns as disposable air.

### Priority

Refactor when touching pillar setup. Scope: **Small–Medium**. Behavior risk: **Low**.

## Configuration values have inconsistent lifetimes

### Severity

Medium (P2)

### Category

Configuration, architecture

### Location

- constructors of `GameSession`, `GameEventManager`, managers
- `GameSession.startGame()` / distribution task
- admin menu update methods

### Current behavior

Some values are captured once at enable/session creation, game mode and item interval are snapshotted at match start, delivery mode is read live each delivery, and Lucky Block outcome values are mixed between construction-time fields and live config writes. There is no general reload lifecycle.

### Why this is a problem

An admin change can affect new sessions, existing waiting sessions, or running matches differently without a clear rule. UI can report persisted values that an existing manager will not use until restart.

### Example scenario

An admin edits a Lucky Block timing value in the file and reloads Bukkit config externally; existing manager fields retain the old value while the GUI reads a new value.

### Recommended direction

Classify settings as startup, session, match-snapshot, or live; validate into immutable settings objects and update only at explicit boundaries.

### Priority

Refactor when touching configuration. Scope: **Medium**. Behavior risk: **Medium**.

## Resolved: coordinator responsibility boundaries

`GameSession` remains the per-arena state-machine coordinator, roster/task owner, and transition-order authority, but it no longer implements presentation, persistent-statistics operations, or direct player/spawn/world mutations. Those responsibilities are isolated behind `SessionPresentationService`, `SessionStatisticsService`, and `SessionPlayerService`; border and event mechanics remain in `SessionWorldBorderController` and `GameEventManager`.

`LuckyBlockOutcomeManager` is now only the registered Bukkit event adapter. `LuckyBlockOutcomeSelector` owns weighted selection and per-player anti-repeat history, while `LuckyBlockEffectService` owns execution and the complete lifecycle of temporary entities, fluids, blocks, and delayed cleanup. `HudManager` remains a broad but presentation-only façade over messages/titles/action bars and `PlayerScoreboardService`; it no longer represents cross-domain coordinator debt.

The extraction preserves the existing public session/Lucky Block entry points, which avoids forcing commands, listeners, and game events through an unrelated rewrite.

## Critical rules have no automated tests

### Severity

Medium (P2)

### Category

Testing

### Location

- no `src/test`
- `GameSession`, `GameEventManager`, `ItemManager`, `ArenaManager`, `StatsManager`

### Current behavior

`mvn test` has no test sources. Most logic is tightly coupled to concrete Bukkit objects and scheduler callbacks.

### Why this is a problem

Winner-once, minimum/countdown cancellation, reset failure, killer eligibility, event cleanup, weighted selection, and persistence behavior can regress without a fast signal.

### Example scenario

Fixing the reset callback changes ordering and accidentally permits join during `RESETTING`; only live-server testing discovers it.

### Recommended direction

If the user explicitly requests test work, prioritize reset success/failure, transfer validation, winner/elimination, event cleanup, and stats serialization—not trivial getters. Repository rules otherwise prohibit inspecting, adding, modifying, or running tests.

### Priority

Add alongside P0/P1 fixes. Scope: **Medium**. Behavior risk: **Low**.

## Potentially heavy startup/admin work runs synchronously

### Severity

Medium (P2)

### Category

Performance, threading

### Location

- `ArenaManager.loadArenas()` / startup template copy
- `generateArenaFloor(...)`
- configuration/item-pool save methods

### Current behavior

Missing arena directories are recursively copied during enable, and floors can write thousands of blocks synchronously. Admin actions save whole YAML files on the main thread.

### Why this is a problem

Large templates or many/max-radius floors can delay enable or stall ticks. A radius-64 square alone covers 16,641 X/Z cells before glass/support work.

### Example scenario

Nine missing arena worlds are copied from a large template during plugin enable, causing a long startup watchdog delay.

### Recommended direction

Measure real template sizes, move filesystem work off-thread, and batch/chunk large world edits on the main thread with arena availability gates.

### Priority

Fix soon if observed in profiling; otherwise refactor when touching arena creation. Scope: **Medium–Large**. Behavior risk: **Medium**.

## Resolved: duplicate arena world identities are rejected

`ArenaManager.loadArenas()` now pre-counts case-insensitive configured world names before loading any world. Every arena participating in a duplicate identity is skipped, and the conflict is logged once in Russian instead of silently overwriting an earlier map entry.

## Resolved: GUI protection covers the complete inventory view

`GuiListener` now identifies plugin-owned menus through the top inventory. It cancels clicks throughout that view before dispatch, including bottom-inventory transfer gestures, and handles `InventoryDragEvent` when any dragged raw slot intersects the plugin-owned top inventory.

## P3 findings

### Item-pool changes can report success after save failure

- **Severity / category:** Low (P3), persistence and error handling.
- **Location:** `ItemManager.saveItemPools()` and item command/menu mutation callers.
- **Current behavior:** Save catches/logs exceptions but returns no failure result; callers still send success and retain the in-memory mutation.
- **Why / scenario:** Disk-full or permission failure makes runtime and YAML diverge while an admin is told the edit succeeded.
- **Recommended direction / priority:** Return a result and confirm durable success, or explicitly report runtime-only failure. **Refactor when touching this area.**

### Resolved: placeholder and HUD participant counts agree

`GameSession.getParticipantCount()` is now the shared participant count for session HUD and `%chronicle_players%`. It includes active and eliminated match participants but excludes administrative observers.

### Border cleanup overwrites rather than restores template settings

- **Severity / category:** Low (P3), cleanup and world state.
- **Location:** `GameSession.stopBorderTask()`.
- **Current behavior:** Match end sets a fixed center, size, damage, and buffer instead of restoring values captured before the match.
- **Why / scenario:** A template's deliberately configured border is lost during ending before world replacement, and may remain if reset fails.
- **Recommended direction / priority:** Snapshot border properties at match start and restore them idempotently. **Refactor when touching border/reset.**

### Placeholder callback threading is not guarded

- **Severity / category:** Low (P3, pending confirmation), concurrency and integration.
- **Location:** `ChroniclePlaceholderExpansion.onRequest(...)`, `GameSessionManager` mutable maps.
- **Current behavior:** Placeholder requests directly read unsynchronized manager/session collections.
- **Why / scenario:** This is safe only if every consuming PAPI/TAB path invokes it on the server thread; an async caller could observe/mutate a `HashMap` concurrently.
- **Recommended direction / priority:** Confirm the upstream threading contract. If async calls are permitted, publish immutable snapshots or marshal access safely. **Fix soon only if the contract confirms async invocation; otherwise document it.**

### Resolved: persistence dependency is declared directly

`pom.xml` now declares and shades Gson 2.11.0, which `StatsManager` imports directly, and no longer declares the unused `json-simple` dependency.

### Some Lucky outcome tasks are not explicitly owned

- **Severity / category:** Low (P3), scheduler lifecycle and retention.
- **Location:** `LuckyBlockEffectService` constructor and delayed entity/effect cleanup.
- **Current behavior:** The repeating cleanup task is not stored, and several delayed removals capture `Entity`/session without per-session task handles.
- **Why / scenario:** Paper cancels plugin tasks on disable, but targeted match cleanup cannot cancel every callback or release every captured reference immediately.
- **Recommended direction / priority:** Track task handles by effect/session and make cancellation/cleanup idempotent. **Refactor when adding outcomes or shutdown cleanup.**

### Arena menus have a fixed 28-entry display limit

- **Severity / category:** Low (P3), UI maintainability.
- **Location:** `ArenaMenuItemFactory` slot list, `ArenaMenu`, `AdminArenaListMenu`.
- **Current behavior:** Only the first 28 sorted arenas are placed and there is no pagination. The bundled configuration has nine.
- **Why / scenario:** A server configuring a 29th arena cannot select/administer it from these menus, although commands may still find it.
- **Recommended direction / priority:** Add pagination only when more than 28 arenas is a supported requirement. **Optional until then.**

## P4 findings

### Legacy presentation APIs

- **Severity / category:** Low (P4), API usage.
- **Location:** `HudManager`, GUI classes, scoreboard/title/message construction.
- **Current behavior:** Many paths use deprecated legacy string APIs.
- **Why / scenario:** There is limited immediate risk on the pinned Paper version, but migration becomes harder as APIs are removed and component formatting is split.
- **Recommended direction / priority:** Migrate incrementally to Adventure while changing presentation code. **Optional improvement.**

### Resolved: hardcoded lobby overload removed

The unused `TeleportManager.teleportToLobby(Player)` overload was removed. Callers must provide the configured lobby world name.

### Resolved: unused `COUNTDOWN` lifecycle value removed

`GameState` and all presentation switches now use `STARTING` as the sole pregame countdown state.

### GUI plugin lookup is a small hidden dependency

- **Severity / category:** Low (P4), architecture.
- **Location:** GUI classes using `JavaPlugin.getProvidingPlugin(...)`.
- **Current behavior:** Some menus discover the plugin statically while their other collaborators are constructor-provided.
- **Why / scenario:** It complicates isolated construction/testing but has little runtime impact.
- **Recommended direction / priority:** Pass the plugin/config writer explicitly when those menus are next refactored. **Optional improvement.**

### Resolved: event IDs use locale-independent normalization

`GameEventManager` lowercases event IDs once with `Locale.ROOT` before alias matching and fallback.

## Category review notes

### Global/static state and retained Bukkit objects

No mutable static gameplay collection or singleton manager was found. Static values are mainly `NamespacedKey` constants. Most durable player identity is UUID-based. Short-lived Lucky outcome tasks sometimes capture `Player`/`Entity`; the remaining concern is explicit per-session task ownership, not static state or the resolved fluid identity bug.

### Thread safety and blocking work

No database, HTTP, `CompletableFuture`, or async Bukkit-world mutation was found. Arena filesystem staging/replacement/rollback runs asynchronously and returns to the main thread for world creation. Stats/startup/config I/O remain blocking. Placeholder callback threading requires confirmation.

### Duplicate logic and dependency direction

Player cleanup primitives are centralized in `PlayerManager`, session-owned player/world mutations are routed through `SessionPlayerService`, elimination policy remains in `GameSession`, and event lifecycle remains in `GameEventManager`; there is not enough evidence to claim widespread duplicate gameplay implementations. `ArenaManager` callbacks still control reset completion state, but this is an explicit asynchronous boundary rather than a circular class dependency.

### Overengineering

The event interfaces correspond to real optional capabilities (`DirectHitGameEvent`, `KnockbackGameEvent`, `PlayerLifecycleGameEvent`) and multiple event behaviors, so they are not flagged as needless abstraction. The new session services each isolate an existing side-effect boundary and do not introduce alternate implementations or generic factories. No excessive factory/builder layer was found.

## Previous-review corrections

These earlier conclusions are not supported by the current tree and should not be carried forward:

- **“Full inventories silently discard awarded items.”** False: `ItemManager.giveOrDrop()` drops leftovers at the player.
- **“Elimination is always below Y=0.”** False: enabled floors use `floorY - 8`; Y=0 is fallback only.
- **“There are no external integrations.”** False: PlaceholderAPI/TAB integration is implemented and declared.
- **“Only three listeners are registered.”** False: five instances are registered, including two Lucky Block components.
- **“Arena GUI supports 17 arenas.”** Outdated: the current slot layout supports 28, still without pagination.
- **“Minimum players scale to 4/6 in bundled larger arenas.”** False in current configuration: all bundled arenas use 2.
- **“Border shrink is one global 360-second value.”** False: it is per arena, with bundled 240/360/480-second values.
- **“Persistence contains only kills and wins.”** Outdated: `gamesPlayed` is persisted and displayed.

## Architectural debt summary

### Immediate risks

1. Missing disable cleanup and reset coordination.
2. Non-atomic, blocking stats writes.

### Medium-term architectural debt

- No test seams or automated lifecycle coverage.
- Inconsistent configuration lifetimes outside the transactional floor/spawn rebuild draft.

### Minor cleanup

- Persistence success reporting, legacy UI APIs, GUI hidden dependencies, and pagination if arena count grows.

### Suggested improvement order

1. Add coordinated `onDisable()` cleanup using the explicit reset/session boundaries.
2. Make stats saving batched, asynchronous, and atomic through the statistics boundary.
3. Address configuration lifetime and other P2 findings incrementally; handle P3/P4 opportunistically.

The single best next change is coordinated plugin-disable cleanup, because explicit session/player/effect boundaries now exist and the remaining failure can leave persistent Bukkit mutations behind after reload or disable.
