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
import org.example.pillars.entities.Arena;
import org.example.pillars.enums.ArenaResetResult;
import org.example.pillars.managers.ArenaManager;
import org.example.pillars.managers.GameSessionManager;
import org.example.pillars.managers.HudManager;
import org.example.pillars.managers.ItemManager;
import org.example.pillars.managers.TranslationManager;
import org.example.pillars.ui.UiPalette;

import java.util.List;

public class AdminArenaSettingsMenu implements InventoryHolder {
    private static final NamespacedKey ACTION_KEY = new NamespacedKey("pillars", "admin_arena_setting_action");
    private static final int MENU_SIZE = 27;

    private final Inventory inventory;
    private final Player player;
    private final ArenaManager arenaManager;
    private final GameSessionManager gameSessionManager;
    private final ItemManager itemManager;
    private final HudManager hudManager;
    private final Arena arena;
    private final TranslationManager translations;

    private boolean resetConfirmationPending;

    public AdminArenaSettingsMenu(Player player, ArenaManager arenaManager, GameSessionManager gameSessionManager, ItemManager itemManager, HudManager hudManager, Arena arena) {
        this.player = player;
        this.arenaManager = arenaManager;
        this.gameSessionManager = gameSessionManager;
        this.itemManager = itemManager;
        this.hudManager = hudManager;
        this.arena = arena;
        this.translations = hudManager.getTranslations();
        this.inventory = Bukkit.createInventory(
                this,
                MENU_SIZE,
                translations.text("menus.arena-settings.title", "arena", arena.getDisplayName())
        );
        buildMenu();
    }

    private void buildMenu() {
        ArenaMenuItemFactory.fill(inventory, Material.BLACK_STAINED_GLASS_PANE);

        inventory.setItem(0, actionItem(
                Material.ARROW,
                translations.text("menus.common.back"),
                List.of(translations.text("menus.arena-settings.back-lore")),
                "back"
        ));
        inventory.setItem(4, infoItem());
        inventory.setItem(8, actionItem(
                arena.isJoiningOpen() ? Material.OAK_DOOR : Material.IRON_DOOR,
                translations.text(arena.isJoiningOpen()
                        ? "menus.arena-settings.close-joining"
                        : "menus.arena-settings.open-joining"),
                translations.list("menus.arena-settings.joining-lore"),
                "toggle_joining"
        ));

        boolean minimumPlayersReached = arena.getMinPlayers() <= ArenaManager.MIN_PLAYERS_TO_START;
        inventory.setItem(9, actionItem(
                minimumPlayersReached ? Material.GRAY_DYE : Material.RED_DYE,
                translations.text(minimumPlayersReached
                        ? "menus.arena-settings.players-start-minimum"
                        : "menus.arena-settings.players-start-decrease"),
                List.of(
                        translations.text("menus.arena-settings.minimum-two"),
                        translations.text("menus.arena-settings.starts-at-lore")
                ),
                minimumPlayersReached ? "minimum_reached" : "min:-1"
        ));
        inventory.setItem(10, displayItem(
                Material.PLAYER_HEAD,
                translations.text("menus.arena-settings.players-to-start"),
                arena.getMinPlayers() + "/" + arena.getSpawnPoints().size(),
                translations.text("menus.arena-settings.starts-at-lore"),
                translations.text("menus.arena-settings.leaves-stop-countdown")
        ));
        inventory.setItem(11, actionItem(Material.LIME_DYE, translations.text("menus.arena-settings.players-start-increase"), List.of(
                translations.text("menus.arena-settings.maximum-capacity"),
                translations.text("menus.arena-settings.starts-at-lore")
        ), "min:1"));

        inventory.setItem(13, actionItem(
                arena.isFloorEnabled() ? floorIcon(arena.getFloorMaterial()) : Material.LIGHT_GRAY_CONCRETE,
                translations.text("menus.arena-settings.floor"),
                translations.list(
                        "menus.arena-settings.floor-lore",
                        "status", translations.text(arena.isFloorEnabled()
                                ? "menus.arena-floor.enabled"
                                : "menus.arena-floor.disabled"),
                        "material", arena.getFloorMaterial().name().toLowerCase().replace('_', ' '),
                        "shape", translations.text("menus.arena-floor.shapes." + arena.getFloorShape().configValue())
                ),
                "floor"
        ));

        inventory.setItem(15, actionItem(
                Material.REDSTONE,
                translations.text("menus.arena-settings.cooldown-decrease"),
                List.of(translations.text("menus.arena-settings.cooldown-minimum")),
                "cooldown:-1"
        ));
        inventory.setItem(16, displayItem(
                Material.CLOCK,
                translations.text("menus.arena-settings.item-cooldown"),
                arena.getItemCooldownSeconds() + translations.text("units.second-short")
        ));
        inventory.setItem(17, actionItem(
                Material.GLOWSTONE_DUST,
                translations.text("menus.arena-settings.cooldown-increase"),
                List.of(translations.text("menus.arena-settings.cooldown-increase-lore")),
                "cooldown:1"
        ));

        inventory.setItem(22, actionItem(
                Material.SPYGLASS,
                translations.text("menus.arena-settings.spectate"),
                translations.list("menus.arena-settings.spectate-lore"),
                "spectate"
        ));

        if (gameSessionManager.isArenaResetting(arena)) {
            inventory.setItem(26, actionItem(
                    Material.GRAY_DYE,
                    translations.text("menus.arena-settings.reset-in-progress"),
                    translations.list("menus.arena-settings.reset-in-progress-lore"),
                    "reset_in_progress"
            ));
        } else if (resetConfirmationPending) {
            inventory.setItem(26, actionItem(
                    Material.RED_CONCRETE,
                    translations.text("menus.arena-settings.confirm-reset"),
                    translations.list("menus.arena-settings.confirm-reset-lore"),
                    "confirm_reset"
            ));
        } else {
            inventory.setItem(26, actionItem(
                    Material.TNT,
                    translations.text("menus.arena-settings.reset-arena"),
                    translations.list("menus.arena-settings.reset-arena-lore"),
                    "reset_arena"
            ));
        }
    }

