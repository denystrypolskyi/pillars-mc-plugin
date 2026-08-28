package org.example.pillars;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.example.pillars.entities.Arena;
import org.example.pillars.enums.ArenaRebuildResult;
import org.example.pillars.enums.ArenaResetResult;
import org.example.pillars.enums.ArenaGameMode;
import org.example.pillars.enums.GameState;
import org.example.pillars.enums.EliminationCause;
import org.example.pillars.enums.ItemDeliveryMode;
import org.example.pillars.gameevents.GameEventManager;
import org.example.pillars.gameevents.GameEventStatus;
import org.example.pillars.gameevents.NextGameEventStatus;
import org.example.pillars.managers.*;

import java.util.*;
import java.util.function.Consumer;

public class GameSession {
    private static final long DAMAGE_CREDIT_MILLIS = 10_000L;

    private final Arena arena;

    private final Set<UUID> activePlayers = new HashSet<>();
    private final Set<UUID> spectators = new HashSet<>();
    private final Set<UUID> adminSpectators = new HashSet<>();
    private final Map<UUID, Location> frozenPlayers = new HashMap<>();
    private final Map<UUID, DamageCredit> lastDamagerMap = new HashMap<>();
    private final Map<UUID, Location> occupiedSpawns = new HashMap<>();
    private final Set<Location> luckyBlocks = new HashSet<>();
    private final Map<UUID, Location> adminSpectatorPreviousLocations = new HashMap<>();
    private final Map<UUID, GameMode> adminSpectatorPreviousGameModes = new HashMap<>();

    private final SessionPresentationService presentation;
    private final SessionPlayerService players;
    private final SessionStatisticsService statistics;
    private final ItemManager itemManager;
    private final ArenaManager arenaManager;
    private final JavaPlugin plugin;
    private final GameEventManager gameEventManager;
    private final SessionWorldBorderController worldBorderController;

    private BukkitTask beginGameCountdownTask;
    private BukkitTask waitingForPlayersTask;
    private BukkitTask itemDistributionTask;
    private BukkitTask endGameCountdownStartDelayTask;
    private BukkitTask arenaResetDelayTask;

    private final Map<UUID, BukkitTask> endGameCountdownTasks = new HashMap<>();

    private GameState state = GameState.WAITING;

    private boolean resetInProgress = false;
    private boolean forceStart = false;
    private ArenaGameMode activeGameMode;
    private int activeItemIntervalSeconds;

    private final int beginCountdownSeconds;
    private final int endGameLobbyCountdownSeconds;
    private final long endGameSpectatorDelayTicks;
    private final long arenaResetDelayTicks;
    private final int spawnPillarHeightBlocks;
    private final String lobbyWorldName;

    public GameSession(
            JavaPlugin plugin,
            HudManager hudManager,
            PlayerManager playerManager,
            StatsManager statsManager,
            SpawnManager spawnManager,
            SoundManager soundManager,
            TeleportManager teleportManager,
            ItemManager itemManager,
            ArenaManager arenaManager,
            Arena arena
    ) {
        this.plugin = plugin;
        this.presentation = new SessionPresentationService(hudManager, soundManager);
        this.players = new SessionPlayerService(playerManager, spawnManager, teleportManager);
        this.statistics = new SessionStatisticsService(statsManager);
        this.itemManager = itemManager;
        this.arenaManager = arenaManager;
        this.arena = arena;
        this.gameEventManager = new GameEventManager(plugin, this, hudManager, soundManager, itemManager);
        this.worldBorderController = new SessionWorldBorderController(
                plugin,
                arena,
                gameEventManager,
                Math.max(1.0, plugin.getConfig().getDouble("settings.borderMinSize", 1.0)),
                Math.max(1.0, plugin.getConfig().getDouble("settings.borderSpawnPaddingBlocks", 10.0))
        );

        this.beginCountdownSeconds = Math.max(1, plugin.getConfig().getInt("settings.beginCountdownSeconds", 5));
        this.endGameLobbyCountdownSeconds = Math.max(1, plugin.getConfig().getInt("settings.endGameLobbyCountdownSeconds", 5));
        this.endGameSpectatorDelayTicks = Math.max(0L, plugin.getConfig().getLong("settings.endGameSpectatorDelayTicks", 40L));
        this.arenaResetDelayTicks = Math.max(1L, plugin.getConfig().getLong("settings.arenaResetDelayTicks", 160L));
        this.spawnPillarHeightBlocks = Math.max(1, Math.min(
                64,
                plugin.getConfig().getInt("settings.spawnPillarHeightBlocks", 5)
        ));
        this.lobbyWorldName = plugin.getConfig().getString("settings.lobbyWorldName", "world");
    }

