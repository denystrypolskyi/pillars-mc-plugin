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
import org.example.pillars.enums.FloorShape;
import org.example.pillars.managers.ArenaManager;
import org.example.pillars.managers.GameSessionManager;
import org.example.pillars.managers.HudManager;
import org.example.pillars.managers.ItemManager;
import org.example.pillars.managers.TranslationManager;
import org.example.pillars.ui.UiPalette;

import java.util.List;
import java.util.Locale;

public final class AdminArenaFloorMenu implements InventoryHolder {
    private static final NamespacedKey ACTION_KEY = new NamespacedKey("pillars", "admin_arena_floor_action");
    private static final int MENU_SIZE = 45;

    private final Inventory inventory;
    private final Player player;
    private final ArenaManager arenaManager;
    private final GameSessionManager gameSessionManager;
    private final ItemManager itemManager;
    private final HudManager hudManager;
    private final Arena arena;
    private final TranslationManager translations;
    private boolean applyConfirmationPending;
    private boolean symmetryConfirmationPending;

    public AdminArenaFloorMenu(
            Player player,
            ArenaManager arenaManager,
            GameSessionManager gameSessionManager,
            ItemManager itemManager,
            HudManager hudManager,
            Arena arena
    ) {
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
                translations.text("menus.arena-floor.title", "arena", arena.getDisplayName())
        );
        buildMenu();
    }

    private void buildMenu() {
        ArenaMenuItemFactory.fill(inventory, Material.BLACK_STAINED_GLASS_PANE);
        int minimumFloorY = arenaManager.getMinimumFloorY(arena);
        int maximumFloorY = arenaManager.getMaximumFloorY(arena);

        inventory.setItem(36, actionItem(
                Material.ARROW,
                translations.text("menus.common.back"),
                List.of(translations.text("menus.arena-floor.back-lore")),
                "back"
        ));
        inventory.setItem(4, ArenaMenuItemFactory.visualItem(
                materialIcon(arena.getFloorMaterial()),
                translations.text("menus.arena-floor.info-name"),
                translations.list("menus.arena-floor.info-lore")
        ));

        inventory.setItem(10, actionItem(
                arena.isFloorEnabled() ? Material.LIME_DYE : Material.GRAY_DYE,
                translations.text(arena.isFloorEnabled()
                        ? "menus.arena-floor.disable"
                        : "menus.arena-floor.enable"),
                translations.list("menus.arena-floor.toggle-lore"),
                "toggle"
        ));
        inventory.setItem(13, actionItem(
                materialIcon(arena.getFloorMaterial()),
                translations.text("menus.arena-floor.material"),
                translations.list(
                        "menus.arena-floor.material-lore",
                        "material", displayMaterial(arena.getFloorMaterial())
                ),
                "material"
        ));
        inventory.setItem(16, symmetryItem());
        inventory.setItem(44, applyItem());

        inventory.setItem(20, shapeItem(FloorShape.SQUARE));
        inventory.setItem(22, shapeItem(FloorShape.SQUARE_RING));
        inventory.setItem(24, shapeItem(FloorShape.ISLANDS));

        inventory.setItem(30, adjustableItem(
                Material.COMPASS,
                translations.text("menus.arena-floor.radius"),
                String.valueOf(arena.getFloorRadius()),
                "radius",
                translations.text("menus.arena-floor.adjust-control")
        ));
        inventory.setItem(32, adjustableItem(
                Material.SCAFFOLDING,
                translations.text("menus.arena-floor.y"),
                arena.getFloorY() + " " + UiPalette.SEPARATOR + "(" + minimumFloorY + "–" + maximumFloorY + ")",
                "y",
                translations.text(
                        "menus.arena-floor.height-control",
                        "minimum", minimumFloorY,
                        "maximum", maximumFloorY
                ),
                translations.text("menus.arena-floor.adjust-control")
        ));
    }

    private ItemStack symmetryItem() {
        if (gameSessionManager.isArenaResetting(arena)) {
            return actionItem(
                    Material.GRAY_DYE,
                    translations.text("menus.arena-floor.symmetry-in-progress"),
                    translations.list("menus.arena-floor.apply-in-progress-lore"),
                    "apply_in_progress"
            );
        }
        if (symmetryConfirmationPending) {
            return actionItem(
                    Material.RED_CONCRETE,
                    translations.text("menus.arena-floor.confirm-symmetry"),
                    translations.list("menus.arena-floor.confirm-symmetry-lore"),
                    "confirm_symmetry"
            );
        }
        return actionItem(
                Material.ENDER_EYE,
                translations.text("menus.arena-floor.symmetrize-spawns"),
                translations.list("menus.arena-floor.symmetrize-spawns-lore"),
                "symmetrize"
        );
    }

    private ItemStack applyItem() {
        if (gameSessionManager.isArenaResetting(arena)) {
            return actionItem(
                    Material.GRAY_DYE,
                    translations.text("menus.arena-floor.apply-in-progress"),
                    translations.list("menus.arena-floor.apply-in-progress-lore"),
                    "apply_in_progress"
            );
        }
        if (applyConfirmationPending) {
            return actionItem(
                    Material.RED_CONCRETE,
                    translations.text("menus.arena-floor.confirm-apply"),
                    translations.list("menus.arena-floor.confirm-apply-lore"),
                    "confirm_apply"
            );
        }
        return actionItem(
                Material.TNT,
                translations.text("menus.arena-floor.apply-title"),
                translations.list("menus.arena-floor.apply-lore"),
                "apply"
        );
    }

    private ItemStack shapeItem(FloorShape shape) {
        boolean selected = arena.getFloorShape() == shape;
        return actionItem(
                shapeIcon(shape),
                translations.text("menus.arena-floor.shape-option", "shape", shapeName(shape)),
                translations.list(selected
                        ? "menus.arena-floor.shape-selected-lore"
                        : "menus.arena-floor.shape-select-lore"),
                "shape:" + shape.configValue()
        );
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
            new AdminArenaSettingsMenu(clicker, arenaManager, gameSessionManager, itemManager, hudManager, arena).open();
            return;
        }
        if (action.equals("toggle")) {
            save(!arena.isFloorEnabled(), arena.getFloorMaterial(), arena.getFloorShape(), arena.getFloorRadius(), arena.getFloorY());
            return;
        }
        if (action.equals("material")) {
            new AdminFloorMaterialMenu(clicker, arenaManager, gameSessionManager, itemManager, hudManager, arena).open();
            return;
        }
        if (action.equals("symmetrize")) {
            symmetryConfirmationPending = true;
            applyConfirmationPending = false;
            buildMenu();
            return;
        }
        if (action.equals("confirm_symmetry")) {
            symmetryConfirmationPending = false;
            if (!arenaManager.symmetrizeArenaSpawns(arena)) {
                clicker.sendMessage(translations.text("messages.spawn-symmetry-failed"));
                buildMenu();
                return;
            }
            ArenaResetResult result = gameSessionManager.resetArenaManually(clicker, arena);
            if (result == ArenaResetResult.STARTED) {
                clicker.closeInventory();
            } else {
                buildMenu();
            }
            return;
        }
        if (action.equals("apply")) {
            applyConfirmationPending = true;
            symmetryConfirmationPending = false;
            buildMenu();
            return;
        }
        if (action.equals("confirm_apply")) {
            ArenaResetResult result = gameSessionManager.resetArenaManually(clicker, arena);
            applyConfirmationPending = false;
            if (result == ArenaResetResult.STARTED) {
                clicker.closeInventory();
            } else {
                buildMenu();
            }
            return;
        }
        if (action.equals("apply_in_progress")) {
            hudManager.sendManualArenaResetInProgress(clicker, arena.getDisplayName());
            return;
        }
        if (action.startsWith("shape:")) {
            FloorShape shape = FloorShape.fromConfig(action.substring("shape:".length()));
            save(arena.isFloorEnabled(), arena.getFloorMaterial(), shape, arena.getFloorRadius(), arena.getFloorY());
            return;
        }

        int delta = event.isLeftClick() ? 1 : event.isRightClick() ? -1 : 0;
        if (delta == 0) return;
        if (event.isShiftClick()) delta *= 5;

        int radius = arena.getFloorRadius();
        int y = arena.getFloorY();
        if (action.equals("radius")) radius += delta;
        if (action.equals("y")) y += delta;
        save(arena.isFloorEnabled(), arena.getFloorMaterial(), arena.getFloorShape(), radius, y);
    }

    private void save(boolean enabled, Material material, FloorShape shape, int radius, int y) {
        arenaManager.updateArenaFloorSettings(arena, enabled, material, shape, radius, y);
        applyConfirmationPending = false;
        symmetryConfirmationPending = false;
        buildMenu();
    }

    private String displayMaterial(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private Material materialIcon(Material material) {
        if (material == Material.LAVA) return Material.LAVA_BUCKET;
        if (material == Material.WATER) return Material.WATER_BUCKET;
        return material;
    }

    private String shapeName(FloorShape shape) {
        return translations.text("menus.arena-floor.shapes." + shape.configValue());
    }

    private Material shapeIcon(FloorShape shape) {
        return switch (shape) {
            case SQUARE -> Material.SMOOTH_STONE;
            case SQUARE_RING -> Material.ENDER_PEARL;
            case ISLANDS -> Material.GRASS_BLOCK;
        };
    }

    private ItemStack displayItem(Material material, String name, String value) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name + " " + UiPalette.TEXT + value);
            meta.setLore(List.of(translations.text("menus.common.current-value")));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack adjustableItem(Material material, String name, String value, String action, String... lore) {
        ItemStack item = displayItem(material, name, value);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setLore(List.of(lore));
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

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public static boolean isAdminArenaFloorMenu(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof AdminArenaFloorMenu;
    }
}
