package org.example.pillars;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
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

    private BukkitTask beginGameCountdownTask;
    private BukkitTask itemDistributionTask;
    private BukkitTask witherCountdownTask;
    private BukkitTask witherEffectTask;

    private final Map<UUID, BukkitTask> endGameCountdownTasks = new HashMap<>();

    private GameState state = GameState.WAITING;

    private boolean resetInProgress = false;
    private boolean forceStart = false;

    private static final int MIN_PLAYERS = 2;


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
        startBeginGameCountdown();
    }

    public void playerLeave(Player player) {
        UUID uuid = player.getUniqueId();

        BukkitTask task = endGameCountdownTasks.remove(uuid);
        if (task != null) task.cancel();

        cleanupArenaSpawnPoint(frozenPlayers.remove(uuid));

        activePlayers.remove(uuid);
        spectators.remove(uuid);
        lastDamagerMap.remove(uuid);

        resetSinglePlayer(player);

        if (state == GameState.RUNNING) {
            evaluateGameEnd();
        }
    }

    public void playerDisconnect(Player player) {
        UUID uuid = player.getUniqueId();

        BukkitTask task = endGameCountdownTasks.remove(uuid);
        if (task != null) task.cancel();

        if (!activePlayers.contains(uuid) && !spectators.contains(uuid)) return;

        boolean wasActive = activePlayers.contains(uuid);

        removePlayerFromSession(player);

        if (state == GameState.RUNNING && wasActive) {
            evaluateGameEnd();
        }
    }

    public void playerDeath(Player dead, Player killer) {
        UUID uuid = dead.getUniqueId();
        if (!activePlayers.contains(uuid)) return;

        BukkitTask endGameCountdownTask = endGameCountdownTasks.remove(uuid);
        if (endGameCountdownTask != null) {
            endGameCountdownTask.cancel();
        }

        rewardKiller(killer);

        setPlayerAsSpectator(dead);

        pillarsPlugin.getSoundManager().playLoseSound(dead);

        evaluateGameEnd();
    }

    private void removePlayerFromSession(Player player) {
        UUID uuid = player.getUniqueId();

        activePlayers.remove(uuid);
        spectators.remove(uuid);

        resetSinglePlayer(player);
    }

    public void forceStart() {
        if (state != GameState.WAITING) {
            return;
        }

        forceStart = true;

        if (beginGameCountdownTask == null) {
            startBeginGameCountdown();
        }
    }

    private void handleGameEnd(Player winner) {
        if (state == GameState.ENDING || state == GameState.RESETTING) return;

        stopSessionTasks();
        state = GameState.ENDING;

        Set<UUID> allPlayersSnapshot = new HashSet<>(activePlayers);
        allPlayersSnapshot.addAll(spectators);

        for (UUID uuid : new ArrayList<>(activePlayers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.setGameMode(GameMode.SPECTATOR);
                player.teleport(arena.getSpectatorCenter());
                pillarsPlugin.getHudManager().sendSpectatorTitle(player);
            }
            spectators.add(uuid);
        }
        activePlayers.clear();

        if (winner != null) {
            pillarsPlugin.getStatsManager().incrementWins(winner.getUniqueId());
            PlayerRecord stats = pillarsPlugin.getStatsManager().getStats(winner.getUniqueId());

            pillarsPlugin.getHudManager().updateScoreboard(
                    winner,
                    0,
                    arena.getSpawnPoints().size(),
                    arena.getDisplayName(),
                    stats.getKills(),
                    stats.getWins()
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
                if (player != null) startEndGameCountdown(player, 5);
            }
        }, 40L); // 40 ticks = 2 seconds

        Bukkit.getScheduler().runTaskLater(pillarsPlugin, this::resetGame, 160L);
        // 160 ticks = 8 seconds
    }

    public void startEndGameCountdown(Player player, int seconds) {
        final int[] timeLeft = {seconds};

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(pillarsPlugin, () -> {
            if (!player.isOnline()) {
                BukkitTask t = endGameCountdownTasks.remove(player.getUniqueId());
                if (t != null) t.cancel();
                spectators.remove(player.getUniqueId());
                return;
            }

            if (timeLeft[0] > 0) {
                pillarsPlugin.getHudManager().sendReturnToLobbyTitle(player, timeLeft[0]);
                timeLeft[0]--;
            } else {
                player.setGameMode(GameMode.SURVIVAL);
                pillarsPlugin.getTeleportManager().teleportToLobby(player);
                pillarsPlugin.getPlayerManager().resetPlayerState(player);
                pillarsPlugin.getHudManager().resetScoreboard(player);

                spectators.remove(player.getUniqueId());

                BukkitTask t = endGameCountdownTasks.remove(player.getUniqueId());
                if (t != null) t.cancel();
            }
        }, 0L, 20L);

        endGameCountdownTasks.put(player.getUniqueId(), task);
    }


    private void evaluateGameEnd() {
        if (state != GameState.RUNNING) return;

        if (activePlayers.isEmpty()) {
            handleGameEnd(null);
            return;
        }

        if (activePlayers.size() == 1) {
            Player winner = Bukkit.getPlayer(activePlayers.getFirst());
            handleGameEnd(winner);
        }
    }

    private void rewardKiller(Player killer) {

        if (killer == null) return;

        pillarsPlugin.getStatsManager()
                .incrementKills(killer.getUniqueId());

        updatePlayerScoreboard(killer);
    }

    private void setPlayerAsSpectator(Player player) {
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
        clearSessionState();
    }


    private void clearSessionState() {
        frozenPlayers.clear();
        activePlayers.clear();
        spectators.clear();
        lastDamagerMap.clear();
        endGameCountdownTasks.clear();
        forceStart = false;
    }

    private void stopSessionTasks() {
        cancelBeginGameCountdownTask();
        cancelItemDistributionTask();
        cancelWitherTask();
        cancelFinalZoneTask();

        cancelEndGameCountdownTasks();

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
                updatePlayerScoreboard(player);
            }
        }
    }

    private void updatePlayerScoreboard(Player player) {

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

    private boolean canJoin() {
        return state != GameState.RUNNING && state != GameState.RESETTING && state != GameState.ENDING;
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

    public Arena getArena() {
        return arena;
    }

    public GameState getState() {
        return state;
    }

    public void startBeginGameCountdown() {
        if (state != GameState.WAITING) return;
        if ((activePlayers.size() < MIN_PLAYERS && !forceStart) || beginGameCountdownTask != null) return;

        state = GameState.STARTING;
        final int[] counter = {5};

        beginGameCountdownTask = Bukkit.getScheduler().runTaskTimer(pillarsPlugin, () -> {
            if (activePlayers.isEmpty()) {
                cancelBeginGameCountdownTask();
                return;
            }

            if (!forceStart && activePlayers.size() < MIN_PLAYERS) {
                cancelBeginGameCountdownTask();

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
                cancelBeginGameCountdownTask();

                for (Player player : getActivePlayers()) {
                    pillarsPlugin.getHudManager().sendGameStartTitle(player);
                    pillarsPlugin.getSoundManager().playGameStartSound(player);
                }

                startItemDistributionTask();
                startWorldBorder();
            }
        }, 0L, 20L);
    }

    private void startItemDistributionTask() {
        if (itemDistributionTask != null) return;

        final int interval = getArena().getItemCooldownSeconds();
        final int[] counter = {interval};

        itemDistributionTask = Bukkit.getScheduler().runTaskTimer(pillarsPlugin, () -> {
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


    private void startWitherCountdown() {
        if (state != GameState.RUNNING) return;

        final int countdownSeconds = 5;
        final int[] counter = {countdownSeconds};

        witherCountdownTask = Bukkit.getScheduler().runTaskTimer(pillarsPlugin, () -> {

            if (activePlayers.isEmpty()) {
                cancelFinalZoneTask();
                return;
            }

            for (Player player : getActivePlayers()) {
                pillarsPlugin.getHudManager().sendWitherCountdownTitle(player, counter[0]);
                pillarsPlugin.getSoundManager().playCountdownTickSound(player);
            }

            counter[0]--;

            if (counter[0] < 0) {
                cancelFinalZoneTask();
                for (Player player : getActivePlayers()) {
                    pillarsPlugin.getHudManager().sendWitherStartTitle(player);
                    pillarsPlugin.getSoundManager().playWitherStartSound(player);

                }
                startWitherTask();
            }

        }, 0L, 20L);
    }

    private void startWitherTask() {
        if (state != GameState.RUNNING) return;

        witherEffectTask = Bukkit.getScheduler().runTaskTimer(pillarsPlugin, () -> {

            if (activePlayers.isEmpty()) {
                cancelWitherTask();
                return;
            }

            for (Player player : getActivePlayers()) {

                player.addPotionEffect(new PotionEffect(
                        PotionEffectType.WITHER,
                        40, // 2 seconds
                        1,
                        false,
                        true
                ));
            }

        }, 0L, 40L);
    }

    public void startWorldBorder() {
        if (arena.getSpawnPoints().isEmpty()) return;
        World world = arena.getSpawnPoints().getFirst().getWorld();
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

        double minSize = 1;
        long shrinkTimeSeconds = 300;
        border.setSize(minSize, shrinkTimeSeconds);

        Bukkit.getScheduler().runTaskLater(
                pillarsPlugin,
                this::startWitherCountdown,
                shrinkTimeSeconds * 20L
        );
    }

    private void cancelEndGameCountdownTasks() {
        for (BukkitTask task : endGameCountdownTasks.values()) {
            if (task != null) task.cancel();
        }

        endGameCountdownTasks.clear();
    }


    private void cancelWitherTask() {
        if (witherEffectTask != null) {
            witherEffectTask.cancel();
            witherEffectTask = null;
        }
    }

    private void cancelFinalZoneTask() {
        if (witherCountdownTask != null) {
            witherCountdownTask.cancel();
            witherCountdownTask = null;
        }
    }


    private void cancelBeginGameCountdownTask() {
        if (beginGameCountdownTask != null) {
            beginGameCountdownTask.cancel();
            beginGameCountdownTask = null;
        }
    }

    private void cancelItemDistributionTask() {
        if (itemDistributionTask != null) {
            itemDistributionTask.cancel();
            itemDistributionTask = null;
        }
    }

    private void stopWorldBorder() {
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


}