    public void playerJoin(Player player) {
        if (!canJoin()) {
            if (arena.getSpawnPoints() == null || arena.getSpawnPoints().isEmpty()) {
                presentation.arenaConfigurationError(player);
            } else if (!arena.isJoiningOpen()) {
                presentation.arenaClosed(player, arena.getDisplayName());
            } else if (activePlayers.size() >= arena.getSpawnPoints().size()) {
                presentation.noSpawnAvailable(player);
            } else {
                presentation.gameAlreadyStarted(player);
            }
            return;
        }

        players.resetForJoin(player);
        if (!teleportToSpawn(player)) {
            players.returnToLobby(player, lobbyWorldName);
            return;
        }
        addActivePlayer(player);
        if (state == GameState.WAITING) {
            players.giveWaitingItems(player, arena.getDisplayName());
        }
        presentation.joined(player, arena, activePlayers.size());

        updateArenaHudForAllPlayers();

        presentation.updatePlayer(player, getParticipantCount(), arena.getSpawnPoints().size(), state,
                arena.getDisplayName(), statistics.get(player));
        if (state == GameState.WAITING && activePlayers.size() < getMinPlayers()) {
            startWaitingForPlayersTask();
        }
        startBeginGameCountdown();
    }

    private void removePlayer(Player player, boolean isDisconnect) {
        UUID uuid = player.getUniqueId();

        BukkitTask task = endGameCountdownTasks.remove(uuid);
        if (task != null) task.cancel();

        if (adminSpectators.contains(uuid)) {
            if (isDisconnect) {
                restoreAdminSpectator(player);
            } else {
                restoreAdminSpectator(player);
            }
            updateArenaHudForAllPlayers();
            return;
        }

        boolean wasActive = activePlayers.contains(uuid);

        if (isDisconnect && !wasActive && !spectators.contains(uuid)) return;

        if (wasActive && state == GameState.RUNNING) {
            gameEventManager.onPlayerRemoved(player);
        }

        activePlayers.remove(uuid);
        spectators.remove(uuid);
        itemManager.clearRecentItems(List.of(uuid));
        lastDamagerMap.remove(uuid);

        if (state == GameState.WAITING || state == GameState.STARTING) {
            Location spawn = occupiedSpawns.remove(uuid);
            luckyBlocks.removeAll(players.cleanupSpawn(spawn, spawnPillarHeightBlocks));

            if (state == GameState.STARTING && activePlayers.size() < getMinPlayers()) {
                cancelBeginGameCountdownTask();
                state = GameState.WAITING;
                forceStart = false;
                giveWaitingItems();
                updateArenaHudForAllPlayers();
                startWaitingForPlayersTask();
            }
        }

        if (isDisconnect) {
            players.release(player);
        } else {
            players.returnToLobby(player, lobbyWorldName);
        }
        updateArenaHudForAllPlayers();
        if (state == GameState.WAITING && !activePlayers.isEmpty()) {
            startWaitingForPlayersTask();
        } else if (activePlayers.isEmpty()) {
            cancelWaitingForPlayersTask();
        }

        if (state == GameState.RUNNING) {
            evaluateGameEnd();
        }
    }

