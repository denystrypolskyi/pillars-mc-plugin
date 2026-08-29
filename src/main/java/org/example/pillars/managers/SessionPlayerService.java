package org.example.pillars.managers;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.block.data.BlockData;
import org.example.pillars.entities.Arena;
import org.example.pillars.enums.ArenaGameMode;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Boundary for player and arena-block mutations initiated by a session.
 */
public final class SessionPlayerService {
    private final PlayerManager playerManager;
    private final SpawnManager spawnManager;
    private final TeleportManager teleportManager;

    public SessionPlayerService(
            PlayerManager playerManager,
            SpawnManager spawnManager,
            TeleportManager teleportManager
    ) {
        this.playerManager = playerManager;
        this.spawnManager = spawnManager;
        this.teleportManager = teleportManager;
    }

    public PreparedSpawn prepareForJoin(
            Player player,
            Arena arena,
            Set<UUID> activePlayerIds,
            Collection<Location> occupiedSpawns,
            int pillarHeight
    ) {
        if (arena.getSpawnPoints() == null || arena.getSpawnPoints().isEmpty()) return null;

        Location spawn = spawnManager.getFarthestSpawn(arena, activePlayerIds, occupiedSpawns);
        if (spawn == null) return null;

        Material pillarMaterial = arena.getGameMode() == ArenaGameMode.LUCKY_BLOCKS
                ? Material.SPONGE
                : Material.BEDROCK;
        SpawnManager.PreparedPillar pillar = spawnManager.prepareSpawnWithSnapshot(
                spawn,
                pillarHeight,
                pillarMaterial
        );
        Location destination = spawn.clone().add(0.5, 1.0, 0.5);
        teleportManager.teleportToSpawnPoint(player, destination);
        return new PreparedSpawn(spawn, destination, pillar.blocks(), pillar.originalBlocks());
    }

    public List<Location> restoreSpawn(Map<Location, BlockData> originalBlocks) {
        return spawnManager.restoreSpawn(originalBlocks);
    }

    public List<Location> rebuildPillar(Location spawn, int pillarHeight, ArenaGameMode mode) {
        Material material = mode == ArenaGameMode.LUCKY_BLOCKS ? Material.SPONGE : Material.BEDROCK;
        return spawnManager.prepareSpawn(spawn, pillarHeight, material);
    }

    public void resetForJoin(Player player) {
        playerManager.resetPlayerState(player);
    }

    public void returnToLobby(Player player, String lobbyWorldName) {
        playerManager.resetAndReturnToLobby(player, lobbyWorldName);
    }

    public void release(Player player) {
        playerManager.releasePlayer(player);
    }

    public void giveWaitingItems(Player player, String arenaName) {
        playerManager.giveLeaveArenaItem(player);
        playerManager.giveForceStartItem(player);
        playerManager.giveAdminMenuItem(player);
        playerManager.giveCurrentArenaSettingsItem(player, arenaName);
    }

    public void removeWaitingItems(Player player) {
        playerManager.removeLeaveArenaItem(player);
        playerManager.removeForceStartItem(player);
        playerManager.removeAdminMenuItem(player);
        playerManager.removeCurrentArenaSettingsItem(player);
    }

    public void makeSpectator(Player player, Location spectatorCenter) {
        playerManager.prepareSpectatorInventory(player);
        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(spectatorCenter);
    }

    public void makeAdminSpectator(Player player, Location spectatorCenter) {
        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(spectatorCenter);
    }

    public void makeEndingSpectator(Player player, Location spectatorCenter) {
        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(spectatorCenter);
    }

    public void restoreAdminSpectator(Player player, Location previousLocation, GameMode previousGameMode) {
        player.setGameMode(previousGameMode == null ? GameMode.SURVIVAL : previousGameMode);
        if (previousLocation != null) player.teleport(previousLocation);
    }

    public void evacuate(Collection<UUID> trackedPlayerIds, String arenaWorldName, String lobbyWorldName) {
        Set<Player> players = new HashSet<>();
        for (UUID playerId : trackedPlayerIds) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) players.add(player);
        }

        World arenaWorld = Bukkit.getWorld(arenaWorldName);
        if (arenaWorld != null) players.addAll(arenaWorld.getPlayers());
        for (Player player : players) returnToLobby(player, lobbyWorldName);
    }

    public record PreparedSpawn(
            Location spawn,
            Location destination,
            List<Location> pillarBlocks,
            Map<Location, BlockData> originalBlocks
    ) {}
}
