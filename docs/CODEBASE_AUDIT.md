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
| P2 | No automated coverage exists for critical lifecycle/game rules |

## P0 findings

No open P0 finding remains from this review.

## Resolved: unsafe arena reset and false-success callback

The reset boundary now rejects non-direct arena paths and the template path itself, occupied worlds, and failed unloads before filesystem mutation. It copies the template to a unique staging directory, keeps the old arena as a backup through world/floor initialization, restores the backup on failed activation where possible, and reports a typed `ArenaRebuildResult`. `GameSession` returns to `WAITING` only on `SUCCESS`; all failures remain unavailable in `RESETTING` and can be retried manually. Relevant code: `ArenaManager.resetArena(...)`, `GameSession.resetArenaInternal(...)`, and `GameSessionManager.resetArenaManually(...)`.

## P1 findings

## Resolved: automatic reset evacuation

Automatic and manual reset now share `GameSession.evacuateArenaPlayers()`. Before session collections and countdown tasks are cleared, it restores administrative spectators, collects every tracked online participant plus every player physically present in the arena world, and sends them through the normal lobby-reset path. `ArenaWorldService` still refuses to unload or replace an occupied world, so a missing lobby or failed/cancelled teleport cannot progress into filesystem mutation.

## Resolved: coordinated plugin shutdown

`PillarsPlugin.onDisable()` now shuts down the retained manager graph in dependency order. Sessions cancel countdown, distribution, ending, event, and border work; restore administrative spectators, participant snapshots, and exact pregame pillar blocks; and clear match state. Lucky Block cleanup removes tracked temporary effects and cancels its repeating cleanup task. Arena reset shutdown stops accepting filesystem work, drains its executor, and rolls back staged arena swaps that have not reached main-thread activation. The statistics writer flushes pending changes and waits for its writer to terminate. Cleanup methods are safe to call more than once.

## Resolved: Lucky Block fluid registry has stable identity

`LuckyBlockEffectService.TemporaryFluid` is now an ordinary identity-based class. Mutating its tracked location set no longer changes hash equality after registration, so delayed/session/disable cleanup can find the effect and remove its fluid blocks and location mappings.

## Resolved: Cosmic Drift player lifecycle cleanup

`CosmicDriftEvent` now records every UUID that receives its transient gravity, jump-strength, and fall-damage modifiers. It implements `PlayerLifecycleGameEvent` and removes all three modifiers immediately when that player is eliminated or removed from the session without ending the event for remaining players. Normal event stop iterates the tracked UUID set rather than current session membership and then clears the registry.

## Resolved: lethal handling respects event cancellation

`GameSessionPlayerListener.onDamage(...)` now runs at `EventPriority.HIGHEST` with `ignoreCancelled = true`. Protection and combat listeners at ordinary earlier priorities can cancel or modify damage before Pillars evaluates `getFinalDamage()`. Pillars still cancels an uncancelled lethal event itself and converts the active player through the existing spectator-elimination path.

## Resolved: coalesced asynchronous atomic stats persistence

`StatsManager` now protects mutations with a lock and coalesces dirty state onto one daemon writer. The writer serializes a deep snapshot rather than live mutable records, writes UTF-8 JSON to `stats.json.tmp`, and atomically replaces `stats.json` when the filesystem supports it, with a replacement fallback otherwise. Shutdown flushes pending state and waits up to 30 seconds. Startup loading remains synchronous and malformed input is still reported through the plugin logger.

## Resolved: external world changes leave the session

`GameSessionPlayerListener` now observes completed `PlayerChangedWorldEvent` transitions. If a registered player remains outside their session's arena world on the next server tick, the listener routes them through `GameSessionManager.leaveSession(...)`, which applies the existing removal, event notification, winner evaluation, player cleanup, lobby return, and presentation behavior. The deferred membership/world recheck exempts plugin-owned leave, reset evacuation, and rapid return transitions that have already cleared membership or returned the player to the arena.

## Resolved: match normalization snapshots and restores player state

Server join no longer clears inventory, armor, effects, experience, health, food, game mode, flight, glow, velocity, or fall state. Lobby action items are placed only into free slots and do not overwrite existing items.

Immediately before session normalization, `PlayerManager` captures a deep per-UUID snapshot of storage, armor, extra inventory, selected slot, health/absorption, hunger, experience, fire/fall/velocity, potion effects, game mode, flight, glow, and location. Normal leave, ending countdown, transfer, and reset evacuation restore that snapshot before applying lobby presentation. Disconnect restores the snapshot and original location before the server persists the player. Administrative spectator disconnect now restores its separately captured location/game mode instead of discarding them.