    public void playerLeave(Player player) {
        removePlayer(player, false);
    }

    public void playerDisconnect(Player player) {
        removePlayer(player, true);
    }

    public boolean adminSpectate(Player player) {
        if (!canAdminSpectate(player)) {
            if (state != GameState.RUNNING) {
                presentation.arenaSpectateUnavailable(player);
            } else {
                presentation.cannotSpectateOwnGame(player);
            }
            return false;
        }

        adminSpectators.add(player.getUniqueId());
        adminSpectatorPreviousLocations.put(player.getUniqueId(), player.getLocation());
        adminSpectatorPreviousGameModes.put(player.getUniqueId(), player.getGameMode());
        players.makeAdminSpectator(player, arena.getSpectatorCenter());
        updateArenaHudForAllPlayers();
        presentation.adminSpectatorJoined(player, arena.getDisplayName());
        return true;
    }

    public boolean canAdminSpectate(Player player) {
        if (state != GameState.RUNNING) {
            return false;
        }

        return !activePlayers.contains(player.getUniqueId());
    }


    public void playerDeath(Player dead, Player killer) {
        playerDeath(dead, killer, EliminationCause.OTHER);
    }

    public void playerDeath(Player dead, Player killer, EliminationCause cause) {
        UUID uuid = dead.getUniqueId();
        if (!activePlayers.contains(uuid)) return;

        BukkitTask endGameCountdownTask = endGameCountdownTasks.remove(uuid);
        if (endGameCountdownTask != null) {
            endGameCountdownTask.cancel();
        }

        rewardKiller(killer);
        presentation.eliminated(dead, killer, arena, cause);
        gameEventManager.onPlayerEliminated(dead, killer);

        setPlayerAsSpectator(dead);

        presentation.lost(dead);

        evaluateGameEnd();
    }

    public boolean forceStart(Player startedBy) {
        if (state != GameState.WAITING) {
            return false;
        }

        forceStart = true;
        presentation.forceStarted(startedBy, arena);

        if (beginGameCountdownTask == null) {
            startBeginGameCountdown();
        }

        return true;
    }

