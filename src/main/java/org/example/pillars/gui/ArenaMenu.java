package org.example.pillars.gui;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataType;
import org.example.pillars.entities.Arena;
import org.example.pillars.enums.GameState;
import org.example.pillars.managers.ArenaManager;
import org.example.pillars.managers.GameSessionManager;
import org.example.pillars.managers.HudManager;
import org.example.pillars.managers.TranslationManager;

import java.util.List;

public class ArenaMenu implements InventoryHolder {

    private final Inventory inventory;
    private final Player player;
    private final ArenaManager arenaManager;
    private final GameSessionManager sessionManager;
    private final HudManager hudManager;
    private final TranslationManager translations;

    private static final NamespacedKey ARENA_KEY = new NamespacedKey("pillars", "arena_worldname");
    private static final NamespacedKey ACTION_KEY = new NamespacedKey("pillars", "arena_action");
    private static final int MENU_SIZE = 54;
    public ArenaMenu(Player player, ArenaManager arenaManager, GameSessionManager sessionManager, HudManager hudManager) {
        this.player = player;
        this.arenaManager = arenaManager;
        this.sessionManager = sessionManager;
        this.hudManager = hudManager;
        this.translations = hudManager.getTranslations();

        this.inventory = Bukkit.createInventory(this, MENU_SIZE, translations.text("menus.arena-selection.title"));
        buildMenu();
    }

    private void buildMenu() {
        ArenaMenuItemFactory.fill(inventory, Material.BLACK_STAINED_GLASS_PANE);

        List<Arena> arenas = arenaManager.getArenas().stream()
                .sorted(ArenaMenuItemFactory.arenaListOrder())
                .toList();

        if (arenas.isEmpty()) {
            inventory.setItem(22, ArenaMenuItemFactory.visualItem(
                    Material.BARRIER,
                    translations.text("menus.arena-selection.empty"),
                    List.of()
            ));
            return;
        }

        ArenaMenuItemFactory.placeArenaItems(
                inventory,
                arenas,
                arena -> ArenaMenuItemFactory.playerArenaItem(
                        arena,
                        sessionManager.getSession(arena),
                        ARENA_KEY,
                        translations
                )
        );

        inventory.setItem(49, ArenaMenuItemFactory.quickJoinItem(ACTION_KEY, translations));
    }

    public void open() {
        player.openInventory(inventory);
    }

    public void handleClick(InventoryClickEvent event) {
        event.setCancelled(true);

        if (event.getCurrentItem() == null || !event.getCurrentItem().hasItemMeta()) return;

        String action = event.getCurrentItem().getItemMeta()
                .getPersistentDataContainer().get(ACTION_KEY, PersistentDataType.STRING);

        if ("quick-join".equals(action)) {
            Arena arena = sessionManager.findQuickJoinArena();
            if (arena == null) {
                hudManager.sendNoJoinableSession(player);
                return;
            }

            sessionManager.joinSession(player, arena);
            player.closeInventory();
            return;
        }

        String worldName = event.getCurrentItem().getItemMeta()
                .getPersistentDataContainer().get(ARENA_KEY, PersistentDataType.STRING);

        if (worldName == null) return;

        Arena arena = arenaManager.getArena(worldName);
        if (arena == null) return;

        if (!arena.isJoiningOpen()) {
            hudManager.sendArenaClosed(player, arena.getDisplayName());
            return;
        }

        if (sessionManager.getSession(arena) != null &&
                sessionManager.getSession(arena).getState() != GameState.WAITING &&
                sessionManager.getSession(arena).getState() != GameState.STARTING) {
            hudManager.sendGameAlreadyRunning(player);
            return;
        }

        sessionManager.joinSession(player, arena);
        player.closeInventory();
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public static boolean isArenaMenu(Inventory inv) {
        return inv != null && inv.getHolder() instanceof ArenaMenu;
    }
}
