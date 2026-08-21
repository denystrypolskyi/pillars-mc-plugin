package org.example.pillars.managers;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public class PlayerManager {

    private final TeleportManager teleportManager;
    private final HudManager hudManager;
    private final TranslationManager translations;
    private final NamespacedKey actionKey;
    private final String lobbyWorldName;

    public PlayerManager(JavaPlugin plugin, TeleportManager teleportManager, HudManager hudManager) {
        this.teleportManager = teleportManager;
        this.hudManager = hudManager;
        this.translations = hudManager.getTranslations();
        this.actionKey = new NamespacedKey(plugin, "lobby_action");
        this.lobbyWorldName = plugin.getConfig().getString("settings.lobbyWorldName", "world");
    }

    public void resetPlayerState(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setExhaustion(0f);
        player.setLevel(0);
        player.setExp(0f);
        player.setFireTicks(0);
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setGlowing(false);
    }

    public void resetAndReturnToLobby(Player player) {
        resetAndReturnToLobby(player, lobbyWorldName);
    }

    public void resetAndReturnToLobby(Player player, String lobbyWorldName) {
        resetPlayerState(player);
        if (!teleportManager.teleportToLobby(player, lobbyWorldName)) {
            hudManager.sendLobbyWorldMissing(player, lobbyWorldName);
        } else {
            giveLobbyItems(player);
        }
        hudManager.resetScoreboard(player);
    }

    public void prepareSpectatorInventory(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        giveLeaveArenaItem(player);
    }

    public String getAction(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;

        return item.getItemMeta().getPersistentDataContainer()
                .get(actionKey, PersistentDataType.STRING);
    }

    public void giveForceStartItem(Player player) {
        if (!player.hasPermission("pillars.forcestart")) return;

        player.getInventory().setItem(0, actionItem(
                Material.LIME_DYE,
                translations.text("game-items.force-start"),
                "force_start",
                translations.text("game-items.force-start-lore")
        ));
    }

    public void removeForceStartItem(Player player) {
        removeActionItem(player, "force_start");
    }

    public void giveLeaveArenaItem(Player player) {
        player.getInventory().setItem(8, actionItem(
                Material.RED_BED,
                translations.text("game-items.leave-arena"),
                "leave_arena",
                translations.text("game-items.leave-arena-lore")
        ));
    }

    public void removeLeaveArenaItem(Player player) {
        removeActionItem(player, "leave_arena");
    }

    public void giveAdminMenuItem(Player player) {
        removeAdminMenuItem(player);
        if (!player.hasPermission("pillars.admin")) return;

        player.getInventory().setItem(4, actionItem(
                Material.COMMAND_BLOCK,
                translations.text("admin-item.name"),
                "admin_menu",
                translations.text("admin-item.lore")
        ));
    }

    public void removeAdminMenuItem(Player player) {
        removeActionItem(player, "admin_menu");
    }

    private void removeActionItem(Player player, String action) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (action.equals(getAction(player.getInventory().getItem(slot)))) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    private void giveLobbyItems(Player player) {
        player.getInventory().setItem(0, actionItem(
                Material.RECOVERY_COMPASS,
                translations.text("lobby-items.choose-arena"),
                "menu"
        ));
        player.getInventory().setItem(8, actionItem(
                Material.NETHER_STAR,
                translations.text("lobby-items.quick-play"),
                "quickjoin"
        ));
        giveAdminMenuItem(player);
    }

    private ItemStack actionItem(Material material, String name, String action, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) {
            meta.setLore(List.of(lore));
        }
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }
}
