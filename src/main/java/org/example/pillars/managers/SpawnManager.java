package org.example.pillars.managers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.example.pillars.entities.Arena;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class SpawnManager {

    public Location getFarthestSpawn(Arena arena, Collection<UUID> activePlayers, Collection<Location> occupied) {
        List<Location> spawns = new ArrayList<>();
        for (Location configuredSpawn : arena.getSpawnPoints()) {
            if (configuredSpawn == null || configuredSpawn.getWorld() == null) continue;

            Location blockLocation = configuredSpawn.getBlock().getLocation();
            if (!spawns.contains(blockLocation)) {
                spawns.add(blockLocation);
            }
        }
        if (spawns.isEmpty()) return null;

        spawns.removeIf(occupied::contains);

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
                Player player = Bukkit.getPlayer(uuid);
                if (player == null || !candidate.getWorld().equals(player.getWorld())) continue;

                Location playerLoc = player.getLocation();
                double dx = cx - playerLoc.getX();
                double dz = cz - playerLoc.getZ();

                double distanceSq = dx * dx + dz * dz;
                minDistanceSq = Math.min(minDistanceSq, distanceSq);
            }

            if (minDistanceSq > bestMinDistanceSq) {
                bestMinDistanceSq = minDistanceSq;
                bestSpawn = candidate;
            }
        }

        return bestSpawn;
    }

    public List<Location> prepareSpawn(Location spawn, int height, Material material) {
        World world = spawn == null ? null : spawn.getWorld();
        if (world == null) return List.of();

        int topY = spawn.getBlockY();
        int bottomY = getPillarBottomY(world, topY, height);
        List<Location> blocks = new ArrayList<>();
        for (int y = topY; y >= bottomY; y--) {
            world.getBlockAt(spawn.getBlockX(), y, spawn.getBlockZ())
                    .setType(material, false);
            blocks.add(new Location(world, spawn.getBlockX(), y, spawn.getBlockZ()));
        }
        return blocks;
    }

    public List<Location> cleanupSpawn(Location spawn, int height) {
        World world = spawn == null ? null : spawn.getWorld();
        if (world == null) return List.of();

        int topY = spawn.getBlockY();
        int bottomY = getPillarBottomY(world, topY, height);
        List<Location> blocks = new ArrayList<>();
        for (int y = topY; y >= bottomY; y--) {
            org.bukkit.block.Block block = world.getBlockAt(spawn.getBlockX(), y, spawn.getBlockZ());
            if (block.getType() == Material.BEDROCK
                    || block.getType() == Material.YELLOW_GLAZED_TERRACOTTA) {
                block.setType(Material.AIR, false);
            }
            blocks.add(block.getLocation());
        }
        return blocks;
    }

    private int getPillarBottomY(World world, int topY, int height) {
        int worldHeight = world.getMaxHeight() - world.getMinHeight();
        int safeHeight = Math.max(1, Math.min(height, worldHeight));
        return Math.max(world.getMinHeight(), topY - safeHeight + 1);
    }
}
