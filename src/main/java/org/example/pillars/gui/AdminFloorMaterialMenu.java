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
import org.example.pillars.managers.ArenaManager;
import org.example.pillars.managers.GameSessionManager;
import org.example.pillars.managers.HudManager;
import org.example.pillars.managers.ItemManager;
import org.example.pillars.managers.TranslationManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class AdminFloorMaterialMenu implements InventoryHolder {
    private static final NamespacedKey ACTION_KEY = new NamespacedKey("pillars", "floor_material_action");
    private static final NamespacedKey MATERIAL_KEY = new NamespacedKey("pillars", "floor_material_value");
    private static final int MENU_SIZE = 54;
    private static final int PAGE_SIZE = 45;
    private static final List<Material> COMMON_MATERIALS = List.of(
            Material.SLIME_BLOCK,
            Material.HONEY_BLOCK,
            Material.STONE,
            Material.SMOOTH_STONE,
            Material.COBBLESTONE,
            Material.DEEPSLATE_TILES,
            Material.GLASS,
            Material.OBSIDIAN,
            Material.MAGMA_BLOCK,
            Material.ICE,
            Material.PACKED_ICE,
            Material.WHITE_CONCRETE,
            Material.BLACK_CONCRETE,
            Material.LAVA,
            Material.WATER
    );

    private final Player player;
    private final ArenaManager arenaManager;
    private final GameSessionManager gameSessionManager;
    private final ItemManager itemManager;
    private final HudManager hudManager;
    private final Arena arena;
    private final TranslationManager translations;
    private final Inventory inventory;
    private final List<Material> materials;
    private final int page;

    public AdminFloorMaterialMenu(
            Player player,
            ArenaManager arenaManager,
            GameSessionManager gameSessionManager,
            ItemManager itemManager,
            HudManager hudManager,
            Arena arena
    ) {
        this(player, arenaManager, gameSessionManager, itemManager, hudManager, arena, 0);
    }

    private AdminFloorMaterialMenu(
            Player player,
            ArenaManager arenaManager,
            GameSessionManager gameSessionManager,
            ItemManager itemManager,
            HudManager hudManager,
            Arena arena,
            int requestedPage
    ) {
        this.player = player;
        this.arenaManager = arenaManager;
        this.gameSessionManager = gameSessionManager;
        this.itemManager = itemManager;
        this.hudManager = hudManager;
        this.arena = arena;
        this.translations = hudManager.getTranslations();
        this.materials = availableMaterials();
        int maximumPage = Math.max(0, (materials.size() - 1) / PAGE_SIZE);
        this.page = Math.max(0, Math.min(maximumPage, requestedPage));
        this.inventory = Bukkit.createInventory(
                this,
                MENU_SIZE,
                translations.text("menus.floor-material.title")
        );
        buildMenu();
    }

    private void buildMenu() {
        ArenaMenuItemFactory.fill(inventory, Material.BLACK_STAINED_GLASS_PANE);
        int start = page * PAGE_SIZE;
        int end = Math.min(materials.size(), start + PAGE_SIZE);
        for (int index = start; index < end; index++) {
            inventory.setItem(index - start, materialItem(materials.get(index)));
        }

        inventory.setItem(45, actionItem(
                Material.ARROW,
                translations.text("menus.common.back"),
                "back"
        ));
        if (page > 0) {
            inventory.setItem(48, actionItem(
                    Material.ARROW,
                    translations.text("menus.floor-material.previous-page"),
                    "page:" + (page - 1)
            ));
        }
        inventory.setItem(53, ArenaMenuItemFactory.visualItem(
                materialIcon(arena.getFloorMaterial()),
                translations.text("menus.floor-material.current"),
                List.of(translations.text(
                        "menus.floor-material.current-lore",
                        "material", displayMaterial(arena.getFloorMaterial())
                ))
        ));
        int pageCount = Math.max(1, (materials.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        inventory.setItem(49, ArenaMenuItemFactory.visualItem(
                Material.PAPER,
                translations.text("menus.floor-material.page", "current", page + 1, "total", pageCount),
                List.of()
        ));
        if (page + 1 < pageCount) {
            inventory.setItem(50, actionItem(
                    Material.ARROW,
                    translations.text("menus.floor-material.next-page"),
                    "page:" + (page + 1)
            ));
        }
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
        ItemMeta meta = clicked.getItemMeta();

        String materialName = meta.getPersistentDataContainer().get(MATERIAL_KEY, PersistentDataType.STRING);
        if (materialName != null) {
            Material material = Material.matchMaterial(materialName);
            if (material != null) {
                arenaManager.updateArenaFloorSettings(
                        arena,
                        arena.isFloorEnabled(),
                        material,
                        arena.getFloorShape(),
                        arena.getFloorRadius(),
                        arena.getFloorY()
                );
                new AdminArenaFloorMenu(clicker, arenaManager, gameSessionManager, itemManager, hudManager, arena).open();
            }
            return;
        }

        String action = meta.getPersistentDataContainer().get(ACTION_KEY, PersistentDataType.STRING);
        if (action == null) return;
        if (action.equals("back")) {
            new AdminArenaFloorMenu(clicker, arenaManager, gameSessionManager, itemManager, hudManager, arena).open();
            return;
        }
        if (action.startsWith("page:")) {
            try {
                int targetPage = Integer.parseInt(action.substring("page:".length()));
                new AdminFloorMaterialMenu(
                        clicker,
                        arenaManager,
                        gameSessionManager,
                        itemManager,
                        hudManager,
                        arena,
                        targetPage
                ).open();
            } catch (NumberFormatException ignored) {
                // Ignore malformed GUI data.
            }
        }
    }

    private List<Material> availableMaterials() {
        List<Material> result = new ArrayList<>(COMMON_MATERIALS);
        Arrays.stream(Material.values())
                .filter(material -> material.isBlock() && material.isItem() && material.isSolid() && !material.isAir())
                .filter(material -> !result.contains(material))
                .sorted(Comparator.comparing(Material::name))
                .forEach(result::add);
        return List.copyOf(result);
    }

    private ItemStack materialItem(Material material) {
        ItemStack item = new ItemStack(materialIcon(material));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            boolean selected = arena.getFloorMaterial() == material;
            meta.setDisplayName(translations.text(
                    selected ? "menus.floor-material.selected-name" : "menus.floor-material.material-name",
                    "material", displayMaterial(material)
            ));
            meta.setLore(translations.list(selected
                    ? "menus.floor-material.selected-lore"
                    : "menus.floor-material.select-lore"));
            meta.getPersistentDataContainer().set(MATERIAL_KEY, PersistentDataType.STRING, material.name());
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack actionItem(Material material, String name, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.getPersistentDataContainer().set(ACTION_KEY, PersistentDataType.STRING, action);
            item.setItemMeta(meta);
        }
        return item;
    }

    private Material materialIcon(Material material) {
        if (material == Material.LAVA) return Material.LAVA_BUCKET;
        if (material == Material.WATER) return Material.WATER_BUCKET;
        return material;
    }

    private String displayMaterial(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public static boolean isAdminFloorMaterialMenu(Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof AdminFloorMaterialMenu;
    }
}
