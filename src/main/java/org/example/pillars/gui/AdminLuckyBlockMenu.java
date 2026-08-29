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
import org.example.pillars.config.LuckyBlockSettings;
import org.example.pillars.managers.ArenaManager;
import org.example.pillars.managers.GameSessionManager;
import org.example.pillars.managers.HudManager;
import org.example.pillars.managers.ItemManager;
import org.example.pillars.managers.TranslationManager;

import java.util.List;

public final class AdminLuckyBlockMenu implements InventoryHolder {
    private static final NamespacedKey ACTION_KEY = new NamespacedKey("pillars", "admin_lucky_block_action");
    private static final int MENU_SIZE = 27;

    private final Player player;
    private final ItemManager itemManager;
    private final HudManager hudManager;
    private final ArenaManager arenaManager;
    private final GameSessionManager gameSessionManager;
    private final TranslationManager translations;
    private final LuckyBlockSettings settings;
    private final Inventory inventory;

    public AdminLuckyBlockMenu(
            Player player,
            ItemManager itemManager,
            HudManager hudManager,
            ArenaManager arenaManager,
            GameSessionManager gameSessionManager,
            LuckyBlockSettings settings
    ) {
        this.player = player;
        this.itemManager = itemManager;
        this.hudManager = hudManager;
        this.arenaManager = arenaManager;
        this.gameSessionManager = gameSessionManager;
        this.translations = hudManager.getTranslations();
        this.settings = settings;
        this.inventory = Bukkit.createInventory(this, MENU_SIZE, translations.text("menus.lucky-blocks.title"));
        buildMenu();
    }

    private void buildMenu() {
        ArenaMenuItemFactory.fill(inventory, Material.BLACK_STAINED_GLASS_PANE);
        inventory.setItem(18, actionItem(
                Material.ARROW,
                translations.text("menus.common.back"),
                List.of(translations.text("menus.lucky-blocks.back-lore")),
                "back"
        ));
        inventory.setItem(4, displayItem(
                Material.SPONGE,
                translations.text("menus.lucky-blocks.info-name"),
                translations.list("menus.lucky-blocks.info-lore")
        ));

        int itemChance = settings.itemChancePercent();
        inventory.setItem(10, actionItem(
                Material.CHEST,
                translations.text("menus.lucky-blocks.item-chance"),
                List.of(
                        translations.text("menus.lucky-blocks.percent", "percent", itemChance),
                        translations.text("menus.lucky-blocks.control-lore")
                ),
                "item_chance"
        ));

        categoryControl(12, "good", Material.EMERALD_BLOCK, "goodPercent", 7);
        categoryControl(14, "neutral", Material.WATER_BUCKET, "neutralPercent", 3);
        categoryControl(16, "bad", Material.TNT, "badPercent", 5);

        boolean blockDamage = settings.tntBlockDamage();
        inventory.setItem(26, actionItem(
                blockDamage ? Material.TNT : Material.GRAY_DYE,
                translations.text("menus.lucky-blocks.tnt-damage"),
                translations.list(
                        "menus.lucky-blocks.tnt-damage-lore",
                        "status", translations.text(blockDamage
                                ? "menus.lucky-blocks.enabled"
                                : "menus.lucky-blocks.disabled")
                ),
                "toggle_tnt_damage"
        ));
    }

    private void categoryControl(int slot, String category, Material icon, String configKey, int fallback) {
        int weight = chance(configKey, fallback);
        inventory.setItem(slot, actionItem(
                icon,
                translations.text("menus.lucky-blocks.categories." + category),
                List.of(
                        translations.text("menus.lucky-blocks.percent", "percent", weight),
                        translations.text("menus.lucky-blocks.control-lore")
                ),
                category
        ));
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
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;

        String action = clicked.getItemMeta().getPersistentDataContainer().get(ACTION_KEY, PersistentDataType.STRING);
        if (action == null) return;
        if (action.equals("back")) {
            new AdminHubMenu(clicker, itemManager, hudManager, arenaManager, gameSessionManager).open();
            return;
        }
        if (action.equals("toggle_tnt_damage")) {
            settings.setTntBlockDamage(!settings.tntBlockDamage());
            buildMenu();
            return;
        }

        int delta = event.isLeftClick() ? 1 : event.isRightClick() ? -1 : 0;
        if (delta == 0) return;

        String configKey = switch (action) {
            case "item_chance" -> "itemChancePercent";
            case "good" -> "goodPercent";
            case "neutral" -> "neutralPercent";
            case "bad" -> "badPercent";
            default -> null;
        };
        if (configKey == null) return;
        adjustChance(configKey, event.isShiftClick() ? delta * 5 : delta);
        buildMenu();
    }

    private void adjustChance(String changedKey, int requestedDelta) {
        int item = chance("itemChancePercent", 85);
        int good = chance("categories.goodPercent", 7);
        int neutral = chance("categories.neutralPercent", 3);
        int bad = chance("categories.badPercent", 5);

        if (changedKey.equals("itemChancePercent")) {
            int delta = Math.max(-item, Math.min(100 - item, requestedDelta));
            if (delta > 0) {
                int remaining = delta;
                int taken = Math.min(good, remaining);
                good -= taken;
                remaining -= taken;
                taken = Math.min(neutral, remaining);
                neutral -= taken;
                remaining -= taken;
                taken = Math.min(bad, remaining);
                bad -= taken;
                remaining -= taken;
                delta -= remaining;
            } else if (delta < 0) {
                good += -delta;
            }
            item += delta;
        } else {
            int current = switch (changedKey) {
                case "goodPercent" -> good;
                case "neutralPercent" -> neutral;
                case "badPercent" -> bad;
                default -> 0;
            };
            int delta = Math.max(-current, Math.min(item, requestedDelta));
            item -= delta;
            switch (changedKey) {
                case "goodPercent" -> good += delta;
                case "neutralPercent" -> neutral += delta;
                case "badPercent" -> bad += delta;
                default -> { }
            }
        }

        settings.setChances(item, good, neutral, bad);
    }

    private int chance(String relativePath, int fallback) {
        return switch (relativePath) {
            case "itemChancePercent" -> settings.itemChancePercent();
            case "categories.goodPercent", "goodPercent" -> settings.goodChancePercent();
            case "categories.neutralPercent", "neutralPercent" -> settings.neutralChancePercent();
            case "categories.badPercent", "badPercent" -> settings.badChancePercent();
            default -> Math.max(0, Math.min(100, fallback));
        };
    }

    private ItemStack actionItem(Material material, String name, List<String> lore, String action) {
        ItemStack item = displayItem(material, name, lore);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(ACTION_KEY, PersistentDataType.STRING, action);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack displayItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public static boolean isAdminLuckyBlockMenu(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof AdminLuckyBlockMenu;
    }
}
