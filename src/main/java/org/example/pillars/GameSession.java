package org.example.pillars;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.example.pillars.entities.Arena;
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

    private final HudManager hudManager;
    private final PlayerManager playerManager;
    private final StatsManager statsManager;
    private final SpawnManager spawnManager;
    private final SoundManager soundManager;
    private final TeleportManager teleportManager;
    private final ItemManager itemManager;
    private final ArenaManager arenaManager;
    private final JavaPlugin plugin;
    private final GameEventManager gameEventManager;

    private BukkitTask beginGameCountdownTask;
    private BukkitTask waitingForPlayersTask;
    private BukkitTask itemDistributionTask;
    private BukkitTask worldBorderShrinkTask;
    private BukkitTask finalEventStartDelayTask;
    private BukkitTask endGameCountdownStartDelayTask;
    private BukkitTask arenaResetDelayTask;

    private final Map<UUID, BukkitTask> endGameCountdownTasks = new HashMap<>();

    private GameState state = GameState.WAITING;

    private boolean resetInProgress = false;
    private boolean forceStart = false;
    private ArenaGameMode activeGameMode;
    private int activeItemIntervalSeconds;
    private long borderShrinkEndTimeMillis = -1L;
    private double borderShrinkBlocksPerSecond = 0.0;

    private final int beginCountdownSeconds;
    private final int endGameLobbyCountdownSeconds;
    private final long endGameSpectatorDelayTicks;
    private final long arenaResetDelayTicks;
    private final double borderMinSize;
    private final double borderSpawnPaddingBlocks;
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
        this.hudManager = hudManager;
        this.playerManager = playerManager;
        this.statsManager = statsManager;
        this.spawnManager = spawnManager;
        this.soundManager = soundManager;
        this.teleportManager = teleportManager;
        this.itemManager = itemManager;
        this.arenaManager = arenaManager;
        this.arena = arena;
        this.gameEventManager = new GameEventManager(plugin, this, hudManager, soundManager, itemManager);

        this.beginCountdownSeconds = Math.max(1, plugin.getConfig().getInt("settings.beginCountdownSeconds", 5));
        this.endGameLobbyCountdownSeconds = Math.max(1, plugin.getConfig().getInt("settings.endGameLobbyCountdownSeconds", 5));
        this.endGameSpectatorDelayTicks = Math.max(0L, plugin.getConfig().getLong("settings.endGameSpectatorDelayTicks", 40L));
        this.arenaResetDelayTicks = Math.max(1L, plugin.getConfig().getLong("settings.arenaResetDelayTicks", 160L));
        this.borderMinSize = Math.max(1.0, plugin.getConfig().getDouble("settings.borderMinSize", 1.0));
        this.borderSpawnPaddingBlocks = Math.max(1.0, plugin.getConfig().getDouble("settings.borderSpawnPaddingBlocks", 10.0));
        this.spawnPillarHeightBlocks = Math.max(1, Math.min(
                64,
                plugin.getConfig().getInt("settings.spawnPillarHeightBlocks", 5)
        ));
        this.lobbyWorldName = plugin.getConfig().getString("settings.lobbyWorldName", "world");
    }

    public void playerJoin(Player player) {
        if (!canJoin()) {
            if (arena.getSpawnPoints() == null || arena.getSpawnPoints().isEmpty()) {
                hudManager.sendArenaConfigurationError(player);
            } else if (!arena.isJoiningOpen()) {
                hudManager.sendArenaClosed(player, arena.getDisplayName());
            } else if (activePlayers.size() >= arena.getSpawnPoints().size()) {
                hudManager.sendNoSpawnAvailable(player);
            } else {
                hudManager.sendGameAlreadyStartedTitle(player);
            }
            return;
        }

        playerManager.resetPlayerState(player);
        if (!teleportToSpawn(player)) {
            playerManager.resetAndReturnToLobby(player, lobbyWorldName);
            return;
        }
        addActivePlayer(player);
        if (state == GameState.WAITING) {
            playerManager.giveLeaveArenaItem(player);
            playerManager.giveForceStartItem(player);
            playerManager.giveAdminMenuItem(player);
            playerManager.giveCurrentArenaSettingsItem(player, arena.getDisplayName());
        }
        hudManager.broadcastPlayerJoinedArena(player, arena.getDisplayName(), activePlayers.size(), arena.getSpawnPoints().size());

        updateArenaHudForAllPlayers();

        var stats = statsManager.getStats(player.getUniqueId());
        hudManager.updatePlayerScoreboard(player, getArenaPlayerCount(), arena.getSpawnPoints().size(), state, arena.getDisplayName(), stats.getKills(), stats.getWins(), stats.getGamesPlayed());
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
                adminSpectators.remove(uuid);
                adminSpectatorPreviousLocations.remove(uuid);
                adminSpectatorPreviousGameModes.remove(uuid);
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
            luckyBlocks.removeAll(spawnManager.cleanupSpawn(spawn, spawnPillarHeightBlocks));

            if (state == GameState.STARTING && activePlayers.size() < getMinPlayers()) {
                cancelBeginGameCountdownTask();
                state = GameState.WAITING;
                forceStart = false;
                giveWaitingItems();
                updateArenaHudForAllPlayers();
                startWaitingForPlayersTask();
            }
        }

        playerManager.resetAndReturnToLobby(player, lobbyWorldName);
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
        if (state != GameState.RUNNING) {
            hudManager.sendArenaSpectateUnavailable(player);
            return false;
        }

        if (activePlayers.contains(player.getUniqueId())) {
            hudManager.sendCannotSpectateOwnGame(player);
            return false;
        }

        adminSpectators.add(player.getUniqueId());
        adminSpectatorPreviousLocations.put(player.getUniqueId(), player.getLocation());
        adminSpectatorPreviousGameModes.put(player.getUniqueId(), player.getGameMode());
        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(arena.getSpectatorCenter());
        updateArenaHudForAllPlayers();
        hudManager.sendAdminSpectatorJoined(player, arena.getDisplayName());
        return true;
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
        hudManager.broadcastElimination(dead, killer, arena.getDisplayName(), cause);
        gameEventManager.onPlayerEliminated(dead, killer);

        setPlayerAsSpectator(dead);

        soundManager.playLoseSound(dead);

        evaluateGameEnd();
    }

    public boolean forceStart(Player startedBy) {
        if (state != GameState.WAITING) {
            return false;
        }

        forceStart = true;
        hudManager.broadcastForceStartedArena(startedBy, arena.getDisplayName());

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
                player.setGameMode(GameMode.SPECTATOR);
                player.teleport(arena.getSpectatorCenter());
                hudManager.sendSpectatorTitle(player);
            }
            spectators.add(uuid);
        }
        activePlayers.clear();

        if (winner != null) {
            statsManager.incrementWins(winner.getUniqueId());
            hudManager.updatePlayerStats(winner, statsManager.getStats(winner.getUniqueId()).getKills(), statsManager.getStats(winner.getUniqueId()).getWins());
            hudManager.broadcastWinner(winner.getName(), arena.getDisplayName());
            for (UUID uuid : allPlayersSnapshot) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    soundManager.playWinSound(player);
                    hudManager.sendWinnerTitle(player, winner.getName());
                }
            }
        } else {
            hudManager.broadcastNoWinner(arena.getDisplayName());
            for (UUID uuid : allPlayersSnapshot) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    soundManager.playLoseSound(player);
                    hudManager.sendNoWinnerTitle(player);
                }
            }
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
                hudManager.sendReturnToLobbyTitle(player, timeLeft[0]);
                timeLeft[0]--;
            } else {
                player.setGameMode(GameMode.SURVIVAL);
                playerManager.resetAndReturnToLobby(player, lobbyWorldName);

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

        UUID uuid = killer.getUniqueId();
        statsManager.incrementKills(uuid);

        int kills = statsManager.getStats(uuid).getKills();
        int wins = statsManager.getStats(uuid).getWins();
        hudManager.updatePlayerStats(killer, kills, wins);
    }

    private void setPlayerAsSpectator(Player player) {
        UUID uuid = player.getUniqueId();

        activePlayers.remove(uuid);
        spectators.add(uuid);
        itemManager.clearRecentItems(List.of(uuid));

        frozenPlayers.remove(uuid);
        lastDamagerMap.remove(uuid);

        playerManager.prepareSpectatorInventory(player);
        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(arena.getSpectatorCenter());

        hudManager.sendSpectatorTitle(player);
    }

    private boolean teleportToSpawn(Player player) {
        if (arena.getSpawnPoints() == null || arena.getSpawnPoints().isEmpty()) {
            hudManager.sendArenaConfigurationError(player);
            return false;
        }

        Location spawn = spawnManager.getFarthestSpawn(arena, getActivePlayerIds(), occupiedSpawns.values());
        if (spawn == null) {
            hudManager.sendNoSpawnAvailable(player);
            return false;
        }

        Material pillarMaterial = arena.getGameMode() == ArenaGameMode.LUCKY_BLOCKS
                ? Material.YELLOW_GLAZED_TERRACOTTA
                : Material.BEDROCK;
        List<Location> pillarBlocks = spawnManager.prepareSpawn(
                spawn,
                spawnPillarHeightBlocks,
                pillarMaterial
        );
        if (arena.getGameMode() == ArenaGameMode.LUCKY_BLOCKS) {
            luckyBlocks.addAll(pillarBlocks);
        }

        occupiedSpawns.put(player.getUniqueId(), spawn);

        Location teleportedLoc = spawn.clone().add(0.5, 1, 0.5);
        teleportManager.teleportToSpawnPoint(player, teleportedLoc);

        frozenPlayers.put(player.getUniqueId(), teleportedLoc);
        return true;
    }

    private void resetGame() {
        if (resetInProgress) return;

        resetInProgress = true;
        state = GameState.RESETTING;
        updateArenaHudForAllPlayers();

        resetSession();
        resetArenaInternal();
    }

    public ArenaResetResult resetArenaManually(Runnable completionCallback) {
        if (resetInProgress) return ArenaResetResult.ALREADY_RESETTING;
        if (Bukkit.getWorld(lobbyWorldName) == null) return ArenaResetResult.LOBBY_UNAVAILABLE;

        Set<UUID> participants = new HashSet<>(activePlayers);
        participants.addAll(spectators);

        resetInProgress = true;
        state = GameState.RESETTING;
        updateArenaHudForAllPlayers();

        restoreAdminSpectators();

        Set<Player> playersToReturn = new HashSet<>();
        for (UUID uuid : participants) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                playersToReturn.add(player);
            }
        }

        World arenaWorld = Bukkit.getWorld(arena.getWorldName());
        if (arenaWorld != null) {
            playersToReturn.addAll(arenaWorld.getPlayers());
        }

        for (Player player : playersToReturn) {
            playerManager.resetAndReturnToLobby(player, lobbyWorldName);
        }

        resetSession();
        resetArenaInternal(completionCallback);
        return ArenaResetResult.STARTED;
    }

    private void resetArenaInternal() {
        resetArenaInternal(null);
    }

    private void resetArenaInternal(Runnable completionCallback) {
        arenaManager.resetArena(arena, () -> {
            state = GameState.WAITING;
            resetInProgress = false;
            if (completionCallback != null) {
                completionCallback.run();
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
        cancelFinalEventStartDelayTask();
        cancelEndGameCountdownStartDelayTask();
        cancelArenaResetDelayTask();
        gameEventManager.stop();

        cancelEndGameCountdownTasks();

        stopWorldBorder();
    }

    private boolean canJoin() {
        return state != GameState.RUNNING
                && state != GameState.RESETTING
                && state != GameState.ENDING
                && arena.isJoiningOpen()
                && arena.getSpawnPoints() != null
                && activePlayers.size() < arena.getSpawnPoints().size();
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
                ? arena.getFloorY() - ArenaManager.FLOOR_ELIMINATION_MARGIN
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
        hudManager.updateArenaInfoForAllPlayers(
                getAllPlayerIds(),
                getArenaPlayerCount(),
                arena.getSpawnPoints().size(),
                state,
                arena.getDisplayName()
        );
    }

    private int getArenaPlayerCount() {
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
                        hudManager.sendNotEnoughPlayersTitle(player);
                    }
                }

                return;
            }

            for (UUID uuid : activePlayers) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    hudManager.sendCountdownTitle(player, counter[0]);
                    soundManager.playCountdownTickSound(player);
                }
            }

            counter[0]--;
            if (counter[0] < 0) {
                for (UUID uuid : getActivePlayerIds()) statsManager.incrementGamesPlayed(uuid);
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
                    hudManager.sendGameStartTitle(player);
                    soundManager.playGameStartSound(player);
                }

                startWorldBorder();
                if (!forceStart) {
                    hudManager.broadcastGameStarted(arena.getDisplayName());
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
                    double currentBorderSize = getCurrentBorderSize();
                    hudManager.sendItemCooldown(
                             player,
                             counter[0],
                             activeGameMode,
                             arena.getItemDeliveryMode(),
                             getSecondsUntilNextBorderDecrease(currentBorderSize),
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
                        soundManager.playItemGivenSound(player);
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
        Material material = activeGameMode == ArenaGameMode.LUCKY_BLOCKS
                ? Material.YELLOW_GLAZED_TERRACOTTA
                : Material.BEDROCK;

        for (Location spawn : occupiedSpawns.values()) {
            List<Location> blocks = spawnManager.prepareSpawn(spawn, spawnPillarHeightBlocks, material);
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
                    hudManager.sendWaitingForPlayers(player, activePlayers.size(), getMinPlayers());
                }
            }
        }, 0L, 20L);
    }

    private void giveWaitingItems() {
        for (UUID uuid : activePlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                playerManager.giveLeaveArenaItem(player);
                playerManager.giveForceStartItem(player);
                playerManager.giveAdminMenuItem(player);
                playerManager.giveCurrentArenaSettingsItem(player, arena.getDisplayName());
            }
        }
    }

    private void removeWaitingItems() {
        for (UUID uuid : activePlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                playerManager.removeLeaveArenaItem(player);
                playerManager.removeForceStartItem(player);
                playerManager.removeAdminMenuItem(player);
                playerManager.removeCurrentArenaSettingsItem(player);
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

        if (previousGameMode != null) {
            player.setGameMode(previousGameMode);
        } else {
            player.setGameMode(GameMode.SURVIVAL);
        }

        if (previousLocation != null) {
            player.teleport(previousLocation);
        }

        hudManager.resetScoreboard(player);
    }

    public void startWorldBorder() {
        List<Location> spawns = arena.getSpawnPoints();
        if (spawns.isEmpty()) return;

        World world = spawns.get(0).getWorld();
        if (world == null) return;

        double sumX = 0, sumY = 0, sumZ = 0;
        for (Location spawn : spawns) {
            sumX += spawn.getX() + 0.5;
            sumY += spawn.getY() + 1;
            sumZ += spawn.getZ() + 0.5;
        }
        double avgX = sumX / spawns.size();
        double avgY = sumY / spawns.size();
        double avgZ = sumZ / spawns.size();
        Location borderCenter = new Location(world, avgX, avgY, avgZ);

        double maxAxisDistance = 0;
        for (Location spawn : spawns) {
            double dx = (spawn.getX() + 0.5) - avgX;
            double dz = (spawn.getZ() + 0.5) - avgZ;
            double axisDistance = Math.max(Math.abs(dx), Math.abs(dz));
            maxAxisDistance = Math.max(maxAxisDistance, axisDistance);
        }

        double initialSize = Math.max(
                borderMinSize,
                (maxAxisDistance + borderSpawnPaddingBlocks) * 2
        );
        WorldBorder border = world.getWorldBorder();
        border.setCenter(borderCenter);
        border.setSize(initialSize);
        border.setDamageAmount(1.0);
        border.setDamageBuffer(0);
        border.setWarningDistance(0);
        border.setWarningTime(0);

        long borderShrinkSeconds = arena.getBorderShrinkSeconds();
        borderShrinkEndTimeMillis = System.currentTimeMillis() + (borderShrinkSeconds * 1000L);
        borderShrinkBlocksPerSecond = Math.max(0.0, (initialSize - borderMinSize) / borderShrinkSeconds);
        startCalmWorldBorderShrink(border);

        finalEventStartDelayTask = Bukkit.getScheduler().runTaskLater(
                plugin,
                gameEventManager::startLastBreathEvent,
                borderShrinkSeconds * 20L
        );
    }

    private void startCalmWorldBorderShrink(WorldBorder border) {
        cancelWorldBorderShrinkTask();

        worldBorderShrinkTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long remainingMillis = Math.max(0L, borderShrinkEndTimeMillis - System.currentTimeMillis());
            double nextSize = borderMinSize + (borderShrinkBlocksPerSecond * remainingMillis / 1000.0);
            border.setSize(Math.max(borderMinSize, nextSize));

            if (remainingMillis == 0L) {
                cancelWorldBorderShrinkTask();
            }
        }, 1L, 1L);
    }


    private void cancelEndGameCountdownTasks() {
        for (BukkitTask task : endGameCountdownTasks.values()) {
            if (task != null) task.cancel();
        }

        endGameCountdownTasks.clear();
    }

    private void cancelFinalEventStartDelayTask() {
        if (finalEventStartDelayTask != null) {
            finalEventStartDelayTask.cancel();
            finalEventStartDelayTask = null;
        }
    }

    private void cancelWorldBorderShrinkTask() {
        if (worldBorderShrinkTask != null) {
            worldBorderShrinkTask.cancel();
            worldBorderShrinkTask = null;
        }
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

    private void stopWorldBorder() {
        cancelWorldBorderShrinkTask();
        borderShrinkEndTimeMillis = -1L;
        borderShrinkBlocksPerSecond = 0.0;
        if (!arena.getSpawnPoints().isEmpty()) {
            World world = arena.getSpawnPoints().getFirst().getWorld();
            if (world != null) {
                WorldBorder border = world.getWorldBorder();
                border.setSize(1000);
                border.setCenter(new Location(world, 0, 64, 0));
                border.setDamageAmount(0.2);
                border.setDamageBuffer(5);
            }
        }
    }

    private long getSecondsUntilNextBorderDecrease(double currentSize) {
        if (borderShrinkEndTimeMillis <= 0L) {
            return 0L;
        }

        if (borderShrinkBlocksPerSecond <= 0.0 || currentSize <= borderMinSize) {
            return 0L;
        }

        double visibleSize = Math.ceil(currentSize);
        double minVisibleSize = Math.ceil(borderMinSize);
        if (visibleSize <= minVisibleSize) {
            return 0L;
        }

        double nextVisibleSize = Math.max(minVisibleSize, visibleSize - 1.0);
        double seconds = (currentSize - nextVisibleSize) / borderShrinkBlocksPerSecond;
        return Math.max(1L, (long) Math.ceil(seconds));
    }

    private double getCurrentBorderSize() {
        if (arena.getSpawnPoints().isEmpty()) {
            return borderMinSize;
        }

        World world = arena.getSpawnPoints().getFirst().getWorld();
        if (world == null) {
            return borderMinSize;
        }

        return world.getWorldBorder().getSize();
    }


}