    private ItemStack infoItem() {
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(translations.text("menus.arena-settings.info-name"));
            meta.setLore(translations.list("menus.arena-settings.info-lore"));
            item.setItemMeta(meta);
        }
        return item;
    }

    private Material floorIcon(Material material) {
        if (material == Material.LAVA) return Material.LAVA_BUCKET;
        if (material == Material.WATER) return Material.WATER_BUCKET;
        return material;
    }

    private ItemStack displayItem(Material material, String name, String value, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name + " " + UiPalette.TEXT + value);
            meta.setLore(lore.length == 0
                    ? List.of(translations.text("menus.common.current-value"))
                    : List.of(lore));
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
            new AdminArenaListMenu(clicker, arenaManager, gameSessionManager, itemManager, hudManager).open();
            return;
        }

        if (action.equals("toggle_joining")) {
            gameSessionManager.setArenaJoiningOpen(arena, !arena.isJoiningOpen());
            hudManager.broadcastArenaJoiningChanged(clicker, arena);
            buildMenu();
            return;
        }

        if (action.equals("spectate")) {
            gameSessionManager.spectateSession(clicker, arena);
            return;
        }

        if (action.equals("floor")) {
            new AdminArenaFloorMenu(clicker, arenaManager, gameSessionManager, itemManager, hudManager, arena).open();
            return;
        }

        if (action.equals("reset_arena")) {
            resetConfirmationPending = true;
            buildMenu();
            return;
        }

        if (action.equals("confirm_reset")) {
            ArenaResetResult result = gameSessionManager.resetArenaManually(clicker, arena);
            resetConfirmationPending = false;
            if (result == ArenaResetResult.STARTED) {
                clicker.closeInventory();
            } else {
                buildMenu();
            }
            return;
        }

        if (action.equals("reset_in_progress")) {
            hudManager.sendManualArenaResetInProgress(clicker, arena.getDisplayName());
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

        int minPlayers = arena.getMinPlayers();
        int cooldown = arena.getItemCooldownSeconds();

        if (parts[0].equals("min")) {
            minPlayers += delta;
        } else if (parts[0].equals("cooldown")) {
            cooldown += delta;
        }

        arenaManager.updateSafeArenaSettings(arena, minPlayers, cooldown);
        hudManager.sendArenaSettingsUpdated(clicker, arena);
        buildMenu();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public static boolean isAdminArenaSettingsMenu(Inventory inv) {
        return inv != null && inv.getHolder() instanceof AdminArenaSettingsMenu;
    }
}