Match health normalization and restoration are capped to the player's current maximum health, so an external attribute provider lowering the maximum below 20 cannot make `setHealth(...)` fail for that reason. Plugin disable now invokes session and player restoration through the retained manager graph.

## Resolved: destination-first session transfer validation

`GameSessionManager.joinSession(...)` now obtains the target and calls its side-effect-free `canAcceptPlayer()` before removing any current membership. `spectateSession(...)` likewise resolves the target session and checks `canAdminSpectate(...)` first. Rejected destinations invoke the existing target handler only to produce its established failure feedback, leaving the source session untouched. Requests targeting the player's current session are idempotent and do not perform a leave/rejoin cycle.

## Resolved: floor and spawn rebuild drafts

`ArenaManager` now keeps floor edits and spawn symmetrization in a per-arena `ArenaRebuildDraft`. Administrative floor menus render and modify the draft, while gameplay continues reading the last successfully built live `Arena`. A reset snapshots the live settings and applies the draft only after the session enters `RESETTING`; successful world/floor activation persists the applied values and clears that exact draft. Any rebuild failure restores the previous live settings and retains the draft for a later retry. Edits made while a rebuild is in progress remain as a newer draft rather than being discarded by the older operation's callback.

## P2 findings

## Resolved: kill credit requires an active participant

`GameSessionPlayerListener.resolveEligibleKiller(...)` now centralizes validation for direct damage, recent damage, and void elimination. A candidate must be a different player who is still active in the same session; eliminated and administrative spectators cannot receive kill credit.

## Resolved: pregame pillars restore exact original block data

`SpawnManager.prepareSpawnWithSnapshot(...)` captures a clone of every replaced `BlockData` before constructing a participant's pillar. `GameSession` owns that snapshot by player UUID. Leaving during `WAITING` or `STARTING`, and plugin shutdown, restore the exact captured blocks instead of replacing matching pillar materials with air. Normal match reset clears these snapshots because successful rebuilding replaces the entire arena world from its template.

## Resolved: configuration lifetime is explicit

`StartupSettings` now validates global lifecycle, lobby, pillar, border-geometry, floor-budget, and detailed event values once during `PillarsPlugin.onEnable()`. The same immutable object is shared with every session, including sessions first accessed after asynchronous arena preparation, so creation time cannot change configuration behavior.

`ArenaMatchSettings` captures game mode, delivery mode, item interval, and border duration together immediately before `RUNNING`. Distribution, HUD, Lucky Block mode, pillar material, and border timing use that snapshot for the complete match; admin changes apply to the following match. Join availability and minimum players remain deliberately live during pregame, while floor/spawn settings retain their successful-rebuild boundary.

`LuckyBlockSettings` is the explicit live boundary used by both the admin menu and outcome selection/execution. Changes affect subsequent outcomes without a second cached representation. Item settings remain live through `ItemManager`, automatic-event enablement is propagated through `GameSessionManager`, and `config.yml` documents every lifetime category. There is still no general reload command; editing YAML on disk requires restart for startup-scoped values.

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

## Resolved: startup/admin heavy work is bounded or asynchronous

Missing startup arena directories are now copied serially on `ArenaWorldService`'s file executor. Completed directories enter a main-thread activation queue that creates at most one Bukkit world per tick. `ArenaManager` does not register an arena until its world and floor are complete, so menus, quick join, and session creation cannot expose partial preparation.

`ArenaFloorService` owns one queue for both startup and reset floor jobs. It performs at most the validated `settings.floorColumnsPerTick` columns across all jobs per tick and reports completion/failure through callbacks. Reset remains in `RESETTING`, and startup arenas remain absent, until the job succeeds. Bukkit world/block APIs remain on the main thread.

`AsyncYamlWriter` receives main-thread string snapshots, coalesces the newest snapshot per path, and atomically replaces `config.yml` and `item-pools.yml` on one daemon writer. `ItemManager` retains its parsed item-pool configuration in memory, so admin edits no longer reread or rewrite the pool synchronously. Plugin shutdown drains the YAML writer after arena work stops.

## Resolved: duplicate arena world identities are rejected

`ArenaManager.loadArenas()` now pre-counts case-insensitive configured world names before loading any world. Every arena participating in a duplicate identity is skipped, and the conflict is logged once in Russian instead of silently overwriting an earlier map entry.

