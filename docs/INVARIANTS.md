# Implementation Invariants

These are constraints the current code enforces or relies upon. They describe today’s implementation; known violations or weak enforcement are stated explicitly and cross-referenced to `CODEBASE_AUDIT.md`.

## Arena and session identity

### 1. A loaded arena has at least two valid configured spawns

**Invariant:** `ArenaManager` skips an arena with fewer than two successfully parsed `[x,y,z]` spawn entries.

**Why it matters:** Spawn count is arena capacity and bounds minimum-player configuration. `SpawnManager` needs a free configured location for every joining player.

**Depends on it:** `ArenaManager`, `SpawnManager`, `GameSession.canJoin()`, menus.

### 2. At most one in-memory session is intended per arena world name

**Invariant:** `GameSessionManager` stores sessions in a map keyed by `arena.getWorldName()` and creates them lazily with `computeIfAbsent`.

**Why it matters:** All membership, tasks, event state, and winner decisions for an arena must converge on one coordinator.

**Depends on it:** Every session lookup and listener route. `ArenaManager` rejects every arena participating in a duplicate case-insensitive world identity before loading.

### 3. Session membership is UUID-based

**Invariant:** Active, eliminated spectator, and administrator spectator membership use UUID sets. Persistent statistics are also UUID-keyed.

**Why it matters:** `Player` instances are connection-scoped; UUID identity remains stable across disconnects and lookups.

**Depends on it:** `GameSession`, `GameSessionManager`, `StatsManager`, event logic.

## Player and spawn membership

### 4. A session UUID occupies at most one participant role

**Invariant:** Normal joins add to `activePlayers`; elimination removes active before adding spectator; admin spectating rejects active/spectator membership; cleanup removes from all sets.

**Why it matters:** Counts, winner evaluation, game-mode changes, and HUD assume roles do not overlap.

**Depends on it:** `GameSession`, `HudManager`, game events.

### 5. An active pregame player owns one occupied spawn

**Invariant:** Join allocates one unoccupied configured spawn and records it by UUID. Pregame leave removes that assignment and clears its generated pillar.

**Why it matters:** Capacity, teleport position, freeze, and pillar preparation are coupled to this mapping.

**Depends on it:** `SpawnManager`, `GameSession.joinPlayer()`, `removePlayer()`, match start.

### 6. Joinable session states are only WAITING and STARTING

**Invariant:** `canJoin()` and menu/manager checks exclude `RUNNING`, `ENDING`, and `RESETTING`. STARTING remains joinable.

**Why it matters:** Runtime match snapshots and player initialization assume the roster closes when `RUNNING` begins.

**Depends on it:** `GameSession`, quick join, `ArenaMenu`, commands.

**Enforcement:** `GameSessionManager` resolves and validates a join/spectate target before removing current membership. Rejected and same-session requests do not mutate the source session.

### 6a. Match normalization has one restorable pre-match snapshot

**Invariant:** Before destructive match normalization, `PlayerManager` captures at most one deep snapshot per player UUID. Lobby return or disconnect removes and restores that snapshot; repeated normalization cannot overwrite it with already-normalized state.

**Why it matters:** Inventory, armor, effects, experience, health, food, game mode, flight, glow, velocity, fall state, and location must not be permanently lost through a match lifecycle.

**Depends on it:** `PlayerManager`, `SessionPlayerService`, every session leave/end/reset/disconnect path.

**Enforcement:** Health normalization and restoration are capped to the current maximum health. Lobby entry does not normalize player state, and lobby action items use free slots rather than overwriting occupied slots.

## Lifecycle

### 7. A normal countdown requires the configured minimum

**Invariant:** Reaching the minimum starts a non-forced countdown; dropping below it during `STARTING` cancels back to `WAITING`.

**Why it matters:** It prevents ordinary undersized matches.

**Depends on it:** `joinPlayer()`, `removePlayer()`, countdown task.

**Exception:** A permission-authorized force start deliberately bypasses this requirement.

### 8. The active countdown state is STARTING

**Invariant:** The countdown task is started and executed while state is `STARTING`.

**Why it matters:** Joinability, cancellation, UI status, and transition checks depend on this value.

**Enforcement:** The countdown uses `STARTING`; `GameState` contains no separate countdown value.

### 9. Match mode and item interval are snapshotted at RUNNING transition

**Invariant:** `activeGameMode` and the item distribution interval are copied from the arena when a match starts.

**Why it matters:** Pillar preparation, Lucky Block tracking, and the distribution schedule use a stable match mode/period.

