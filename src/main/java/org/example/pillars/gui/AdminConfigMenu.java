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
import org.example.pillars.ui.UiPalette;

import java.util.List;

public class AdminConfigMenu implements InventoryHolder {
    private static final NamespacedKey ACTION_KEY = new NamespacedKey("pillars", "admin_action");
    private static final int MENU_SIZE = 27;
    private final Inventory inventory;
    private final Player player;
    private final ItemManager itemManager;
    private final HudManager hudManager;
    private final ArenaManager arenaManager;
    private final GameSessionManager gameSessionManager;
    private final TranslationManager translations;

    public AdminConfigMenu(Player player, ItemManager itemManager, HudManager hudManager, ArenaManager arenaManager, GameSessionManager gameSessionManager) {
        this.player = player;
        this.itemManager = itemManager;
        this.hudManager = hudManager;
        this.arenaManager = arenaManager;
        this.gameSessionManager = gameSessionManager;
        this.translations = hudManager.getTranslations();
        this.inventory = Bukkit.createInventory(this, MENU_SIZE, translations.text("menus.rarity.title"));
        buildMenu();
    }

    private void buildMenu() {
        ArenaMenuItemFactory.fill(inventory, Material.BLACK_STAINED_GLASS_PANE);

        inventory.setItem(4, infoItem());
        inventory.setItem(18, actionItem(Material.ARROW, translations.text("menus.common.back"), "back"));

        String legendary = translations.text("rarities.legendary");
        String rare = translations.text("rarities.rare");
        String common = translations.text("rarities.common");

        inventory.setItem(11, adjustableItem(Material.NETHERITE_BLOCK, UiPalette.BRAND + legendary, itemManager.getLegendaryPercent(), "legendary"));

        inventory.setItem(13, adjustableItem(Material.OBSIDIAN, UiPalette.INFO + rare, itemManager.getRarePercent(), "rare"));

        inventory.setItem(15, displayItem(Material.STONE, UiPalette.TEXT + common, itemManager.getCommonPercent()));
    }

    private ItemStack infoItem() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(translations.text("menus.rarity.info-name"));
            meta.setLore(translations.list("menus.rarity.info-lore"));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack displayItem(Material material, String name, int percent) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name + " " + UiPalette.TEXT + percent + "%");
            meta.setLore(List.of(translations.text("menus.rarity.current-chance")));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack actionItem(Material material, String name, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(List.of(translations.text("menus.item-settings.back-lore")));
            meta.getPersistentDataContainer().set(ACTION_KEY, PersistentDataType.STRING, action);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack adjustableItem(Material material, String name, int percent, String action) {
        ItemStack item = displayItem(material, name, percent);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setLore(List.of(translations.text("menus.rarity.control-lore")));
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
            new AdminItemSettingsMenu(clicker, itemManager, hudManager, arenaManager, gameSessionManager).open();
            return;
        }

        int delta = event.isLeftClick() ? 1 : event.isRightClick() ? -1 : 0;
        if (delta == 0) return;
        if (event.isShiftClick()) delta *= 5;

        int legendary = itemManager.getLegendaryPercent();
        int rare = itemManager.getRarePercent();

        if (action.equals("legendary")) {
            legendary += delta;
        } else if (action.equals("rare")) {
            rare += delta;
        } else {
            return;
        }

        itemManager.setRarityPercentages(legendary, rare);
        buildMenu();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public static boolean isAdminConfigMenu(Inventory inv) {
        return inv != null && inv.getHolder() instanceof AdminConfigMenu;
    }
}
