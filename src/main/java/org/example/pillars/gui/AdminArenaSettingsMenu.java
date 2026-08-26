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
import org.example.pillars.enums.ArenaGameMode;
import org.example.pillars.enums.ArenaResetResult;
import org.example.pillars.enums.ItemDeliveryMode;
import org.example.pillars.listeners.LuckyBlockListener;
import org.example.pillars.managers.ArenaManager;
import org.example.pillars.managers.GameSessionManager;
import org.example.pillars.managers.HudManager;
import org.example.pillars.managers.ItemManager;
import org.example.pillars.managers.TranslationManager;
import org.example.pillars.ui.UiPalette;

import java.util.List;

public class AdminArenaSettingsMenu implements InventoryHolder {
    private static final NamespacedKey ACTION_KEY = new NamespacedKey("pillars", "admin_arena_setting_action");
    private static final int MENU_SIZE = 36;

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

        inventory.setItem(27, actionItem(
                Material.ARROW,
                translations.text("menus.common.back"),
                List.of(translations.text("menus.arena-settings.back-lore")),
                "back"
        ));
        inventory.setItem(4, infoItem());
        inventory.setItem(20, actionItem(
                arena.isJoiningOpen() ? Material.OAK_DOOR : Material.IRON_DOOR,
                translations.text(arena.isJoiningOpen()
                        ? "menus.arena-settings.close-joining"
                        : "menus.arena-settings.open-joining"),
                translations.list("menus.arena-settings.joining-lore"),
                "toggle_joining"
        ));

        inventory.setItem(11, adjustableItem(
                Material.PLAYER_HEAD,
                translations.text("menus.arena-settings.players-to-start"),
                arena.getMinPlayers() + "/" + arena.getSpawnPoints().size(),
                "min",
                translations.text("menus.arena-settings.starts-at-lore"),
                translations.text("menus.arena-settings.leaves-stop-countdown"),
                translations.text("menus.arena-settings.players-control")
        ));

        inventory.setItem(22, actionItem(
                arena.getItemDeliveryMode() == ItemDeliveryMode.HOTBAR ? Material.CHEST : Material.DROPPER,
                translations.text("menus.arena-settings.item-delivery-mode"),
                translations.list(
                        "menus.arena-settings.item-delivery-mode-lore",
                        "mode", translations.text(
                                "menus.arena-settings.item-delivery-modes."
                                        + arena.getItemDeliveryMode().configValue()
                        ),
                        "seconds", arena.getItemCooldownSeconds(),
                        "break_time", formatLuckyBlockBreakSeconds()
                ),
                "item_mode"
        ));

        inventory.setItem(23, actionItem(
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

        inventory.setItem(21, actionItem(
                arena.getGameMode() == ArenaGameMode.LUCKY_BLOCKS
                        ? Material.YELLOW_GLAZED_TERRACOTTA
                        : Material.BEDROCK,
                translations.text("menus.arena-settings.game-mode"),
                translations.list(
                        "menus.arena-settings.game-mode-lore",
                        "mode", translations.text(
                                "menus.arena-settings.game-modes." + arena.getGameMode().configValue()
                        ),
                        "seconds", arena.getItemCooldownSeconds(),
                        "break_time", formatLuckyBlockBreakSeconds()
                ),
                "game_mode"
        ));

        inventory.setItem(13, adjustableItem(
                Material.CLOCK,
                translations.text("menus.arena-settings.item-cooldown"),
                arena.getItemCooldownSeconds() + translations.text("units.second-short"),
                "cooldown",
                translations.text("menus.arena-settings.cooldown-minimum"),
                translations.text("menus.arena-settings.cooldown-control")
        ));

        inventory.setItem(15, adjustableItem(
                Material.COMPASS,
                translations.text("menus.arena-settings.border-shrink-time"),
                arena.getBorderShrinkSeconds() + translations.text("units.second-short"),
                "border",
                translations.text("menus.arena-settings.border-shrink-apply-lore"),
                translations.text("menus.arena-settings.border-control")
        ));

        inventory.setItem(24, actionItem(
                Material.SPYGLASS,
                translations.text("menus.arena-settings.spectate"),
                translations.list("menus.arena-settings.spectate-lore"),
                "spectate"
        ));

        if (gameSessionManager.isArenaResetting(arena)) {
            inventory.setItem(35, actionItem(
                    Material.GRAY_DYE,
                    translations.text("menus.arena-settings.reset-in-progress"),
                    translations.list("menus.arena-settings.reset-in-progress-lore"),
                    "reset_in_progress"
            ));
        } else if (resetConfirmationPending) {
            inventory.setItem(35, actionItem(
                    Material.RED_CONCRETE,
                    translations.text("menus.arena-settings.confirm-reset"),
                    translations.list("menus.arena-settings.confirm-reset-lore"),
                    "confirm_reset"
            ));
        } else {
            inventory.setItem(35, actionItem(
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

    private String formatLuckyBlockBreakSeconds() {
        return Double.toString(LuckyBlockListener.BREAK_DURATION_TICKS / 20.0);
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

    private ItemStack adjustableItem(Material material, String name, String value, String action, String... lore) {
        ItemStack item = displayItem(material, name, value, lore);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(ACTION_KEY, PersistentDataType.STRING, action);
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

        if (action.equals("item_mode")) {
            arenaManager.updateArenaItemDeliveryMode(arena, arena.getItemDeliveryMode().next());
            buildMenu();
            return;
        }

        if (action.equals("game_mode")) {
            arenaManager.updateArenaGameMode(arena, arena.getGameMode().next());
            buildMenu();
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

        int direction = event.isLeftClick() ? 1 : event.isRightClick() ? -1 : 0;
        if (direction == 0) return;

        int minPlayers = arena.getMinPlayers();
        int cooldown = arena.getItemCooldownSeconds();

        if (action.equals("min")) {
            minPlayers += direction * (event.isShiftClick() ? 5 : 1);
        } else if (action.equals("cooldown")) {
            cooldown += direction * (event.isShiftClick() ? 5 : 1);
        } else if (action.equals("border")) {
            int adjustedDelta = direction * (event.isShiftClick() ? 60 : 30);
            arenaManager.updateArenaBorderShrinkSeconds(
                    arena,
                    arena.getBorderShrinkSeconds() + adjustedDelta
            );
            buildMenu();
            return;
        }

        arenaManager.updateSafeArenaSettings(arena, minPlayers, cooldown);
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