## Resolved: GUI protection covers the complete inventory view

`GuiListener` now identifies plugin-owned menus through the top inventory. It cancels clicks throughout that view before dispatch, including bottom-inventory transfer gestures, and handles `InventoryDragEvent` when any dragged raw slot intersects the plugin-owned top inventory.

## P3 findings

### Resolved: item-pool persistence result is reported

`AsyncYamlWriter` now completes every coalesced write request with the result of the atomic replacement. Item-pool command and menu actions wait for that result before reporting durable success; on failure they explicitly tell the administrator that the in-memory change lasts only until plugin restart.

### Resolved: placeholder and HUD participant counts agree

`GameSession.getParticipantCount()` is now the shared participant count for session HUD and `%chronicle_players%`. It includes active and eliminated match participants but excludes administrative observers.

### Resolved: match border restores template settings

`SessionWorldBorderController` now captures the complete relevant border state before the match mutation and restores it idempotently at match end, reset, or shutdown. Cleanup before a border has started no longer writes generic values into the template world.

### Resolved: placeholder callbacks read immutable snapshots

TAB documents asynchronous placeholder refresh as its default. `GameSession` now publishes immutable member/value views on lifecycle and roster changes, while `GameSessionManager` publishes an immutable session list. `ChroniclePlaceholderExpansion` resolves UUIDs against those snapshots, precomputes translated state labels on the main thread, and no longer calls `OfflinePlayer.getPlayer()` or traverses mutable session/Arena state.

### Resolved: persistence dependency is declared directly

`pom.xml` now declares and shades Gson 2.11.0, which `StatsManager` imports directly, and no longer declares the unused `json-simple` dependency.

### Resolved: Lucky outcome tasks are explicitly session-owned

`LuckyBlockEffectService` now registers every delayed entity, fluid, and temporary-block cleanup task under its `GameSession`, removes handles after normal execution, and cancels all remaining handles during direct session cleanup. Entity callbacks capture UUIDs instead of `Entity` objects. The periodic ended-session scan and global disable cleanup remain idempotent fallbacks.

### Resolved: arena menus paginate beyond 28 entries

`ArenaMenuItemFactory` now pages the shared 28 arena slots. Player selection and administrative arena lists expose bounded previous/next controls and a page indicator; administrative settings and nested floor/material menus retain the originating arena-list page for back navigation.

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

### Resolved: Lucky Block GUI uses the shared injected settings

`AdminLuckyBlockMenu` no longer discovers the plugin through `JavaPlugin.getProvidingPlugin(...)` or constructs a second settings wrapper. `AdminHubMenu` passes the single live `LuckyBlockSettings` instance explicitly; the existing `GameSessionManager` dependency exposes that narrow configuration boundary without adding another dependency to every menu in the back-navigation graph.

### Resolved: event IDs use locale-independent normalization

`GameEventManager` lowercases event IDs once with `Locale.ROOT` before alias matching and fallback.

## Category review notes

### Global/static state and retained Bukkit objects

No mutable static gameplay collection or singleton manager was found. Static values are mainly `NamespacedKey` constants. Most durable player identity is UUID-based. Short-lived Lucky outcome tasks sometimes capture `Player`/`Entity`; the remaining concern is explicit per-session task ownership, not static state or the resolved fluid identity bug.

### Thread safety and blocking work

No database, HTTP, or async Bukkit-world mutation was found. Startup copies and arena filesystem staging/replacement/rollback run on a dedicated single-thread executor and return to a one-world-per-tick main-thread activation queue. Floor world edits are main-thread-only and globally budgeted per tick. Statistics and YAML writes use separate single-thread executors with atomic replacement; `CompletableFuture<Boolean>` carries YAML write results back to main-thread completion handlers. Startup YAML/JSON reads and Bukkit world creation remain synchronous by design. Placeholder callbacks read immutable session views and locked statistics without Bukkit player access.

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

No open P0 or P1 finding remains from this audit.

### Medium-term architectural debt

- No test seams or automated lifecycle coverage.

### Minor cleanup

- Legacy UI APIs.

### Suggested improvement order

1. Add focused lifecycle and rule coverage when test work is explicitly requested.
2. Migrate legacy presentation calls incrementally when their concrete UI paths are otherwise being changed; avoid a behavior-neutral whole-UI rewrite.

The best next architectural change is focused automated lifecycle coverage, if test work is explicitly requested. The only other open finding is incremental presentation API modernization.
