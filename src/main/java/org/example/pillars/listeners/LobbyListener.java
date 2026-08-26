package org.example.pillars.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.example.pillars.GameSession;
import org.example.pillars.entities.Arena;
import org.example.pillars.enums.GameState;
import org.example.pillars.gui.AdminHubMenu;
import org.example.pillars.gui.AdminArenaSettingsMenu;
import org.example.pillars.gui.ArenaMenu;
import org.example.pillars.managers.ArenaManager;
import org.example.pillars.managers.GameSessionManager;
import org.example.pillars.managers.HudManager;
import org.example.pillars.managers.ItemManager;
import org.example.pillars.managers.PlayerManager;

public class LobbyListener implements Listener {

    private final JavaPlugin plugin;
    private final ArenaManager arenaManager;
    private final GameSessionManager gameSessionManager;
    private final HudManager hudManager;
    private final ItemManager itemManager;
    private final PlayerManager playerManager;

    public LobbyListener(
            JavaPlugin plugin,
            ArenaManager arenaManager,
            GameSessionManager gameSessionManager,
            HudManager hudManager,
            ItemManager itemManager,
            PlayerManager playerManager
    ) {
        this.plugin = plugin;
        this.arenaManager = arenaManager;
        this.gameSessionManager = gameSessionManager;
        this.hudManager = hudManager;
        this.itemManager = itemManager;
        this.playerManager = playerManager;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        playerManager.resetAndReturnToLobby(player);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        GameSession session = gameSessionManager.getSessionByPlayer(player);

        if (session != null) {
            event.setDeathMessage(null);
            event.getDrops().clear();
            event.setDroppedExp(0);
            return;
        }

        event.getDrops().removeIf(item -> playerManager.getAction(item) != null);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> handleRespawn(player));
    }

    private void handleRespawn(Player player) {
        GameSession session = gameSessionManager.getSessionByPlayer(player);
        if (session == null) {
            playerManager.resetAndReturnToLobby(player);
            return;
        }

        if (session.getState() == GameState.RUNNING
                && session.getActivePlayerIds().contains(player.getUniqueId())) {
            session.playerDeath(player, null);
            return;
        }

        session.playerLeave(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        String action = playerManager.getAction(event.getItem());
        if (action == null) return;

        event.setCancelled(true);
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();

        if (action.equals("menu")) {
            new ArenaMenu(player, arenaManager, gameSessionManager, hudManager).open();
            return;
        }

        if (action.equals("quickjoin")) {
            Arena arena = gameSessionManager.findQuickJoinArena();
            if (arena == null) {
                hudManager.sendNoJoinableSession(player);
                return;
            }
            gameSessionManager.joinSession(player, arena);
            return;
        }

        if (action.equals("admin_menu")) {
            if (!player.hasPermission("pillars.admin")) {
                playerManager.removeAdminMenuItem(player);
                hudManager.sendNoPermission(player);
                return;
            }

            new AdminHubMenu(player, itemManager, hudManager, arenaManager, gameSessionManager).open();
            return;
        }

        if (action.equals("arena_settings")) {
            if (!player.hasPermission("pillars.admin")) {
                playerManager.removeCurrentArenaSettingsItem(player);
                hudManager.sendNoPermission(player);
                return;
            }

            GameSession session = gameSessionManager.getSessionByPlayer(player);
            if (session == null) {
                playerManager.removeCurrentArenaSettingsItem(player);
                return;
            }

            new AdminArenaSettingsMenu(
                    player,
                    arenaManager,
                    gameSessionManager,
                    itemManager,
                    hudManager,
                    session.getArena()
            ).open();
            return;
        }

        if (action.equals("force_start")) {
            gameSessionManager.forceStartSession(player);
            return;
        }

        if (action.equals("leave_arena")) {
            gameSessionManager.leaveSession(player);
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (playerManager.getAction(event.getItemDrop().getItemStack()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        boolean clickedActionItem = playerManager.getAction(event.getCurrentItem()) != null
                || playerManager.getAction(event.getCursor()) != null;

        int hotbarSlot = event.getHotbarButton();
        if (hotbarSlot >= 0 && playerManager.getAction(player.getInventory().getItem(hotbarSlot)) != null) {
            clickedActionItem = true;
        }

        if (clickedActionItem) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (playerManager.getAction(event.getOldCursor()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (playerManager.getAction(event.getMainHandItem()) != null
                || playerManager.getAction(event.getOffHandItem()) != null) {
            event.setCancelled(true);
        }
    }
}
