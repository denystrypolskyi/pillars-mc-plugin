package org.example.pillars.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.example.pillars.GameSession;
import org.example.pillars.managers.GameSessionManager;
import org.example.pillars.managers.ItemManager;
import org.example.pillars.managers.LuckyBlockOutcomeManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class LuckyBlockListener implements Listener {
    public static final int BREAK_DURATION_TICKS = 6;

    private final GameSessionManager gameSessionManager;
    private final ItemManager itemManager;
    private final LuckyBlockOutcomeManager outcomeManager;
    private final JavaPlugin plugin;
    private final Map<UUID, BukkitRunnable> miningTasks = new HashMap<>();
    private final Map<UUID, Location> miningBlocks = new HashMap<>();

    public LuckyBlockListener(
            JavaPlugin plugin,
            GameSessionManager gameSessionManager,
            ItemManager itemManager,
            LuckyBlockOutcomeManager outcomeManager
    ) {
        this.plugin = plugin;
        this.gameSessionManager = gameSessionManager;
        this.itemManager = itemManager;
        this.outcomeManager = outcomeManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        GameSession session = gameSessionManager.getSession(event.getBlock().getWorld());
        if (session == null || !session.isLuckyBlocksModeActive()) return;
        if (!session.isActivePlayer(event.getPlayer()) || !session.isLuckyBlock(event.getBlock())) return;

        event.setCancelled(true);
        startMining(event.getPlayer(), event.getBlock(), session);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockDamageAbort(BlockDamageAbortEvent event) {
        Location miningBlock = miningBlocks.get(event.getPlayer().getUniqueId());
        if (miningBlock != null && miningBlock.equals(event.getBlock().getLocation())) {
            cancelMining(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!itemManager.isLuckyBlock(event.getItemInHand())) return;

        GameSession session = gameSessionManager.getSessionByPlayer(event.getPlayer());
        if (session == null || !session.isLuckyBlocksModeActive() || !session.isActivePlayer(event.getPlayer())) {
            return;
        }

        session.registerLuckyBlock(event.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        GameSession session = gameSessionManager.getSession(event.getBlock().getWorld());
        if (session == null || !session.isLuckyBlock(event.getBlock())) return;

        if (!session.isLuckyBlocksModeActive() || !session.isActivePlayer(event.getPlayer())) {
            event.setCancelled(true);
            return;
        }

        event.setDropItems(false);
        event.setExpToDrop(0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreakReward(BlockBreakEvent event) {
        GameSession session = gameSessionManager.getSession(event.getBlock().getWorld());
        if (session == null || !session.isLuckyBlocksModeActive()) return;
        if (!session.isActivePlayer(event.getPlayer())) return;

        if (session.removeLuckyBlock(event.getBlock())) {
            cancelMiningAt(event.getBlock().getLocation());
            Player player = event.getPlayer();
            Location blockLocation = event.getBlock().getLocation();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && session.isLuckyBlocksModeActive()) {
                    outcomeManager.trigger(player, blockLocation, session);
                }
            });
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        protectLuckyBlocks(event.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        protectLuckyBlocks(event.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (containsLuckyBlock(event.getBlocks())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (containsLuckyBlock(event.getBlocks())) event.setCancelled(true);
    }

    private void protectLuckyBlocks(java.util.List<Block> blocks) {
        blocks.removeIf(block -> {
            GameSession session = gameSessionManager.getSession(block.getWorld());
            return session != null && session.isLuckyBlock(block);
        });
    }

    private boolean containsLuckyBlock(java.util.List<Block> blocks) {
        for (Block block : blocks) {
            GameSession session = gameSessionManager.getSession(block.getWorld());
            if (session != null && session.isLuckyBlock(block)) return true;
        }
        return false;
    }

    private void startMining(Player player, Block block, GameSession session) {
        UUID playerId = player.getUniqueId();
        cancelMining(playerId);

        Location blockLocation = block.getLocation();
        int totalTicks = BREAK_DURATION_TICKS;
        int crackSourceId = playerId.hashCode();
        miningBlocks.put(playerId, blockLocation);

        BukkitRunnable task = new BukkitRunnable() {
            private int elapsedTicks;

            @Override
            public void run() {
                Block currentBlock = blockLocation.getBlock();
                GameSession currentSession = gameSessionManager.getSession(currentBlock.getWorld());
                if (!player.isOnline()
                        || currentSession != session
                        || !session.isLuckyBlocksModeActive()
                        || !session.isActivePlayer(player)
                        || !session.isLuckyBlock(currentBlock)) {
                    cancelMining(playerId);
                    return;
                }

                elapsedTicks++;
                float progress = Math.min(0.99F, (float) elapsedTicks / totalTicks);
                showCracks(session, blockLocation, progress, crackSourceId);

                if (elapsedTicks < totalTicks) return;

                miningTasks.remove(playerId);
                miningBlocks.remove(playerId);
                cancel();
                showCracks(session, blockLocation, 0.0F, crackSourceId);

                if (!session.removeLuckyBlock(currentBlock)) return;

                currentBlock.setType(Material.AIR, false);
                currentBlock.getWorld().playSound(
                        blockLocation.clone().add(0.5, 0.5, 0.5),
                        Sound.BLOCK_STONE_BREAK,
                        1.0F,
                        1.15F
                );
                outcomeManager.trigger(player, blockLocation, session);
                cancelMiningAt(blockLocation);
            }
        };

        miningTasks.put(playerId, task);
        task.runTaskTimer(plugin, 1L, 1L);
    }

    private void cancelMining(UUID playerId) {
        BukkitRunnable task = miningTasks.remove(playerId);
        Location blockLocation = miningBlocks.remove(playerId);
        if (task != null) task.cancel();
        if (blockLocation == null) return;

        GameSession session = gameSessionManager.getSession(blockLocation.getWorld());
        if (session != null) {
            showCracks(session, blockLocation, 0.0F, playerId.hashCode());
        }
    }

    private void cancelMiningAt(Location blockLocation) {
        for (Map.Entry<UUID, Location> entry : new HashMap<>(miningBlocks).entrySet()) {
            if (entry.getValue().equals(blockLocation)) {
                cancelMining(entry.getKey());
            }
        }
    }

    private void showCracks(GameSession session, Location location, float progress, int sourceId) {
        for (UUID playerId : session.getAllPlayerIds()) {
            Player viewer = Bukkit.getPlayer(playerId);
            if (viewer != null && viewer.isOnline() && viewer.getWorld().equals(location.getWorld())) {
                viewer.sendBlockDamage(location, progress, sourceId);
            }
        }
    }

}
