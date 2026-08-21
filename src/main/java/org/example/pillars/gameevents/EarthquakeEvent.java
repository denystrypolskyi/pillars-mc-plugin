package org.example.pillars.gameevents;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.example.pillars.GameSession;
import org.example.pillars.managers.HudManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class EarthquakeEvent implements GameEvent {
    private static final String ID = "earthquake";
    private static final long FIRST_WAVE_DELAY_TICKS = 10L;
    private static final long WARNING_UPDATE_TICKS = 5L;
    private static final long RESTORE_RETRY_TICKS = 10L;
    private static final int CANDIDATE_ATTEMPTS = 16;
    private static final int VERTICAL_SEARCH_DEPTH = 8;

    private final JavaPlugin plugin;
    private final GameSession session;
    private final HudManager hudManager;
    private final int durationSeconds;
    private final long wavePeriodTicks;
    private final int warningTicks;
    private final long missingDurationTicks;
    private final int maxBlocksPerWave;
    private final int playersPerBlock;
    private final int horizontalSearchRadius;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final Map<Block, BlockData> originalBlocks = new HashMap<>();
    private final Set<Block> collapsedBlocks = new HashSet<>();

    private boolean active;

    public EarthquakeEvent(
            JavaPlugin plugin,
            GameSession session,
            HudManager hudManager,
            int durationSeconds,
            long wavePeriodTicks,
            int warningTicks,
            long missingDurationTicks,
            int maxBlocksPerWave,
            int playersPerBlock,
            int horizontalSearchRadius
    ) {
        this.plugin = plugin;
        this.session = session;
        this.hudManager = hudManager;
        this.durationSeconds = durationSeconds;
        this.wavePeriodTicks = wavePeriodTicks;
        this.warningTicks = warningTicks;
        this.missingDurationTicks = missingDurationTicks;
        this.maxBlocksPerWave = maxBlocksPerWave;
        this.playersPerBlock = playersPerBlock;
        this.horizontalSearchRadius = horizontalSearchRadius;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public int getDurationSeconds() {
        return durationSeconds;
    }

    @Override
    public void start() {
        if (active) return;

        active = true;
        hudManager.sendEarthquakeStarted(session.getAllPlayerIds(), durationSeconds);

        long durationTicks = durationSeconds * 20L;
        int maximumWaves = (int) Math.max(
                1L,
                (durationTicks - FIRST_WAVE_DELAY_TICKS - warningTicks) / wavePeriodTicks + 1L
        );

        BukkitTask waveTask = new BukkitRunnable() {
            private int wavesStarted;

            @Override
            public void run() {
                if (!active || wavesStarted >= maximumWaves) {
                    cancel();
                    return;
                }

                startWave();
                wavesStarted++;
            }
        }.runTaskTimer(plugin, FIRST_WAVE_DELAY_TICKS, wavePeriodTicks);
        tasks.add(waveTask);
    }

    @Override
    public void stop() {
        if (!active) return;

        active = false;
        for (BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();

        for (Map.Entry<Block, BlockData> entry : originalBlocks.entrySet()) {
            clearCracks(entry.getKey());
            if (collapsedBlocks.contains(entry.getKey())) {
                restoreImmediately(entry.getKey(), entry.getValue());
            }
        }
        originalBlocks.clear();
        collapsedBlocks.clear();

        hudManager.sendEarthquakeEnded(session.getAllPlayerIds());
    }

    private void startWave() {
        List<Player> players = getActivePlayers();
        if (players.isEmpty()) return;

        Collections.shuffle(players);
        int blockCount = Math.min(
                maxBlocksPerWave,
                Math.max(1, (players.size() + playersPerBlock - 1) / playersPerBlock)
        );

        int warnedBlocks = 0;
        for (Player player : players) {
            Block block = findAffectedBlock(player);
            if (block == null) continue;

            warnAndCollapse(block);
            warnedBlocks++;
            if (warnedBlocks >= blockCount) break;
        }
    }

    private List<Player> getActivePlayers() {
        List<Player> players = new ArrayList<>();
        for (UUID uuid : session.getActivePlayerIds()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                players.add(player);
            }
        }
        return players;
    }

    private Block findAffectedBlock(Player player) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location playerLocation = player.getLocation();

        for (int attempt = 0; attempt < CANDIDATE_ATTEMPTS; attempt++) {
            int offsetX = random.nextInt(-horizontalSearchRadius, horizontalSearchRadius + 1);
            int offsetZ = random.nextInt(-horizontalSearchRadius, horizontalSearchRadius + 1);
            int x = playerLocation.getBlockX() + offsetX;
            int z = playerLocation.getBlockZ() + offsetZ;
            int startY = playerLocation.getBlockY() - 1;

            for (int depth = 0; depth < VERTICAL_SEARCH_DEPTH; depth++) {
                Block block = player.getWorld().getBlockAt(x, startY - depth, z);
                if (isSafeCandidate(block)) {
                    return block;
                }
            }
        }
        return null;
    }

    private boolean isSafeCandidate(Block block) {
        Material material = block.getType();
        return material.isSolid()
                && material.getHardness() >= 0.0F
                && block.getRelative(0, 1, 0).isPassable()
                && !(block.getState() instanceof TileState)
                && !originalBlocks.containsKey(block);
    }

    private void warnAndCollapse(Block block) {
        BlockData originalBlockData = block.getBlockData().clone();
        originalBlocks.put(block, originalBlockData);

        BukkitTask warningTask = new BukkitRunnable() {
            private int elapsedTicks;

            @Override
            public void run() {
                if (!active) {
                    cancel();
                    return;
                }
                if (!block.getBlockData().equals(originalBlockData)) {
                    clearCracks(block);
                    originalBlocks.remove(block);
                    cancel();
                    return;
                }

                elapsedTicks += WARNING_UPDATE_TICKS;
                float progress = Math.min(0.95F, (float) elapsedTicks / warningTicks);
                showCracks(block, progress);
                showWarningParticles(block);

                if (elapsedTicks < warningTicks) return;

                clearCracks(block);
                collapse(block, originalBlockData);
                cancel();
            }
        }.runTaskTimer(plugin, 0L, WARNING_UPDATE_TICKS);
        tasks.add(warningTask);
    }

    private void collapse(Block block, BlockData originalBlockData) {
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        block.getWorld().spawnParticle(
                Particle.BLOCK_CRUMBLE,
                center,
                28,
                0.4,
                0.4,
                0.4,
                originalBlockData
        );
        block.getWorld().playSound(center, Sound.BLOCK_STONE_BREAK, 1.0F, 0.75F);
        block.setType(Material.AIR, false);
        collapsedBlocks.add(block);

        BukkitTask restoreTask = Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> restoreWhenSafe(block, originalBlockData),
                missingDurationTicks
        );
        tasks.add(restoreTask);
    }

    private void restoreWhenSafe(Block block, BlockData originalBlockData) {
        if (!active) return;
        if (isOccupied(block)) {
            BukkitTask retryTask = Bukkit.getScheduler().runTaskLater(
                    plugin,
                    () -> restoreWhenSafe(block, originalBlockData),
                    RESTORE_RETRY_TICKS
            );
            tasks.add(retryTask);
            return;
        }

        block.setBlockData(originalBlockData, false);
        collapsedBlocks.remove(block);
        originalBlocks.remove(block);
    }

    private boolean isOccupied(Block block) {
        BoundingBox blockArea = getBlockArea(block);
        for (Player player : getActivePlayers()) {
            if (player.getWorld().equals(block.getWorld())
                    && player.getBoundingBox().overlaps(blockArea)) {
                return true;
            }
        }
        return false;
    }

    private void restoreImmediately(Block block, BlockData originalBlockData) {
        BoundingBox blockArea = getBlockArea(block);
        for (Player player : getActivePlayers()) {
            if (!player.getWorld().equals(block.getWorld())
                    || !player.getBoundingBox().overlaps(blockArea)) {
                continue;
            }

            Location safeLocation = player.getLocation().clone();
            safeLocation.setY(block.getY() + 1.0);
            player.teleport(safeLocation);
        }
        block.setBlockData(originalBlockData, false);
    }

    private BoundingBox getBlockArea(Block block) {
        return new BoundingBox(
                block.getX(),
                block.getY(),
                block.getZ(),
                block.getX() + 1.0,
                block.getY() + 1.0,
                block.getZ() + 1.0
        );
    }

    private void showCracks(Block block, float progress) {
        for (UUID uuid : session.getAllPlayerIds()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline() && player.getWorld().equals(block.getWorld())) {
                player.sendBlockDamage(block.getLocation(), progress, block.hashCode());
            }
        }
    }

    private void clearCracks(Block block) {
        showCracks(block, 0.0F);
    }

    private void showWarningParticles(Block block) {
        Location location = block.getLocation().add(0.5, 1.05, 0.5);
        block.getWorld().spawnParticle(
                Particle.DUST,
                location,
                8,
                0.35,
                0.05,
                0.35,
                new Particle.DustOptions(Color.ORANGE, 1.2F)
        );
    }
}