**Depends on it:** `GameSession.startGame()`, Lucky Block listeners/outcomes, distribution task.

**Enforcement gap:** Item delivery mode is not snapshotted and may change during the match.

### 10. Winner processing happens at most once per match

**Invariant:** End evaluation only acts from `RUNNING`, and `handleGameEnd()` immediately moves to `ENDING`.

**Why it matters:** Prevents duplicate wins, duplicate end presentations, and duplicate reset schedules when several events occur close together.

**Depends on it:** Damage/leave handlers, `StatsManager`, end/reset tasks.

### 11. An online sole active player is required for a recorded win

**Invariant:** A win is incremented only when active count is one and `Bukkit.getPlayer(uuid)` resolves that player. Zero players or an unresolved survivor produces no winner.

**Why it matters:** Defines the exact persistent victory rule.

**Depends on it:** `GameSession.evaluateGameEnd()`, `StatsManager`, HUD.

### 12. Games played increments once for each active UUID at match start

**Invariant:** `startGame()` increments `gamesPlayed` while iterating the active roster once.

**Why it matters:** Win-rate placeholders and scoreboard displays divide wins by games.

**Depends on it:** `StatsManager`, `HudManager`, `ChroniclePlaceholderExpansion`.

## Elimination and combat

### 13. Only active players are eliminated through the normal elimination path

**Invariant:** `playerDeath()` returns unless the UUID is currently active, then atomically moves it from active to spectator before reevaluating the match.

**Why it matters:** Prevents repeated elimination and keeps active count authoritative.

**Depends on it:** Damage, movement, death listeners, game events, winner evaluation.

### 14. The elimination height follows the configured floor

**Invariant:** The threshold is `floorY - 8` for an enabled floor and 0 otherwise.

**Why it matters:** Movement elimination is coupled to the arena floor configuration, not a universal void height.

**Depends on it:** `GameSessionPlayerListener.onMove()` and `GameSession.getEliminationY()`.

**Enforcement:** Floor/spawn edits remain in `ArenaManager`'s rebuild draft. `GameSession` continues reading the live `Arena` until a successful rebuild commits the draft.

### 15. PvP damage credit expires after ten seconds

**Invariant:** Recorded active-player damage carries an expiry time and is rejected after that time.

**Why it matters:** Kill attribution for falls and non-direct lethal damage must be temporally bounded.

**Depends on it:** `GameSession.setLastDamager()`, `getLastDamager()`, `GameSessionPlayerListener.resolveEligibleKiller(...)`, elimination messages, and statistics.

**Enforcement:** `GameSessionPlayerListener.resolveEligibleKiller(...)` rejects the victim, eliminated spectators, and administrative spectators by requiring `GameSession.isActivePlayer(...)` for direct, recent-damage, and void-elimination paths.

## Mode-specific state and tasks

### 16. Only registered Lucky Blocks produce Lucky Block outcomes

**Invariant:** Break outcomes require the sponge block location to be registered to a running Lucky Block session and broken by an active player. Mode checks use the snapshotted active mode.

**Why it matters:** Ordinary sponge blocks must not trigger the outcome system, even though Lucky Blocks use the same vanilla material for resource-pack compatibility.

**Depends on it:** `GameSession`, `LuckyBlockListener`, `LuckyBlockOutcomeManager`, `LuckyBlockOutcomeSelector`, and `LuckyBlockEffectService`.

### 17. Normal game events are single-active-event per session

**Invariant:** `GameEventManager` tracks one current event. Starting a manual event stops the existing one; random scheduling waits until an event completes.

**Why it matters:** Attribute, block, entity, and HUD effects assume serialized normal events.

**Depends on it:** All `gameevents` classes and session end/reset cleanup.

### 18. Last Breath owns the post-border event phase

**Invariant:** Border completion starts Last Breath, stops any current event, cancels the pending event start, and prevents further normal event scheduling.

**Why it matters:** The final pressure mechanic must not overlap the random-event cycle.

**Depends on it:** `SessionWorldBorderController`, `GameEventManager`, `LastBreathEvent`.

### 19. Session-owned gameplay tasks stop at match end/reset

**Invariant:** `handleGameEnd()` stops distribution, border, and all game events; reset clears countdown/end/reset/lobby tasks and temporary session maps.

**Why it matters:** Old-match callbacks must not mutate the next match.

**Depends on it:** `GameSession`, `SessionWorldBorderController`, `GameEventManager`, event implementations.

**Enforcement gaps:** Some Lucky outcome tasks are global/untracked, and plugin disable has no centralized cleanup. See the audit.

