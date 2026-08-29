# Implemented Game Design

This is a reconstruction of player-visible behavior from the current implementation. It does not propose new mechanics.

## Core gameplay loop

1. A player uses `/pillars`, `/p menu`, `/p join <arena>`, `/p quickjoin`, or a lobby compass to choose an arena.
2. The plugin assigns an unused configured spawn, snapshots the blocks it will replace, builds a five-block pillar, teleports the player to its top, freezes them, and gives waiting controls.
3. Once the arena minimum is reached, a countdown begins. Players may continue joining while it counts down.
4. At match start, players enter Survival, receive items or Lucky Blocks at the arena interval, the border begins shrinking, and random events may occur.
5. Lethal damage or falling below the arena elimination height converts a player to Spectator.
6. When at most one active player remains, the match ends. A sole online survivor wins; otherwise there is no winner.
7. Participants are returned to the lobby after a personal countdown, and the arena world is subsequently replaced from `arena_template`.

The server join handler sends players to the configured lobby and adds lobby controls only to free inventory slots. It does not clear existing inventory or player attributes. Reconnecting does not restore a match position.

Immediately after plugin enable, an arena is hidden from selection until its template directory is ready, its Bukkit world has loaded, and its configured floor has finished batched generation. World directories are prepared serially, at most one queued world is activated per tick, and all floors share the configured column budget. This can make arenas appear progressively rather than freezing one startup tick.

When a player already belongs to another session, the requested join or administrator-spectate destination is validated first. A rejected target leaves the player in their current session; a valid target removes the old membership before completing the new admission.

## Arena selection and start conditions

An arena is joinable only when:

- `joiningOpen` is true;
- its session is in `WAITING` or `STARTING`;
- active players are below the number of configured spawns.

Quick join prefers the joinable waiting/starting session with the most active players. If no populated session qualifies, it chooses the smallest-capacity open arena.

The player arena selector and administrative arena list show the same arena characteristics: capacity, lifecycle status, joining availability, current players, minimum start count, item interval, platform state/material/shape, game mode, and item-delivery mode. Their final action line differs because one joins and the other opens editing. Both lists paginate after 28 arenas; nested administrative editing returns to the originating list page.

The effective minimum is clamped from 2 through arena capacity. All bundled arenas currently use 2. Reaching the minimum starts the configured five-second countdown. If a player leaves and a non-forced countdown drops below the minimum, it is canceled. `/p forcestart` (permission `pillars.forcestart`) starts from `WAITING` without enforcing the minimum, including with one player.

At zero, game mode, delivery mode, item interval, and border duration are fixed for the complete match. Every occupied spawn pillar is re-prepared for that match mode, player freeze ends, games-played is incremented for active players, item distribution begins immediately, and the border/event systems start. Admin changes to those four arena values apply to the following match.

## Arenas, pillars, and floors

The bundled config defines nine arenas: three each with 4, 8, and 12 spawns. Pillars are five blocks high by default:

- `STANDARD`: bedrock pillars.
- `LUCKY_BLOCKS`: sponge pillars, registered as breakable Lucky Blocks. A server resource pack can replace the sponge texture without changing plugin mechanics.

An optional generated floor is created when an arena world is loaded or reset. Supported shapes are:

- `SQUARE`: filled square centered on the average spawn position.
- `SQUARE_RING`: square area with a central opening.
- `ISLANDS`: a square island around each spawn.

Solid block materials and water/lava are supported. Liquid floors get glass below and a glass boundary. Default bundled floors are square lava at Y=75, with radius 8/16/20 by arena capacity.

Admin floor edits and spawn symmetrization are staged as an in-memory draft. Menus show the draft, but matches continue using the last successfully built floor/spawns. Applying a reset activates and persists the draft only if the replacement world and floor initialize successfully. Failure restores the prior live settings and keeps the draft available for retry; unapplied drafts are lost on plugin restart.

## Game modes and item delivery

### Standard mode

Items are selected from common, rare, or legendary pools. Bundled rarity chances are 74% common, 20% rare, and 6% legendary. Within a rarity, materials are selected by positive integer weight. A per-player recent-history reroll reduces immediate repetition. Empty/invalid pools ultimately fall back to stone.

Delivery modes:

- `SINGLE`: give one weighted item each interval; inventory overflow is dropped naturally at the player's location.
- `HOTBAR`: remove all prior plugin-tagged delivered items from the player's storage, then put a new weighted item in every empty hotbar slot. Ordinary items already occupying slots are preserved.

