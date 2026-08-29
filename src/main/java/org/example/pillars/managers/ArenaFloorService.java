package org.example.pillars.managers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitTask;
import org.example.pillars.PillarsPlugin;
import org.example.pillars.entities.Arena;
import org.example.pillars.enums.FloorShape;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Owns arena-floor configuration rules and Bukkit block generation.
 */
public final class ArenaFloorService {
    public static final int MIN_DISTANCE_BELOW_SPAWNS = 12;
    public static final int MAX_DISTANCE_BELOW_SPAWNS = 70;
    public static final int ELIMINATION_MARGIN = 8;
    private final PillarsPlugin plugin;
    private final int columnsPerTick;
    private final Deque<FloorGeneration> pendingGenerations = new ArrayDeque<>();
    private BukkitTask generationTask;
    private boolean shuttingDown;

    public ArenaFloorService(PillarsPlugin plugin, int columnsPerTick) {
        this.plugin = plugin;
        this.columnsPerTick = Math.max(32, columnsPerTick);
    }

    public void loadSettings(Arena arena, ConfigurationSection section) {
        int defaultRadius = switch (arena.getSpawnPoints().size()) {
            case 4 -> 8;
            case 8 -> 16;
            default -> 20;
        };
        int defaultY = (int) Math.floor(arena.getSpawnPoints().stream()
                .mapToDouble(Location::getY)
                .min()
                .orElse(100.0)) - 25;
        Material material = Material.matchMaterial(section.getString("floor.material", "LAVA"));
        if (!isFloorMaterial(material)) {
            material = Material.LAVA;
        }

        arena.setFloorEnabled(section.getBoolean("floor.enabled", true));
        arena.setFloorMaterial(material);
        arena.setFloorShape(FloorShape.fromConfig(section.getString("floor.shape", "square")));
        arena.setFloorRadius(Math.max(2, Math.min(64, section.getInt("floor.radius", defaultRadius))));
        arena.setFloorY(Math.max(
                getMinimumY(arena),
                Math.min(getMaximumY(arena), section.getInt("floor.y", defaultY))
        ));
    }

    public int getMinimumY(Arena arena) {
        World world = arena.getSpawnPoints().get(0).getWorld();
        int worldMinimum = world == null ? 0 : world.getMinHeight() + ELIMINATION_MARGIN + 1;
        return Math.max(worldMinimum, minimumSpawnY(arena) - MAX_DISTANCE_BELOW_SPAWNS);
    }

    public int getMaximumY(Arena arena) {
        World world = arena.getSpawnPoints().get(0).getWorld();
        int worldMaximum = world == null ? 319 : world.getMaxHeight() - 2;
        return Math.max(getMinimumY(arena), Math.min(
                worldMaximum,
                minimumSpawnY(arena) - MIN_DISTANCE_BELOW_SPAWNS
        ));
    }

    public boolean isFloorMaterial(Material material) {
        return material != null
                && material.isBlock()
                && !material.isAir()
                && ((material.isItem() && material.isSolid())
                || material == Material.LAVA
                || material == Material.WATER);
    }

    public void generate(Arena arena, Runnable onSuccess, Consumer<Throwable> onFailure) {
        if (shuttingDown) return;
        if (!arena.isFloorEnabled() || arena.getSpawnPoints().isEmpty()) {
            onSuccess.run();
            return;
        }

        Location center = arena.getCenter();
        World world = center.getWorld();
        if (world == null) {
            onFailure.accept(new IllegalStateException("Мир арены не загружен"));
            return;
        }

        int y = arena.getFloorY();
        Material material = arena.getFloorMaterial();
        boolean basin = material == Material.LAVA || material == Material.WATER;
        Set<Long> blocks = floorBlocks(arena, center.getBlockX(), center.getBlockZ());

        pendingGenerations.addLast(new FloorGeneration(
                world,
                y,
                material,
                basin,
                blocks,
                blocks.iterator(),
                onSuccess,
                onFailure
        ));
        ensureGenerationTask();
    }

    public void shutdown() {
        shuttingDown = true;
        if (generationTask != null) {
            generationTask.cancel();
            generationTask = null;
        }
        pendingGenerations.clear();
    }

    private void ensureGenerationTask() {
        if (generationTask != null) return;
        generationTask = Bukkit.getScheduler().runTaskTimer(plugin, this::processGenerationBatch, 1L, 1L);
    }

    private void processGenerationBatch() {
        int remainingColumns = columnsPerTick;
        while (remainingColumns > 0 && !pendingGenerations.isEmpty()) {
            FloorGeneration generation = pendingGenerations.getFirst();
            try {
                while (remainingColumns > 0 && generation.blocks().hasNext()) {
                    applyColumn(generation, generation.blocks().next());
                    remainingColumns--;
                }
            } catch (RuntimeException e) {
                pendingGenerations.removeFirst();
                generation.onFailure().accept(e);
                continue;
            }

            if (!generation.blocks().hasNext()) {
                pendingGenerations.removeFirst();
                try {
                    generation.onSuccess().run();
                } catch (RuntimeException e) {
                    generation.onFailure().accept(e);
                }
            }
        }

        if (pendingGenerations.isEmpty() && generationTask != null) {
            generationTask.cancel();
            generationTask = null;
        }
    }

    private void applyColumn(FloorGeneration generation, long packed) {
        int x = (int) (packed >> 32);
        int z = (int) packed;
        if (!generation.basin()) {
            generation.world().getBlockAt(x, generation.y(), z).setType(generation.material(), false);
            return;
        }

        generation.world().getBlockAt(x, generation.y() - 1, z).setType(Material.GLASS, false);
        generation.world().getBlockAt(x, generation.y(), z).setType(
                isFloorEdge(generation.allBlocks(), x, z) ? Material.GLASS : generation.material(),
                false
        );
    }

    private int minimumSpawnY(Arena arena) {
        return (int) Math.floor(arena.getSpawnPoints().stream()
                .mapToDouble(Location::getY)
                .min()
                .orElse(100.0));
    }

    private Set<Long> floorBlocks(Arena arena, int centerX, int centerZ) {
        Set<Long> blocks = new HashSet<>();
        int radius = arena.getFloorRadius();

        if (arena.getFloorShape() == FloorShape.ISLANDS) {
            int islandRadius = Math.max(2, radius / 5);
            for (Location spawn : arena.getSpawnPoints()) {
                addSquare(blocks, spawn.getBlockX(), spawn.getBlockZ(), islandRadius, 0);
            }
            return blocks;
        }

        int innerRadius = arena.getFloorShape() == FloorShape.SQUARE_RING ? Math.max(1, radius / 2) : 0;
        addSquare(blocks, centerX, centerZ, radius, innerRadius);
        return blocks;
    }

    private void addSquare(Set<Long> blocks, int centerX, int centerZ, int radius, int innerRadius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int distance = Math.max(Math.abs(dx), Math.abs(dz));
                if (distance <= radius && distance >= innerRadius) {
                    blocks.add(pack(centerX + dx, centerZ + dz));
                }
            }
        }
    }

    private long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private boolean isFloorEdge(Set<Long> blocks, int x, int z) {
        int[][] neighbors = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, -1}, {-1, 1}};
        for (int[] offset : neighbors) {
            if (!blocks.contains(pack(x + offset[0], z + offset[1]))) return true;
        }
        return false;
    }

    private record FloorGeneration(
            World world,
            int y,
            Material material,
            boolean basin,
            Set<Long> allBlocks,
            Iterator<Long> blocks,
            Runnable onSuccess,
            Consumer<Throwable> onFailure
    ) {}
}
