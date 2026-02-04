package org.example.pillars;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.example.pillars.entities.Arena;
import org.example.pillars.entities.PlayerRecord;
import org.example.pillars.enums.GameState;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class GameSession {
    private final Arena arena;
    private final PillarsPlugin pillarsPlugin;

    private final List<UUID> activePlayers = new ArrayList<>();
    private final Set<UUID> spectators = new HashSet<>();
    private final Map<UUID, Location> frozenPlayers = new HashMap<>();
    private final Map<UUID, UUID> lastDamagerMap = new HashMap<>();

    private BukkitTask countdownTask;
    private BukkitTask itemGivingTask;

    private GameState state = GameState.WAITING;

    private boolean resetInProgress = false;
    private boolean forceStart = false;

    private static final int MIN_PLAYERS = 2;

    public Arena getArena() {
        return arena;
    }

    public GameState getState() {
        return state;
    }

    public GameSession(Arena arena, PillarsPlugin pillarsPlugin) {
        this.arena = arena;
        this.pillarsPlugin = pillarsPlugin;
    }

    public void playerJoin(Player player) {

        if (!canJoin()) {
            pillarsPlugin.getHudManager().sendGameAlreadyStartedTitle(player);
            return;
        }

        preparePlayer(player);
        addActivePlayer(player);
        teleportToSpawn(player);

        updateScoreboards();
        tryStartCountdown();
    }

    public void playerLeave(Player player) {

        UUID uuid = player.getUniqueId();

        boolean wasActive = activePlayers.remove(uuid);
        spectators.remove(uuid);

        cleanupArenaSpawnPoint(frozenPlayers.remove(uuid));
        lastDamagerMap.remove(uuid);

        teleportToLobby(player);

        if (wasActive) {
            evaluateGameEnd();
        }

        if (shouldResetGame()) {
            resetGame();
        }
    }

    public void playerDeath(Player dead, Player killer) {
        if (!activePlayers.contains(dead.getUniqueId())) return;

        rewardKiller(killer);

        moveToSpectator(dead);

        pillarsPlugin.getSoundManager().playLoseSound(dead);

        evaluateGameEnd();
    }

    public void playerDisconnect(Player player) {
        UUID uuid = player.getUniqueId();

        if (!activePlayers.contains(uuid) && !spectators.contains(uuid)) return;

        boolean wasActive = activePlayers.contains(uuid);

        removeFromSession(player);

        if (state == GameState.RUNNING && wasActive) {
            evaluateGameEnd();
        }
    }

    private void removeFromSession(Player player) {
        UUID uuid = player.getUniqueId();

        activePlayers.remove(uuid);
        spectators.remove(uuid);

        pillarsPlugin.getTeleportManager().teleportToLobby(player);
        pillarsPlugin.getPlayerManager().resetPlayerState(player);
        pillarsPlugin.getHudManager().resetScoreboard(player);
    }

    public void forceStart() {
        if (state != GameState.WAITING) {
            return;
        }

        forceStart = true;

        if (countdownTask == null) {
            startCountdownTask();
        }
    }

    private void tryStartCountdown() {

        if (state != GameState.WAITING) return;

        if ((activePlayers.size() >= MIN_PLAYERS || forceStart) && countdownTask == null) {
            startCountdownTask();
        }
    }

    private void handleGameEnd(Player winner) {
        if (state == GameState.ENDING || state == GameState.RESETTING) return;

        stopWorldBorder();
        cancelItemGivingTask();
        cancelCountdown();

        state = GameState.ENDING;

        Set<UUID> allPlayersSnapshot = new HashSet<>(activePlayers);
        allPlayersSnapshot.addAll(spectators);

        if (winner != null) {
            pillarsPlugin.getStatsManager().incrementWins(winner.getUniqueId());
            PlayerRecord winnerStats = pillarsPlugin.getStatsManager().getStats(winner.getUniqueId());

            pillarsPlugin.getHudManager().updateScoreboard(
                    winner,
                    getActivePlayers().size(),
                    getArena().getSpawnPoints().size(),
                    getArena().getDisplayName(),
                    winnerStats.getKills(),
                    winnerStats.getWins()
            );

            for (UUID uuid : allPlayersSnapshot) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    pillarsPlugin.getSoundManager().playWinSound(player);
                    pillarsPlugin.getHudManager().sendWinnerTitle(player, winner.getName());
                }
            }
        } else {
            for (UUID uuid : allPlayersSnapshot) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    pillarsPlugin.getSoundManager().playLoseSound(player);
                    pillarsPlugin.getHudManager().sendNoWinnerTitle(player);
                }
            }
        }

        Bukkit.getScheduler().runTaskLater(pillarsPlugin, () -> {
            for (UUID uuid : allPlayersSnapshot) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    startReturnToLobbyWithTitleTask(player, 5);
                }
            }

            Bukkit.getScheduler().runTaskLater(pillarsPlugin, this::resetGame, 120L);
        }, 60L);
    }

    private void evaluateGameEnd() {

        if (state != GameState.RUNNING) return;

        if (activePlayers.size() > 1) return;

        Player winner = activePlayers.isEmpty()
                ? null
                : Bukkit.getPlayer(activePlayers.get(0));

        handleGameEnd(winner);
    }


    private void teleportToLobby(Player player) {
        if (!player.isOnline()) return;

        pillarsPlugin.getTeleportManager().teleportToLobby(player);
        pillarsPlugin.getPlayerManager().resetPlayerState(player);
        pillarsPlugin.getHudManager().resetScoreboard(player);
    }


    private void rewardKiller(Player killer) {

        if (killer == null) return;

        pillarsPlugin.getStatsManager()
                .incrementKills(killer.getUniqueId());

        updateScoreboardFor(killer);
    }

    private void moveToSpectator(Player player) {

        UUID uuid = player.getUniqueId();

        activePlayers.remove(uuid);
        spectators.add(uuid);

        frozenPlayers.remove(uuid);
        lastDamagerMap.remove(uuid);

        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(arena.getSpectatorCenter());

        pillarsPlugin.getHudManager().sendSpectatorTitle(player);
    }

    private void teleportToSpawn(Player player) {
        Location spawn = getFarthestSpawn();
        prepareArenaSpawnPoint(spawn);

        pillarsPlugin.getTeleportManager()
                .teleportToSpawnPoint(player, spawn);

        frozenPlayers.put(
                player.getUniqueId(),
                spawn.clone().add(0.5, 1, 0.5)
        );
    }

    private void resetGame() {
        if (resetInProgress) return;

        resetInProgress = true;
        state = GameState.RESETTING;

        resetSession();
        resetArenaInternal();
    }

    private void resetArenaInternal() {
        pillarsPlugin.getArenaManager().resetArena(arena, () -> {
            state = GameState.WAITING;
            resetInProgress = false;
        });
    }

    private void resetSession() {
        stopSessionTasks();
        resetPlayers(); // TODO reset players before they get teleported back to the lobby
        clearSessionState();
    }


    private void resetPlayers() {
        for (UUID uuid : getAllPlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;

            resetSinglePlayer(player);
        }
    }

    private void clearSessionState() {
        frozenPlayers.clear();
        activePlayers.clear();
        spectators.clear();
        lastDamagerMap.clear();
        forceStart = false;
    }

    private void stopSessionTasks() {
        cancelCountdown();
        cancelItemGivingTask();
        stopWorldBorder();
    }

    private void resetSinglePlayer(Player player) {
        pillarsPlugin.getTeleportManager().teleportToLobby(player);
        pillarsPlugin.getPlayerManager().resetPlayerState(player);
        pillarsPlugin.getHudManager().resetScoreboard(player);
    }

    private void updateScoreboards() {
        for (UUID uuid : getAllPlayers()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                updateScoreboardFor(player);
            }
        }
    }

    private void updateScoreboardFor(Player player) {

        PlayerRecord stats =
                pillarsPlugin.getStatsManager().getStats(player.getUniqueId());

        pillarsPlugin.getHudManager().updateScoreboard(
                player,
                activePlayers.size(),
                arena.getSpawnPoints().size(),
                arena.getDisplayName(),
                stats.getKills(),
                stats.getWins()
        );
    }

    private boolean canJoin() {
        return state != GameState.RUNNING && state != GameState.RESETTING;
    }

    private boolean shouldResetGame() {
        return activePlayers.isEmpty() && state != GameState.RUNNING;
    }

    private void preparePlayer(Player player) {
        pillarsPlugin.getPlayerManager().resetPlayerState(player);
    }

    private void prepareArenaSpawnPoint(Location spawn) {
        if (spawn != null) spawn.getBlock().setType(Material.BEDROCK);
    }

    private void cleanupArenaSpawnPoint(Location spawn) {
        if (spawn != null && spawn.getBlock().getType() == Material.BEDROCK) {
            spawn.getBlock().setType(Material.AIR);
        }
    }

    private Location getFarthestSpawn() {
        List<Location> spawns = arena.getSpawnPoints();
        if (spawns.isEmpty()) return null;

        if (activePlayers.isEmpty()) {
            return spawns.get(ThreadLocalRandom.current().nextInt(spawns.size()));
        }

        Location bestSpawn = null;
        double bestMinDistanceSq = -1;

        for (Location candidate : spawns) {
            double cx = candidate.getX() + 0.5;
            double cz = candidate.getZ() + 0.5;
            double minDistanceSq = Double.MAX_VALUE;

            for (UUID uuid : activePlayers) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !candidate.getWorld().equals(p.getWorld())) continue;

                Location pLoc = p.getLocation();
                double dx = cx - pLoc.getX();
                double dz = cz - pLoc.getZ();
                minDistanceSq = Math.min(minDistanceSq, dx * dx + dz * dz);
            }

            if (minDistanceSq > bestMinDistanceSq) {
                bestMinDistanceSq = minDistanceSq;
                bestSpawn = candidate;
            }
        }

        return bestSpawn;
    }

    public List<Player> getActivePlayers() {
        List<Player> players = new ArrayList<>();
        for (UUID uuid : activePlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) players.add(p);
        }
        return players;
    }

    public Collection<UUID> getAllPlayers() {
        Set<UUID> all = new HashSet<>(activePlayers);
        all.addAll(spectators);
        return all;
    }

    private void addActivePlayer(Player player) {
        activePlayers.add(player.getUniqueId());
    }

    public boolean hasPlayer(Player player) {
        return getAllPlayers().contains(player.getUniqueId());
    }

    public boolean isPlayerFrozen(Player player) {
        return frozenPlayers.containsKey(player.getUniqueId());
    }

    public Location getFrozenPlayerLocation(Player player) {
        return frozenPlayers.get(player.getUniqueId());
    }

    public void setLastDamager(UUID victim, UUID damager) {
        lastDamagerMap.put(victim, damager);
    }

    public UUID getLastDamager(UUID victim) {
        return lastDamagerMap.get(victim);
    }

    public void startCountdownTask() {
        if (countdownTask != null) return;

        final int[] counter = {5};

        countdownTask = Bukkit.getScheduler().runTaskTimer(pillarsPlugin, () -> {
            if (!forceStart && activePlayers.size() < MIN_PLAYERS) {
                cancelCountdown();

                for (UUID uuid : activePlayers) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null) {
                        pillarsPlugin.getHudManager().sendNotEnoughPlayersTitle(player);
                    }
                }

                return;
            }

            for (UUID uuid : activePlayers) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    pillarsPlugin.getHudManager().sendCountdownTitle(player, counter[0]);
                    pillarsPlugin.getSoundManager().playCountdownTickSound(player);
                }
            }

            counter[0]--;
            if (counter[0] < 0) {
                state = GameState.RUNNING;
                frozenPlayers.clear();
                cancelCountdown();

                for (UUID uuid : activePlayers) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null) {
                        pillarsPlugin.getHudManager().sendGameStartTitle(player);
                        pillarsPlugin.getSoundManager().playGameStartSound(player);
                    }
                }

                startItemGivingTask();
                startWorldBorder();
            }
        }, 0L, 20L);
    }

    private void startItemGivingTask() {
        if (itemGivingTask != null) return;

        final int interval = getArena().getItemCooldownSeconds();
        final int[] counter = {interval};

        itemGivingTask = Bukkit.getScheduler().runTaskTimer(pillarsPlugin, () -> {
            for (UUID uuid : activePlayers) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    pillarsPlugin.getHudManager().sendItemCooldown(player, counter[0]);
                }
            }

            if (counter[0] == interval) {
                for (UUID uuid : activePlayers) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null) {
                        pillarsPlugin.getItemManager().giveRandomItem(player);
                        pillarsPlugin.getSoundManager().playItemGivenSound(player);
                    }
                }
            }

            counter[0]--;
            if (counter[0] <= 0) counter[0] = interval;
        }, 0L, 20L);
    }

    private void startReturnToLobbyWithTitleTask(Player player, int seconds) {
        final int[] timeLeft = {seconds};

        BukkitTask[] taskHolder = new BukkitTask[1];

        taskHolder[0] = Bukkit.getScheduler().runTaskTimer(pillarsPlugin, () -> {
            if (timeLeft[0] > 0) {
                pillarsPlugin.getHudManager().sendReturnToLobbyTitle(player, timeLeft[0]);
                timeLeft[0]--;
            } else {
                pillarsPlugin.getTeleportManager().teleportToLobby(player);

                if (taskHolder[0] != null) {
                    taskHolder[0].cancel();
                }
            }
        }, 0L, 20L);
    }

    public void startWorldBorder() {
        if (arena.getSpawnPoints().isEmpty()) return;
        World world = arena.getSpawnPoints().get(0).getWorld();
        if (world == null) return;

        Location center = arena.getCenter();
        WorldBorder border = world.getWorldBorder();

        double maxDistance = 0;
        for (Location spawn : arena.getSpawnPoints()) {
            double dx = Math.abs(spawn.getX() - center.getX());
            double dz = Math.abs(spawn.getZ() - center.getZ());
            maxDistance = Math.max(maxDistance, Math.max(dx, dz));
        }

        double initialSize = (maxDistance + 4) * 2;
        border.setCenter(center);
        border.setSize(initialSize);
        border.setDamageAmount(1.0);
        border.setDamageBuffer(0);
        border.setWarningDistance(0);
        border.setWarningTime(0);

        double minSize = 4 * 2;
        long shrinkTimeSeconds = 180;
        border.setSize(minSize, shrinkTimeSeconds);
    }

    private void cancelCountdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
    }

    private void cancelItemGivingTask() {
        if (itemGivingTask != null) {
            itemGivingTask.cancel();
            itemGivingTask = null;
        }
    }

    private void stopWorldBorder() {
        if (!arena.getSpawnPoints().isEmpty()) {
            World world = arena.getSpawnPoints().get(0).getWorld();
            if (world != null) {
                WorldBorder border = world.getWorldBorder();
                border.setSize(1000);
                border.setCenter(new Location(world, 0, 64, 0));
                border.setDamageAmount(0.2);
                border.setDamageBuffer(5);
            }
        }
    }


}