The bundled 4-player arenas deliver every five seconds; 8- and 12-player arenas every three seconds. The first delivery occurs when the repeating task starts.

### Lucky Blocks mode

Each interval gives one tagged sponge Lucky Block, independent of the arena delivery-mode setting. A player may place it during an active Lucky Block match. Registered blocks use vanilla sponge mining speed/tool behavior; successful breaking suppresses the sponge drop and experience, then triggers the random outcome. Registered blocks cannot be piston-moved or destroyed by explosions.

Breaking a tracked block chooses either a normal weighted item or a non-item outcome. The bundled top-level weights are 85 item, 7 good, 3 neutral, and 5 bad. Non-item outcomes have a short per-player anti-repeat history.

Implemented good outcomes are another random item, 1–3 diamonds, a golden apple, full health/food and extinguishing fire, regeneration, speed, strength, 8–20 XP, a tamed wolf, a seven-item rain, and a jackpot. Neutral outcomes are temporary water, a passive mob, two extra Lucky Blocks, swapping with another active player, and a firework. Bad outcomes are temporary lava, primed TNT, an explosion, visual lightning plus 5 damage, a falling anvil, poison, blindness, launching, one hostile mob, a three-mob horde, a five-second cobweb trap, a creeper, four falling TNT, and a ravager mini-boss.

Temporary fluids, blocks, and most spawned entities are scheduled for cleanup. TNT/explosion block damage is configurable.

## Combat and elimination

PvP uses normal Paper combat plus event modifiers. Recent active-player PvP damage is remembered for ten seconds for kill attribution. Direct players and projectile shooters are also resolved at lethal damage time.

A player is eliminated when:

- uncancelled final incoming damage is computed as lethal against health plus absorption; or
- movement puts their Y below `floorY - 8` when the floor is enabled, otherwise below Y=0; or
- Bukkit death handling reaches the session fallback path.

Damage canceled by another handler before Pillars' high-priority evaluation does not eliminate the player. For plugin-handled lethal damage, Pillars cancels the event, removes the player from the active set, adds them to the spectator set, clears/normalizes them into Spectator mode, gives a leave item, announces the cause, and checks active game-event rules. Kill credit requires a different player who is still active in the same session when elimination is resolved; that player receives one persisted kill and reward feedback.

Cause labels distinguish void, melee, projectile, explosion, fire, lava, fall, and other damage.

## World border and Last Breath

At match start the border is centered on the mean configured spawn X/Z. Initial diameter is derived from the maximum spawn distance plus configured padding, with a configured minimum. It shrinks linearly on a per-tick wall-clock calculation to the minimum size over the arena's duration (bundled: 240, 360, or 480 seconds).

When shrink completes, `LastBreathEvent` stops normal events and periodically reapplies Wither II (amplifier 1) to active players. Match end restores the border center, size, damage settings, and warning settings captured immediately before the match border started.

## Timed game events

When enabled, automatic events are scheduled at a random delay between the configured minimum and maximum (bundled 30–60 seconds), with anti-repeat history. Administrators can inspect `/p event next`, list events with `/p events`, force named events with `/p event <id>`, and toggle automatic scheduling. Disabling automatic events cancels the next scheduled start but does not stop a currently active event.

Implemented rules:

- **Super Smash Bros** (20s): attack knockback is multiplied by 2.5.
- **Cosmic Drift** (25s): active players receive 0.35 gravity, 1.3 jump strength, and 0.25 fall-damage multipliers. A player's modifiers are removed immediately if they are eliminated or leave during the event.
- **Meteor Shower** (24s): warned falling displays impact near active players, dealing up to 6 damage and knockback. It is force-startable but excluded from random selection.
- **Earthquake** (24s): warned supporting blocks temporarily collapse, then restore.
- **The Hunt Begins** (30s): one target glows; a qualifying killer receives two random items, or the target receives them for surviving.
- **Hot Potato** (15s): one active holder is marked. A direct melee hit passes it after a cooldown. The final holder receives 6 damage plus knockback.
- **Last Breath**: begins at border completion and runs until the match ends.

Only one normal event is active at a time. Starting an administrator-forced event stops the existing event and reschedules the automatic cycle afterward, except Last Breath, which ends that cycle.

## Victory and rewards

After an elimination or active-player departure, the session ends when active-player count is zero or one. State guards prevent winner handling from running twice.

