package org.example.pillars.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
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

public class LuckyBlockListener implements Listener {
    private final GameSessionManager gameSessionManager;
    private final ItemManager itemManager;
    private final LuckyBlockOutcomeManager outcomeManager;
    private final JavaPlugin plugin;

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

}
