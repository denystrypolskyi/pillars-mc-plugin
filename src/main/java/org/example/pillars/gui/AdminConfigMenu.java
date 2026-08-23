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
        inventory.setItem(0, actionItem(Material.ARROW, translations.text("menus.common.back"), "back"));

        String legendary = translations.text("rarities.legendary");
        String rare = translations.text("rarities.rare");
        String common = translations.text("rarities.common");

        inventory.setItem(9, actionItem(Material.RED_DYE, translations.text("menus.rarity.decrease-five", "rarity", legendary), "legendary:-5"));
        inventory.setItem(10, actionItem(Material.REDSTONE, translations.text("menus.rarity.decrease-one", "rarity", legendary), "legendary:-1"));
        inventory.setItem(11, displayItem(Material.NETHERITE_BLOCK, UiPalette.BRAND + legendary, itemManager.getLegendaryPercent()));
        inventory.setItem(12, actionItem(Material.GLOWSTONE_DUST, translations.text("menus.rarity.increase-one", "rarity", legendary), "legendary:1"));
        inventory.setItem(13, actionItem(Material.LIME_DYE, translations.text("menus.rarity.increase-five", "rarity", legendary), "legendary:5"));

        inventory.setItem(18, actionItem(Material.ORANGE_DYE, translations.text("menus.rarity.decrease-five", "rarity", rare), "rare:-5"));
        inventory.setItem(19, actionItem(Material.COPPER_INGOT, translations.text("menus.rarity.decrease-one", "rarity", rare), "rare:-1"));
        inventory.setItem(20, displayItem(Material.OBSIDIAN, UiPalette.INFO + rare, itemManager.getRarePercent()));
        inventory.setItem(21, actionItem(Material.LAPIS_LAZULI, translations.text("menus.rarity.increase-one", "rarity", rare), "rare:1"));
        inventory.setItem(22, actionItem(Material.EMERALD, translations.text("menus.rarity.increase-five", "rarity", rare), "rare:5"));

        inventory.setItem(16, displayItem(Material.STONE, UiPalette.TEXT + common, itemManager.getCommonPercent()));
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
            meta.setLore(List.of(translations.text("menus.rarity.update-lore")));
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
            return;
        }

        String[] parts = action.split(":");
        if (parts.length != 2) return;

        int delta;
        try {
            delta = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ignored) {
            return;
        }

        int legendary = itemManager.getLegendaryPercent();
        int rare = itemManager.getRarePercent();

        if (parts[0].equals("legendary")) {
            legendary += delta;
        } else if (parts[0].equals("rare")) {
            rare += delta;
        }

        itemManager.setRarityPercentages(legendary, rare);
        buildMenu();
        hudManager.sendAdminConfigUpdated(clicker, itemManager.getCommonPercent(), itemManager.getRarePercent(), itemManager.getLegendaryPercent());
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public static boolean isAdminConfigMenu(Inventory inv) {
        return inv != null && inv.getHolder() instanceof AdminConfigMenu;
    }
}