- Exactly one active **online** player: that player gets one persisted win; all tracked participants see/hear the winner presentation.
- Zero active players, or a sole survivor who cannot be resolved online: no winner is recorded and the no-winner presentation is used.

There is no currency or match prize beyond statistics, event rewards, sounds, titles, and messages.

## Disconnect and leave behavior

### Before/during countdown

The player is removed, every block replaced by their occupied pillar is restored to its exact captured block data, and their pre-match state is restored on voluntary leave. They are then sent to the lobby and receive lobby controls in free slots. Disconnect performs the same pillar restoration and restores the captured player state and original location without applying lobby controls. If the count falls below the minimum, a normal countdown is canceled; a forced countdown continues.

### During a match

Quit or `/p leave` removes the player from active play, notifies the current game event, restores pre-match state, and reevaluates victory. It is a departure rather than a recorded death; there is no kill credit solely for quitting. Reconnect begins in the lobby rather than in the abandoned match.

### After elimination

An eliminated player may spectate and use the leave item. Leaving or disconnecting removes spectator membership but does not affect the active count. Online participants are otherwise returned by the post-match countdown.

### Admin spectator

Normal exit, match end, and disconnect restore the location and game mode saved on entry.

## Reset behavior

Match end immediately stops item distribution, restores the pre-match world border, stops game events, cancels delayed Lucky Block outcome cleanup, removes the session's tracked Lucky entities/fluids/temporary blocks, clears damage credit and Lucky Block registrations, converts survivors, and later clears session collections/tasks. Reset requires an empty arena world and successful unload. It stages a fresh `arena_template` copy, retains the old directory as a backup, creates the new world/floor, and deletes the backup only after success. Failure restores the backup where possible and leaves the arena closed in `RESETTING` for an administrator to retry.

Match entry snapshots inventory/armor/extra slots, selected slot, effects, health/absorption, food, XP, fire, fall state, velocity, game mode, flight, glow, and location before normalizing the player for gameplay. Normal leave, match return, transfer, reset evacuation, and disconnect restore that snapshot. Health is normalized and restored no higher than the current maximum health.

Both automatic and manual reset return tracked online participants and every player currently in the arena world to the lobby before clearing session membership and rebuilding. This forced evacuation is the fallback when the normal post-match countdown has not completed. If anyone remains in the world, rebuild is refused and the arena stays unavailable in `RESETTING`.

Plugin shutdown cancels session countdown/distribution/ending/event/border work, restores administrative spectators and captured participant state, restores pregame pillar blocks, removes tracked Lucky Block world effects, and reconciles arena rebuild work before statistics are flushed. This is server lifecycle cleanup rather than an additional gameplay transition; sessions are not resumed after re-enable.

## Configuration affecting gameplay

- Lifecycle: begin countdown, spectator delay, lobby countdown, reset delay.
- Arena: spawns/capacity, minimum players, join availability, mode, delivery mode, item interval, border duration.
- Floor: enabled, shape, material, radius, Y.
- Border/pillars: minimum border diameter, spawn padding, pillar height.
- Preparation performance: maximum generated floor columns processed per tick.
- Items: rarity percentages, rarity pools/weights, anti-repeat length.
- Lucky Blocks: item/category weights, non-item anti-repeat, fluid/mob duration, TNT fuse, explosion power, block damage.
- Events: enable flag, delay range, anti-repeat, and the timing/strength values listed under each event.
- Presentation: Russian messages/display names and the lobby world name. The built-in and bundled TAB scoreboards share the same gold/gray section style, compact field symbols, and indented detail rows. There is no language selector or English fallback.

There is no general runtime reload command. Lifecycle, lobby, pillar, border-geometry, and detailed event settings require restart. Join availability and minimum players apply during pregame; game mode, delivery mode, item interval, and border duration apply from the next match; Lucky Block probabilities/effects, item rarity/pools, and automatic-event enablement apply to subsequent operations. Floor/spawn drafts activate only after successful rebuild.

## Unclear / Requires Confirmation

- Whether one-player forced matches are expected to end immediately. Current code starts one, but winner evaluation is not invoked at start, so it continues until another end trigger.
- Whether same-world administrative teleports away from the playable area should count as leaving. Cross-world moves now remove the player, but the implementation has no configured arena boundary for classifying destinations within the arena world.
- Whether placing a tagged Lucky Block outside an active Lucky Block match should be prevented. It currently becomes an ordinary sponge.
