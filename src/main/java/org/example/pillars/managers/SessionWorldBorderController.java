package org.example.pillars.managers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.example.pillars.entities.Arena;
import org.example.pillars.gameevents.GameEventManager;

import java.util.List;

/**
 * Owns one session's border geometry, shrink task, Last Breath handoff, and reset mutation.
 */
public final class SessionWorldBorderController {
    private final JavaPlugin plugin;
    private final Arena arena;
    private final GameEventManager gameEventManager;
    private final double minimumSize;
    private final double spawnPaddingBlocks;

    private BukkitTask shrinkTask;
    private BukkitTask lastBreathDelayTask;
    private long shrinkEndTimeMillis = -1L;
    private double blocksPerSecond;

    public SessionWorldBorderController(
            JavaPlugin plugin,
            Arena arena,
            GameEventManager gameEventManager,
            double minimumSize,
            double spawnPaddingBlocks
    ) {
        this.plugin = plugin;
        this.arena = arena;
        this.gameEventManager = gameEventManager;
        this.minimumSize = minimumSize;
        this.spawnPaddingBlocks = spawnPaddingBlocks;
    }

    public void start() {
        List<Location> spawns = arena.getSpawnPoints();
        if (spawns.isEmpty()) return;

        World world = spawns.get(0).getWorld();
        if (world == null) return;

        double sumX = 0;
        double sumY = 0;
        double sumZ = 0;
        for (Location spawn : spawns) {
            sumX += spawn.getX() + 0.5;
            sumY += spawn.getY() + 1;
            sumZ += spawn.getZ() + 0.5;
        }
        double averageX = sumX / spawns.size();
        double averageY = sumY / spawns.size();
        double averageZ = sumZ / spawns.size();

        double maxAxisDistance = 0;
        for (Location spawn : spawns) {
            double distanceX = (spawn.getX() + 0.5) - averageX;
            double distanceZ = (spawn.getZ() + 0.5) - averageZ;
            maxAxisDistance = Math.max(
                    maxAxisDistance,
                    Math.max(Math.abs(distanceX), Math.abs(distanceZ))
            );
        }

        double initialSize = Math.max(minimumSize, (maxAxisDistance + spawnPaddingBlocks) * 2);
        WorldBorder border = world.getWorldBorder();
        border.setCenter(new Location(world, averageX, averageY, averageZ));
        border.setSize(initialSize);
        border.setDamageAmount(1.0);
        border.setDamageBuffer(0);
        border.setWarningDistance(0);
        border.setWarningTime(0);

        long shrinkSeconds = arena.getBorderShrinkSeconds();
        shrinkEndTimeMillis = System.currentTimeMillis() + (shrinkSeconds * 1000L);
        blocksPerSecond = Math.max(0.0, (initialSize - minimumSize) / shrinkSeconds);
        startShrink(border);

        lastBreathDelayTask = Bukkit.getScheduler().runTaskLater(
                plugin,
                gameEventManager::startLastBreathEvent,
                shrinkSeconds * 20L
        );
    }

    public void stop() {
        cancelTasks();
        shrinkEndTimeMillis = -1L;
        blocksPerSecond = 0.0;

        if (arena.getSpawnPoints().isEmpty()) return;
        World world = arena.getSpawnPoints().getFirst().getWorld();
        if (world == null) return;

        WorldBorder border = world.getWorldBorder();
        border.setSize(1000);
        border.setCenter(new Location(world, 0, 64, 0));
        border.setDamageAmount(0.2);
        border.setDamageBuffer(5);
    }

    public double getCurrentSize() {
        if (arena.getSpawnPoints().isEmpty()) return minimumSize;

        World world = arena.getSpawnPoints().getFirst().getWorld();
        return world == null ? minimumSize : world.getWorldBorder().getSize();
    }

    public long getSecondsUntilNextDecrease(double currentSize) {
        if (shrinkEndTimeMillis <= 0L || blocksPerSecond <= 0.0 || currentSize <= minimumSize) {
            return 0L;
        }

        double visibleSize = Math.ceil(currentSize);
        double minimumVisibleSize = Math.ceil(minimumSize);
        if (visibleSize <= minimumVisibleSize) return 0L;

        double nextVisibleSize = Math.max(minimumVisibleSize, visibleSize - 1.0);
        double seconds = (currentSize - nextVisibleSize) / blocksPerSecond;
        return Math.max(1L, (long) Math.ceil(seconds));
    }

    private void startShrink(WorldBorder border) {
        cancelShrinkTask();
        shrinkTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long remainingMillis = Math.max(0L, shrinkEndTimeMillis - System.currentTimeMillis());
            double nextSize = minimumSize + (blocksPerSecond * remainingMillis / 1000.0);
            border.setSize(Math.max(minimumSize, nextSize));

            if (remainingMillis == 0L) {
                cancelShrinkTask();
            }
        }, 1L, 1L);
    }

    private void cancelTasks() {
        cancelShrinkTask();
        if (lastBreathDelayTask != null) {
            lastBreathDelayTask.cancel();
            lastBreathDelayTask = null;
        }
    }

    private void cancelShrinkTask() {
        if (shrinkTask != null) {
            shrinkTask.cancel();
            shrinkTask = null;
        }
    }
}
