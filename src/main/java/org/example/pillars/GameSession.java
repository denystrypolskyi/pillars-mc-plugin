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

    private BukkitTask startCountdownTask;
    private BukkitTask itemGivingTask;
    private BukkitTask worldBorderTask;

    private GameState state = GameState.WAITING;
    private boolean resetInProgress = false;

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

    public void joinPlayer(Player player) {
        if (activePlayers.contains(player.getUniqueId())) return;

        if (state == GameState.RESETTING) {
            pillarsPlugin.getHudManager().sendArenaResettingTitle(player);
            return;
        }

        if (state == GameState.RUNNING) {
            pillarsPlugin.getHudManager().sendGameAlreadyStartedTitle(player);
            return;
        }

        pillarsPlugin.getPlayerManager().resetPlayerState(player);
        activePlayers.add(player.getUniqueId());

        Location spawn = getFarthestSpawn();
        prepareArenaSpawn(spawn);
        pillarsPlugin.getTeleportManager().teleport(player, spawn);
        frozenPlayers.put(player.getUniqueId(), spawn.clone().add(0.5, 1, 0.5));

        for (UUID playerUuid : getAllPlayers()) {
            Player p = Bukkit.getPlayer(playerUuid);
            if (p != null) {
                PlayerRecord stats = pillarsPlugin.getStatsManager().getStats(p.getUniqueId());
                pillarsPlugin.getHudManager().updateScoreboard(
                        p,
                        getActivePlayers().size(),
                        getArena().getSpawnPoints().size(),
                        getArena().getDisplayName(),
                        stats.getKills(),
                        stats.getWins()
                );
            }
        }

        if (activePlayers.size() >= MIN_PLAYERS && startCountdownTask == null) {
            startCountdown();
        }
    }

    private void prepareArenaSpawn(Location spawn) {
        if (spawn != null) spawn.getBlock().setType(Material.BEDROCK);
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

    public void removePlayer(Player player) {
        UUID uuid = player.getUniqueId();

        Location spawn = frozenPlayers.get(uuid);
        cleanupPlayerSpawn(spawn);

        boolean wasActive = activePlayers.remove(uuid);
        frozenPlayers.remove(uuid);
        spectators.remove(uuid);
        lastDamagerMap.remove(uuid);

        if (player.isOnline()) {
            pillarsPlugin.getTeleportManager().teleportToLobby(player);
            pillarsPlugin.getPlayerManager().resetPlayerState(player);
            pillarsPlugin.getHudManager().resetScoreboard(player);
        }

        if (!wasActive) return;

        if (state == GameState.ENDING || state == GameState.RESETTING) {
            return;
        }

        if (state == GameState.RUNNING) {
            if (activePlayers.size() <= 1) {
                Player winner = activePlayers.isEmpty() ? null : Bukkit.getPlayer(activePlayers.get(0));
                handleGameEnd(winner);
            }
            return;
        }

        if (activePlayers.isEmpty()) {
            cancelCountdown();
            cancelItemGivingTask();
            stopWorldBorder();

            resetSessionAsync();
        }
    }

    private void cleanupPlayerSpawn(Location spawn) {
        if (spawn != null && spawn.getBlock().getType() == Material.BEDROCK) {
            spawn.getBlock().setType(Material.AIR);
        }
    }

    public void makeSpectator(Player player) {
        UUID uuid = player.getUniqueId();
        if (!activePlayers.contains(uuid)) return;
        if (state == GameState.ENDING || state == GameState.RESETTING) return;

        activePlayers.remove(uuid);
        frozenPlayers.remove(uuid);
        addSpectator(player);

        Location specLoc = arena.getSpectatorCenter();
        player.teleport(specLoc);
        player.setGameMode(GameMode.SPECTATOR);
        pillarsPlugin.getHudManager().sendSpectatorTitle(player);

        if (activePlayers.size() == 1) {
            Player winner = Bukkit.getPlayer(activePlayers.get(0));
            handleGameEnd(winner);
        } else if (activePlayers.size() < MIN_PLAYERS) {
            cancelCountdown();
            cancelItemGivingTask();
            state = GameState.WAITING;
            for (UUID u : activePlayers) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) {
                    pillarsPlugin.getHudManager().sendNotEnoughPlayersTitle(p);
                }
            }
        }
    }

    private void handleGameEnd(Player winner) {
        state = GameState.ENDING;

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
        }

        for (UUID uuid : activePlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                if (winner != null) {
                    pillarsPlugin.getHudManager().sendWinnerTitle(player, winner.getName());
                }
            }
        }

        Bukkit.getScheduler().runTaskLater(pillarsPlugin, () -> {
            for (UUID uuid : getAllPlayers()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    returnToLobbyWithTitle(player, 5);
                }
            }

            Bukkit.getScheduler().runTaskLater(pillarsPlugin, this::resetSessionAsync, 120L);
        }, 60L);
    }

    private void returnToLobbyWithTitle(Player player, int seconds) {
        final int[] timeLeft = {seconds};

        Bukkit.getScheduler().runTaskTimer(pillarsPlugin, task -> {
            if (!player.isOnline() || !getAllPlayers().contains(player.getUniqueId())) {
                task.cancel();
                return;
            }

            if (timeLeft[0] > 0) {
                pillarsPlugin.getHudManager().sendReturnToLobbyTitle(player, timeLeft[0]);
                timeLeft[0]--;
            } else {
                pillarsPlugin.getTeleportManager().teleportToLobby(player);
                if (spectators.contains(player.getUniqueId())) {
                    player.setGameMode(GameMode.SURVIVAL);
                }
                task.cancel();
            }
        }, 0L, 20L);
    }

    private void resetSessionAsync() {
        if (resetInProgress) return;
        resetInProgress = true;

        state = GameState.RESETTING;
        resetSession();

        pillarsPlugin.getArenaManager().resetArenaAsync(arena, () -> {
            state = GameState.WAITING;
            resetInProgress = false;
        });
    }

    private void resetSession() {
        cancelCountdown();
        cancelItemGivingTask();
        stopWorldBorder();
        frozenPlayers.clear();

        Set<UUID> allPlayers = new HashSet<>(activePlayers);
        allPlayers.addAll(spectators);

        for (UUID uuid : allPlayers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;

            pillarsPlugin.getTeleportManager().teleportToLobby(player);
            pillarsPlugin.getPlayerManager().resetPlayerState(player);
            pillarsPlugin.getHudManager().resetScoreboard(player);
        }

        activePlayers.clear();
        spectators.clear();
    }

    public void startCountdown() {
        if (startCountdownTask != null) return;

        final int[] counter = {5};

        startCountdownTask = Bukkit.getScheduler().runTaskTimer(pillarsPlugin, () -> {
            if (activePlayers.isEmpty()) {
                cancelCountdown();
                return;
            }

            for (UUID uuid : activePlayers) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    pillarsPlugin.getHudManager().sendCountdownTitle(player, counter[0]);
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
                    }
                }

                startItemGivingTask();
                startWorldBorder();
            }
        }, 0L, 20L);
    }

    private void cancelCountdown() {
        if (startCountdownTask != null) {
            startCountdownTask.cancel();
            startCountdownTask = null;
        }
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

    private void stopWorldBorder() {
        if (worldBorderTask != null) {
            worldBorderTask.cancel();
            worldBorderTask = null;
        }

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

    private void cancelItemGivingTask() {
        if (itemGivingTask != null) {
            itemGivingTask.cancel();
            itemGivingTask = null;
        }
    }

    public void addSpectator(Player player) {
        spectators.add(player.getUniqueId());
    }

    public List<Player> getActivePlayers() {
        List<Player> players = new ArrayList<>();
        for (UUID uuid : activePlayers) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) players.add(p);
        }
        return Collections.unmodifiableList(players);
    }

    public Collection<UUID> getAllPlayers() {
        Set<UUID> all = new HashSet<>(activePlayers);
        all.addAll(spectators);
        return all;
    }

    public boolean hasPlayer(Player player) {
        return activePlayers.contains(player.getUniqueId());
    }

    public boolean isFrozen(Player player) {
        return frozenPlayers.containsKey(player.getUniqueId());
    }

    public Location getFrozenLocation(Player player) {
        return frozenPlayers.get(player.getUniqueId());
    }

    public void setLastDamager(UUID victim, UUID damager) {
        lastDamagerMap.put(victim, damager);
    }

    public UUID getLastDamager(UUID victim) {
        return lastDamagerMap.get(victim);
    }

    public void removeLastDamager(UUID victim) {
        lastDamagerMap.remove(victim);
    }
}