    private void handleGameEnd(Player winner) {
        if (state == GameState.ENDING || state == GameState.RESETTING) return;

        stopSessionTasks();
        state = GameState.ENDING;
        updateArenaHudForAllPlayers();

        Set<UUID> allPlayersSnapshot = new HashSet<>(activePlayers);
        allPlayersSnapshot.addAll(spectators);
        restoreAdminSpectators();

        for (UUID uuid : new ArrayList<>(activePlayers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                players.makeEndingSpectator(player, arena.getSpectatorCenter());
                presentation.spectator(player);
            }
            spectators.add(uuid);
        }
        activePlayers.clear();

        if (winner != null) {
            var winnerStats = statistics.recordWin(winner);
            presentation.updateStats(winner, winnerStats);
            presentation.winner(winner, arena, allPlayersSnapshot);
        } else {
            presentation.noWinner(arena, allPlayersSnapshot);
        }

        endGameCountdownStartDelayTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            endGameCountdownStartDelayTask = null;
            for (UUID uuid : allPlayersSnapshot) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && spectators.contains(uuid)) {
                    startEndGameCountdown(player, endGameLobbyCountdownSeconds);
                }
            }
        }, endGameSpectatorDelayTicks);

        arenaResetDelayTask = Bukkit.getScheduler().runTaskLater(plugin, this::resetGame, arenaResetDelayTicks);
    }

    public void startEndGameCountdown(Player player, int seconds) {
        UUID uuid = player.getUniqueId();
        BukkitTask previousTask = endGameCountdownTasks.remove(uuid);
        if (previousTask != null) {
            previousTask.cancel();
        }

        final int[] timeLeft = {seconds};

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || !spectators.contains(uuid)) {
                BukkitTask t = endGameCountdownTasks.remove(uuid);
                if (t != null) t.cancel();
                spectators.remove(uuid);
                return;
            }

            if (timeLeft[0] > 0) {
                presentation.returnCountdown(player, timeLeft[0]);
                timeLeft[0]--;
            } else {
                players.returnToLobby(player, lobbyWorldName);

                spectators.remove(uuid);

                BukkitTask t = endGameCountdownTasks.remove(uuid);
                if (t != null) t.cancel();
            }
        }, 0L, 20L);

        endGameCountdownTasks.put(uuid, task);
    }


    private void evaluateGameEnd() {
        if (state != GameState.RUNNING) return;

        if (activePlayers.isEmpty()) {
            handleGameEnd(null);
            return;
        }

        if (activePlayers.size() == 1) {
            UUID winnerId = activePlayers.iterator().next();
            Player winner = Bukkit.getPlayer(winnerId);
            handleGameEnd(winner);
        }
    }

    private void rewardKiller(Player killer) {
        if (killer == null) return;

        presentation.updateStats(killer, statistics.recordKill(killer));
    }

    private void setPlayerAsSpectator(Player player) {
        UUID uuid = player.getUniqueId();

        activePlayers.remove(uuid);
        spectators.add(uuid);
        itemManager.clearRecentItems(List.of(uuid));

        frozenPlayers.remove(uuid);
        lastDamagerMap.remove(uuid);

        players.makeSpectator(player, arena.getSpectatorCenter());
        presentation.spectator(player);
    }

    private boolean teleportToSpawn(Player player) {
        if (arena.getSpawnPoints() == null || arena.getSpawnPoints().isEmpty()) {
            presentation.arenaConfigurationError(player);
            return false;
        }

        SessionPlayerService.PreparedSpawn prepared = players.prepareForJoin(
                player, arena, getActivePlayerIds(), occupiedSpawns.values(), spawnPillarHeightBlocks
        );
        if (prepared == null) {
            presentation.noSpawnAvailable(player);
            return false;
        }
        if (arena.getGameMode() == ArenaGameMode.LUCKY_BLOCKS) {
            luckyBlocks.addAll(prepared.pillarBlocks());
        }
        occupiedSpawns.put(player.getUniqueId(), prepared.spawn());
        frozenPlayers.put(player.getUniqueId(), prepared.destination());
        return true;
    }

    private void resetGame() {
        if (resetInProgress) return;

        resetInProgress = true;
        state = GameState.RESETTING;
        updateArenaHudForAllPlayers();

        evacuateArenaPlayers();
        resetSession();
        resetArenaInternal();
    }

    public ArenaResetResult resetArenaManually(Consumer<ArenaRebuildResult> completionCallback) {
        if (resetInProgress) return ArenaResetResult.ALREADY_RESETTING;
        if (Bukkit.getWorld(lobbyWorldName) == null) return ArenaResetResult.LOBBY_UNAVAILABLE;

        resetInProgress = true;
        state = GameState.RESETTING;
        updateArenaHudForAllPlayers();

        evacuateArenaPlayers();
        resetSession();
        resetArenaInternal(completionCallback);
        return ArenaResetResult.STARTED;
    }

    private void evacuateArenaPlayers() {
        restoreAdminSpectators();

        players.evacuate(getAllPlayerIds(), arena.getWorldName(), lobbyWorldName);
    }

    private void resetArenaInternal() {
        resetArenaInternal(null);
    }

    private void resetArenaInternal(Consumer<ArenaRebuildResult> completionCallback) {
        arenaManager.resetArena(arena, result -> {
            resetInProgress = false;
            if (result == ArenaRebuildResult.SUCCESS) {
                state = GameState.WAITING;
            } else {
                state = GameState.RESETTING;
            }
            if (completionCallback != null) {
                completionCallback.accept(result);
            }
        });
    }

    public boolean isResetInProgress() {
        return resetInProgress;
    }

    private void resetSession() {
        stopSessionTasks();
        clearSessionState();
    }


    private void clearSessionState() {
        itemManager.clearRecentItems(getAllPlayerIds());
        frozenPlayers.clear();
        activePlayers.clear();
        spectators.clear();
        restoreAdminSpectators();
        adminSpectators.clear();
        adminSpectatorPreviousLocations.clear();
        adminSpectatorPreviousGameModes.clear();
        lastDamagerMap.clear();
        endGameCountdownTasks.clear();
        occupiedSpawns.clear();
        luckyBlocks.clear();
        activeGameMode = null;
        activeItemIntervalSeconds = 0;
        forceStart = false;
    }

    private void stopSessionTasks() {
        cancelBeginGameCountdownTask();
        cancelWaitingForPlayersTask();
        cancelItemDistributionTask();
        cancelEndGameCountdownStartDelayTask();
        cancelArenaResetDelayTask();
        gameEventManager.stop();

        cancelEndGameCountdownTasks();

        worldBorderController.stop();
    }

    private boolean canJoin() {
        return state != GameState.RUNNING
                && state != GameState.RESETTING
                && state != GameState.ENDING
                && arena.isJoiningOpen()
                && arena.getSpawnPoints() != null
                && activePlayers.size() < arena.getSpawnPoints().size();
    }

    public boolean canAcceptPlayer() {
        return canJoin();
    }

    public Set<UUID> getActivePlayerIds() {
        return Collections.unmodifiableSet(activePlayers);
    }

    public Set<UUID> getAllPlayerIds() {
        Set<UUID> all = new HashSet<>(activePlayers);
        all.addAll(spectators);
        all.addAll(adminSpectators);
        return Collections.unmodifiableSet(all);
    }

    private void addActivePlayer(Player player) {
        activePlayers.add(player.getUniqueId());
    }

    public boolean hasPlayer(Player player) {
        return getAllPlayerIds().contains(player.getUniqueId());
    }

    public boolean isParticipant(Player player) {
        UUID uuid = player.getUniqueId();
        return activePlayers.contains(uuid) || spectators.contains(uuid);
    }

    public boolean isPlayerFrozen(Player player) {
        return frozenPlayers.containsKey(player.getUniqueId());
    }

    public Location getFrozenPlayerLocation(Player player) {
        return frozenPlayers.get(player.getUniqueId());
    }

    public void setLastDamager(UUID victim, UUID damager) {
        lastDamagerMap.put(victim, new DamageCredit(
                damager,
                System.currentTimeMillis() + DAMAGE_CREDIT_MILLIS
        ));
    }

    public UUID getLastDamager(UUID victim) {
        DamageCredit credit = lastDamagerMap.get(victim);
        if (credit == null) return null;
        if (System.currentTimeMillis() > credit.expiresAt()) {
            lastDamagerMap.remove(victim);
            return null;
        }
        return credit.damager();
    }

    private record DamageCredit(UUID damager, long expiresAt) {}

    public Arena getArena() {
        return arena;
    }

    public boolean isActivePlayer(Player player) {
        return player != null && activePlayers.contains(player.getUniqueId());
    }

    public boolean isLuckyBlock(org.bukkit.block.Block block) {
        return block != null && luckyBlocks.contains(block.getLocation());
    }

    public void registerLuckyBlock(org.bukkit.block.Block block) {
        if (block != null) luckyBlocks.add(block.getLocation());
    }

    public boolean removeLuckyBlock(org.bukkit.block.Block block) {
        return block != null && luckyBlocks.remove(block.getLocation());
    }

    public boolean isLuckyBlocksModeActive() {
        return state == GameState.RUNNING && activeGameMode == ArenaGameMode.LUCKY_BLOCKS;
    }

    public int getActiveItemIntervalSeconds() {
        return Math.max(1, activeItemIntervalSeconds);
    }

    public double getEliminationY() {
        return arena.isFloorEnabled()
                ? arena.getFloorY() - ArenaFloorService.ELIMINATION_MARGIN
                : 0.0;
    }

    public GameState getState() {
        return state;
    }

    public Vector modifyKnockback(Player victim, Player damager, Vector knockback) {
        return gameEventManager.modifyKnockback(victim, damager, knockback);
    }

    public void handleDirectPlayerHit(Player attacker, Player victim) {
        gameEventManager.onDirectHit(attacker, victim);
    }

    public boolean startGameEvent(String eventId) {
        return gameEventManager.startEvent(eventId);
    }

    public GameEventStatus getActiveGameEventStatus() {
        return gameEventManager.getActiveEventStatus();
    }

    public NextGameEventStatus getNextGameEventStatus() {
        return gameEventManager.getNextEventStatus();
    }

    public void setAutomaticGameEventsEnabled(boolean enabled) {
        gameEventManager.setAutomaticEventsEnabled(enabled);
    }

    public boolean areAutomaticGameEventsEnabled() {
        return gameEventManager.areAutomaticEventsEnabled();
    }

    public boolean isFinalPhaseActive() {
        return gameEventManager.isFinalPhaseActive();
    }

    private void updateArenaHudForAllPlayers() {
        presentation.updateArena(
                getAllPlayerIds(),
                getParticipantCount(),
                arena.getSpawnPoints().size(),
                state,
                arena.getDisplayName()
        );
    }

    public int getParticipantCount() {
        return activePlayers.size() + spectators.size();
    }

    public void startBeginGameCountdown() {
        if (state != GameState.WAITING) return;
        if ((activePlayers.size() < getMinPlayers() && !forceStart) || beginGameCountdownTask != null) return;

        state = GameState.STARTING;
        removeWaitingItems();
        cancelWaitingForPlayersTask();
        updateArenaHudForAllPlayers();
        final int[] counter = {beginCountdownSeconds};

        beginGameCountdownTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (activePlayers.isEmpty()) {
                cancelBeginGameCountdownTask();
                return;
            }

            if (!forceStart && activePlayers.size() < getMinPlayers()) {
                cancelBeginGameCountdownTask();
                state = GameState.WAITING;
                giveWaitingItems();
                updateArenaHudForAllPlayers();
                startWaitingForPlayersTask();
                for (UUID uuid : activePlayers) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null) {
                        presentation.notEnoughPlayers(player);
                    }
                }

                return;
            }

            for (UUID uuid : activePlayers) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    presentation.countdown(player, counter[0]);
                }
            }

            counter[0]--;
            if (counter[0] < 0) {
                statistics.recordGamesPlayed(getActivePlayerIds());
                activeGameMode = arena.getGameMode();
                activeItemIntervalSeconds = Math.max(1, arena.getItemCooldownSeconds());
                prepareOccupiedPillarsForGameMode();
                state = GameState.RUNNING;
                frozenPlayers.clear();
                cancelBeginGameCountdownTask();
                updateArenaHudForAllPlayers();

                for (UUID uuid : getActivePlayerIds()) {
                    if (uuid == null) {
                        continue;
                    }
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null) {
                        continue;
                    }
                    presentation.gameStarted(player);
                }

                worldBorderController.start();
                if (!forceStart) {
                    presentation.gameStarted(arena);
                }
                startItemDistributionTask();
                gameEventManager.scheduleRandomEvent();
            }
        }, 0L, 20L);
    }

    private void startItemDistributionTask() {
        if (itemDistributionTask != null) return;

        final int interval = getActiveItemIntervalSeconds();
        final int[] counter = {interval};
        final ItemDeliveryMode[] previousMode = {arena.getItemDeliveryMode()};

        itemDistributionTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (UUID uuid : activePlayers) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    double currentBorderSize = worldBorderController.getCurrentSize();
                    presentation.itemCooldown(
                             player,
                             counter[0],
                             activeGameMode,
                             arena.getItemDeliveryMode(),
                             worldBorderController.getSecondsUntilNextDecrease(currentBorderSize),
                             getActiveGameEventStatus()
                    );
                }
            }

            if (counter[0] == interval) {
                for (UUID uuid : activePlayers) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null) {
                        ItemDeliveryMode currentMode = arena.getItemDeliveryMode();
                        if (activeGameMode == ArenaGameMode.LUCKY_BLOCKS) {
                            itemManager.giveLuckyBlock(player);
                        } else if (currentMode == ItemDeliveryMode.HOTBAR) {
                            itemManager.refreshHotbar(player);
                        } else {
                            if (previousMode[0] == ItemDeliveryMode.HOTBAR) {
                                itemManager.clearDeliveredItems(player);
                            }
                            itemManager.giveRandomItem(player);
                        }
                        presentation.itemGiven(player);
                    }
                }
                previousMode[0] = arena.getItemDeliveryMode();
            }

            counter[0]--;
            if (counter[0] <= 0) counter[0] = interval;
        }, 0L, 20L);
    }

    private void prepareOccupiedPillarsForGameMode() {
        luckyBlocks.clear();
        for (Location spawn : occupiedSpawns.values()) {
            List<Location> blocks = players.rebuildPillar(spawn, spawnPillarHeightBlocks, activeGameMode);
            if (activeGameMode == ArenaGameMode.LUCKY_BLOCKS) {
                luckyBlocks.addAll(blocks);
            }
        }
    }

    private void startWaitingForPlayersTask() {
        if (waitingForPlayersTask != null) return;

        waitingForPlayersTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (state != GameState.WAITING || activePlayers.isEmpty()) {
                cancelWaitingForPlayersTask();
                return;
            }

            for (UUID uuid : activePlayers) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    presentation.waiting(player, activePlayers.size(), getMinPlayers());
                }
            }
        }, 0L, 20L);
    }

    private void giveWaitingItems() {
        for (UUID uuid : activePlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                players.giveWaitingItems(player, arena.getDisplayName());
            }
        }
    }

    private void removeWaitingItems() {
        for (UUID uuid : activePlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                players.removeWaitingItems(player);
            }
        }
    }

    private int getMinPlayers() {
        return Math.max(ArenaManager.MIN_PLAYERS_TO_START, arena.getMinPlayers());
    }

    private void restoreAdminSpectators() {
        for (UUID uuid : new HashSet<>(adminSpectators)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                restoreAdminSpectator(player);
            }
        }
    }

    private void restoreAdminSpectator(Player player) {
        UUID uuid = player.getUniqueId();
        adminSpectators.remove(uuid);

        Location previousLocation = adminSpectatorPreviousLocations.remove(uuid);
        GameMode previousGameMode = adminSpectatorPreviousGameModes.remove(uuid);

        players.restoreAdminSpectator(player, previousLocation, previousGameMode);
        presentation.resetScoreboard(player);
    }

    private void cancelEndGameCountdownTasks() {
        for (BukkitTask task : endGameCountdownTasks.values()) {
            if (task != null) task.cancel();
        }

        endGameCountdownTasks.clear();
    }

    private void cancelEndGameCountdownStartDelayTask() {
        if (endGameCountdownStartDelayTask != null) {
            endGameCountdownStartDelayTask.cancel();
            endGameCountdownStartDelayTask = null;
        }
    }

    private void cancelArenaResetDelayTask() {
        if (arenaResetDelayTask != null) {
            arenaResetDelayTask.cancel();
            arenaResetDelayTask = null;
        }
    }


    private void cancelBeginGameCountdownTask() {
        if (beginGameCountdownTask != null) {
            beginGameCountdownTask.cancel();
            beginGameCountdownTask = null;
        }
    }

    private void cancelWaitingForPlayersTask() {
        if (waitingForPlayersTask != null) {
            waitingForPlayersTask.cancel();
            waitingForPlayersTask = null;
        }
    }

    private void cancelItemDistributionTask() {
        if (itemDistributionTask != null) {
            itemDistributionTask.cancel();
            itemDistributionTask = null;
        }
    }

}