## World and persistence

### 20. Arena rebuilds are intended to originate from `arena_template`

**Invariant:** Missing arena directories at startup are copied from the template. Before either reset path clears membership, it attempts to return all tracked online participants and all physical arena-world occupants to the lobby. Rebuild accepts only a direct child of the world container other than `arena_template`, requires no remaining world occupants and successful unload, stages a template copy without identity/lock files, and retains the prior directory until the replacement world and floor initialize successfully.

**Why it matters:** Reset assumes world mutation is disposable and a clean template is authoritative.

**Depends on it:** `GameSession.evacuateArenaPlayers()`, `ArenaManager.loadArenas()`, and `ArenaWorldService.rebuild()`.

**Enforcement:** `ArenaRebuildResult` carries failure to `GameSession`; only `SUCCESS` changes the session to `WAITING`. Failed sessions remain unavailable and retryable.

### 21. Item weights used for selection are positive

**Invariant:** Loaded/admin item entries with non-positive weight are rejected or removed; weighted selection sums positive values.

**Why it matters:** The random selector assumes a positive total and valid `Material` entries.

**Depends on it:** `ItemManager`, admin item commands/menus.

### 22. Persistent statistics are non-negative UUID records

**Invariant:** Runtime mutation only increments kills, wins, and games played, and serialization uses UUID strings as keys.

**Why it matters:** HUD and placeholders assume stable, non-negative counters and compute win rate from them.

**Depends on it:** `StatsManager`, `HudManager`, PlaceholderAPI expansion.

**Enforcement gap:** The JSON write is non-atomic; a failure can violate durability even though in-memory counters remain valid.

### 23. Russian is the sole message language

**Invariant:** `TranslationManager` always loads `messages_ru.yml`, uses its bundled copy as the only fallback, and always applies Russian plural rules.

**Why it matters:** Every message key, plural form, and configured arena display name must be complete in Russian because there is no second-language fallback.

**Depends on it:** `PillarsPlugin` bootstrap, every HUD/menu/message path.

**Enforcement:** `config.yml` has no language selector, bundled arena names contain only `displayName.ru`, and production code has no `messages_en.yml` reference.

### 24. A registered player must remain in the session arena world

**Invariant:** After a completed cross-world move, a player who is still registered in a session and remains outside that session's arena world on the next server tick is removed through the normal leave lifecycle.

**Why it matters:** Active counts, winner evaluation, game-event targeting, frozen-player state, HUD values, and reset ownership must not retain a participant who is playing in another world.

**Depends on it:** `GameSessionPlayerListener.onPlayerChangedWorld()`, `GameSessionManager.leaveSession()`, and `GameSession.removePlayer()`.

**Boundary:** Same-world teleports do not violate this invariant because the code has no configured playable-region boundary and uses same-world teleportation internally.

### 25. Canceled damage cannot eliminate a player

**Invariant:** The lethal-damage adapter ignores an `EntityDamageEvent` that an earlier Bukkit/Paper handler canceled. Only an uncancelled event whose final damage reaches health plus absorption enters the normal elimination path.

**Why it matters:** Protection, invulnerability, and combat plugins must be able to prevent damage without Pillars independently applying the eliminated state.

**Depends on it:** `GameSessionPlayerListener.onDamage()` and `GameSession.playerDeath()`.

### 26. Cosmic Drift modifiers belong only to active affected players

**Invariant:** Cosmic Drift records each UUID that receives its three attribute modifiers and removes those modifiers when the player is eliminated, leaves the session, or the event stops.

**Why it matters:** Session departure and elimination must not leak altered gravity, jump strength, or fall-damage behavior into spectator mode, the lobby, or another world.

**Depends on it:** `CosmicDriftEvent`, `PlayerLifecycleGameEvent`, `GameEventManager.onPlayerEliminated()`, and `GameEventManager.onPlayerRemoved()`.

### 27. Rebuild-required settings become live only with their rebuilt world

**Invariant:** Pending floor and symmetrized-spawn values do not mutate the live `Arena` or YAML. A reset applies them only while the session is unavailable, commits them after successful world/floor activation, and restores the previous live snapshot on failure.

**Why it matters:** Elimination height, spawn allocation, border geometry, generated floor blocks, and persisted configuration must describe the same successfully activated arena version.

**Depends on it:** `ArenaManager.updateArenaFloorSettings()`, `symmetrizeArenaSpawns()`, `resetArena()`, `ArenaWorldService.rebuild()`, and the administrative floor menus.
