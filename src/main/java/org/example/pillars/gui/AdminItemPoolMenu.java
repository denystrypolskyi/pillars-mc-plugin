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

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AdminItemPoolMenu implements InventoryHolder {
    private static final NamespacedKey ACTION_KEY = new NamespacedKey("pillars", "admin_pool_action");
    private static final NamespacedKey MATERIAL_KEY = new NamespacedKey("pillars", "admin_pool_material");
    private static final int MENU_SIZE = 54;

    private final Inventory inventory;
    private final Player player;
    private final ArenaManager arenaManager;
    private final GameSessionManager gameSessionManager;
    private final ItemManager itemManager;
    private final HudManager hudManager;
    private final String rarity;
    private final int page;
    private final TranslationManager translations;

    public AdminItemPoolMenu(Player player, ArenaManager arenaManager, GameSessionManager gameSessionManager, ItemManager itemManager, HudManager hudManager, String rarity) {
        this(player, arenaManager, gameSessionManager, itemManager, hudManager, rarity, 0);
    }

    public AdminItemPoolMenu(Player player, ArenaManager arenaManager, GameSessionManager gameSessionManager, ItemManager itemManager, HudManager hudManager, String rarity, int page) {
        this.player = player;
        this.arenaManager = arenaManager;
        this.gameSessionManager = gameSessionManager;
        this.itemManager = itemManager;
        this.hudManager = hudManager;
        this.rarity = rarity.toLowerCase();
        this.page = Math.max(0, page);
        this.translations = hudManager.getTranslations();
        this.inventory = Bukkit.createInventory(
                this,
                MENU_SIZE,
                translations.text("menus.item-pool.title", "rarity", localizedRarity())
        );
        buildMenu();
    }

    private void buildMenu() {
        for (int i = 0; i < MENU_SIZE; i++) {
            inventory.setItem(i, filler());
        }

        inventory.setItem(0, actionItem(
                Material.ARROW,
                translations.text("menus.common.back"),
                List.of(translations.text("menus.common.back-admin-lore")),
                "back"
        ));
        inventory.setItem(4, infoItem());
        inventory.setItem(8, actionItem(
                Material.LIME_DYE,
                translations.text("menus.item-pool.add-held"),
                translations.list("menus.item-pool.add-held-lore"),
                "add_held"
        ));

        List<Map.Entry<Material, Integer>> entries = itemManager.getItemPool(rarity).entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().name()))
                .toList();
        int pageSize = MENU_SIZE - 18;
        int maxPage = Math.max(0, (entries.size() - 1) / pageSize);
        int currentPage = Math.min(page, maxPage);
        int startIndex = currentPage * pageSize;
        int endIndex = Math.min(entries.size(), startIndex + pageSize);

        int slot = 9;
        for (Map.Entry<Material, Integer> entry : entries.subList(startIndex, endIndex)) {
            inventory.setItem(slot++, poolItem(entry.getKey(), entry.getValue()));
        }

        if (currentPage > 0) {
            inventory.setItem(45, actionItem(
                    Material.ARROW,
                    translations.text("menus.item-pool.previous-page"),
                    List.of(translations.text(
                            "menus.item-pool.page-of",
                            "current", currentPage,
                            "total", maxPage + 1
                    )),
                    "page:" + (currentPage - 1)
            ));
        }

        inventory.setItem(49, pageItem(currentPage, maxPage));

        if (currentPage < maxPage) {
            inventory.setItem(53, actionItem(
                    Material.ARROW,
                    translations.text("menus.item-pool.next-page"),
                    List.of(translations.text(
                            "menus.item-pool.page-of",
                            "current", currentPage + 2,
                            "total", maxPage + 1
                    )),
                    "page:" + (currentPage + 1)
            ));
        }
    }

    private ItemStack filler() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack infoItem() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(translations.text("menus.item-pool.info-name", "rarity", localizedRarity()));
            meta.setLore(translations.list("menus.item-pool.info-lore"));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack poolItem(Material material, int weight) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§f" + material.name().toLowerCase(Locale.ROOT).replace('_', ' '));
            meta.setLore(List.of(
                    translations.text("menus.item-pool.weight", "weight", weight),
                    translations.text("menus.item-pool.disable")
            ));
            meta.getPersistentDataContainer().set(ACTION_KEY, PersistentDataType.STRING, "remove");
            meta.getPersistentDataContainer().set(MATERIAL_KEY, PersistentDataType.STRING, material.name());
            item.setItemMeta(meta);
        }
        return item;
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

    private ItemStack pageItem(int currentPage, int maxPage) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            int itemCount = itemManager.getItemPool(rarity).size();
            meta.setDisplayName(translations.text(
                    "menus.item-pool.page",
                    "current", currentPage + 1,
                    "total", maxPage + 1
            ));
            meta.setLore(List.of(translations.text(
                    "menus.item-pool.enabled-count",
                    "count", itemCount,
                    "items", translations.plural("units.enabled-item", itemCount)
            )));
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

        ItemMeta meta = event.getCurrentItem().getItemMeta();
        String action = meta.getPersistentDataContainer().get(ACTION_KEY, PersistentDataType.STRING);
        if (action == null) return;

        switch (action) {
            case "back" -> new AdminHubMenu(clicker, itemManager, hudManager, arenaManager, gameSessionManager).open();
            case "add_held" -> addHeldItem(clicker);
            case "remove" -> removeItem(clicker, meta);
            default -> {
                if (action.startsWith("page:")) {
                    openPage(clicker, action);
                }
            }
        }
    }

    private void openPage(Player clicker, String action) {
        try {
            int targetPage = Integer.parseInt(action.substring("page:".length()));
            new AdminItemPoolMenu(clicker, arenaManager, gameSessionManager, itemManager, hudManager, rarity, targetPage).open();
        } catch (NumberFormatException ignored) {
        }
    }

    private void addHeldItem(Player clicker) {
        ItemStack heldItem = clicker.getInventory().getItemInMainHand();
        if (heldItem.getType() == Material.AIR) {
            hudManager.sendHoldItemToConfigure(clicker);
            return;
        }

        if (itemManager.addItemWithDefaultWeight(rarity, heldItem.getType())) {
            hudManager.sendItemConfigured(clicker, heldItem.getType(), rarity, itemManager.getDefaultWeight(rarity));
            new AdminItemPoolMenu(clicker, arenaManager, gameSessionManager, itemManager, hudManager, rarity, page).open();
        }
    }

    private void removeItem(Player clicker, ItemMeta meta) {
        String materialName = meta.getPersistentDataContainer().get(MATERIAL_KEY, PersistentDataType.STRING);
        Material material = Material.matchMaterial(materialName == null ? "" : materialName);
        if (material == null) {
            hudManager.sendUnknownMaterial(clicker);
            return;
        }

        if (itemManager.removeItem(rarity, material)) {
            hudManager.sendItemRemoved(clicker, material, rarity);
            new AdminItemPoolMenu(clicker, arenaManager, gameSessionManager, itemManager, hudManager, rarity, page).open();
        }
    }

    private String localizedRarity() {
        return translations.text("rarities." + rarity);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public static boolean isAdminItemPoolMenu(Inventory inv) {
        return inv != null && inv.getHolder() instanceof AdminItemPoolMenu;
    }
}
