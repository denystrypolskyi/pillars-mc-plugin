package org.example.pillars.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.example.pillars.managers.ArenaManager;
import org.example.pillars.managers.GameSessionManager;
import org.example.pillars.managers.HudManager;
import org.example.pillars.managers.ItemManager;
import org.example.pillars.managers.TranslationManager;

import java.util.List;

public final class AdminItemSettingsMenu implements InventoryHolder {
    private static final NamespacedKey ACTION_KEY = new NamespacedKey("pillars", "admin_item_settings_action");
    private static final int MENU_SIZE = 27;

    private final Inventory inventory;
    private final Player player;
    private final ItemManager itemManager;
    private final HudManager hudManager;
    private final ArenaManager arenaManager;
    private final GameSessionManager gameSessionManager;
    private final TranslationManager translations;

    public AdminItemSettingsMenu(Player player, ItemManager itemManager, HudManager hudManager, ArenaManager arenaManager, GameSessionManager gameSessionManager) {
        this.player = player;
        this.itemManager = itemManager;
        this.hudManager = hudManager;
        this.arenaManager = arenaManager;
        this.gameSessionManager = gameSessionManager;
        this.translations = hudManager.getTranslations();
        this.inventory = Bukkit.createInventory(this, MENU_SIZE, translations.text("menus.item-settings.title"));
        buildMenu();
    }

    private void buildMenu() {
        ArenaMenuItemFactory.fill(inventory, Material.BLACK_STAINED_GLASS_PANE);

        inventory.setItem(4, actionItem(
                Material.COMPARATOR,
                translations.text("menus.item-settings.rarity-chances"),
                translations.list("menus.item-settings.rarity-chances-lore"),
                "rarity"
        ));
        inventory.setItem(11, actionItem(
                Material.CHEST,
                translations.text("menus.item-settings.common-items"),
                translations.list("menus.item-settings.common-items-lore"),
                "pool:common"
        ));
        inventory.setItem(13, actionItem(
                Material.ENDER_CHEST,
                translations.text("menus.item-settings.rare-items"),
                translations.list("menus.item-settings.rare-items-lore"),
                "pool:rare"
        ));
        inventory.setItem(15, actionItem(
                Material.NETHERITE_BLOCK,
                translations.text("menus.item-settings.legendary-items"),
                translations.list("menus.item-settings.legendary-items-lore"),
                "pool:legendary"
        ));
        inventory.setItem(18, actionItem(
                Material.ARROW,
                translations.text("menus.common.back"),
                List.of(translations.text("menus.common.back-admin-lore")),
                "back"
        ));
    }

    private ItemStack actionItem(Material material, String name, List<String> lore, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(ACTION_KEY, PersistentDataType.STRING, action);
            item.setItemMeta(meta);
        }
        return item;
    }

    public void open() {
        player.openInventory(inventory);
    }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player clicker)) return;
        if (!clicker.hasPermission("pillars.admin")) {
            hudManager.sendNoPermission(clicker);
            clicker.closeInventory();
            return;
        }
        if (event.getCurrentItem() == null || !event.getCurrentItem().hasItemMeta()) return;

        String action = event.getCurrentItem().getItemMeta()
                .getPersistentDataContainer()
                .get(ACTION_KEY, PersistentDataType.STRING);
        if (action == null) return;

        if (action.equals("back")) {
            new AdminHubMenu(clicker, itemManager, hudManager, arenaManager, gameSessionManager).open();
        } else if (action.equals("rarity")) {
            new AdminConfigMenu(clicker, itemManager, hudManager, arenaManager, gameSessionManager).open();
        } else if (action.startsWith("pool:")) {
            new AdminItemPoolMenu(clicker, arenaManager, gameSessionManager, itemManager, hudManager, action.substring("pool:".length())).open();
        }
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public static boolean isAdminItemSettingsMenu(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof AdminItemSettingsMenu;
    }
}
