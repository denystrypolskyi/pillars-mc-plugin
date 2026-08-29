package org.example.pillars.gui;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.example.pillars.GameSession;
import org.example.pillars.entities.Arena;
import org.example.pillars.enums.GameState;
import org.example.pillars.managers.TranslationManager;
import org.example.pillars.ui.UiPalette;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

final class ArenaMenuItemFactory {
    private static final int[] ARENA_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private ArenaMenuItemFactory() {
    }

    static Comparator<Arena> arenaListOrder() {
        return Comparator
                .comparingInt((Arena arena) -> arena.getSpawnPoints().size())
                .thenComparing(Arena::getDisplayName);
    }

    static void fill(Inventory inventory, Material material) {
        ItemStack filler = new ItemStack(material);
        ItemMeta fillerMeta = filler.getItemMeta();
        if (fillerMeta != null) {
            fillerMeta.setDisplayName(" ");
            filler.setItemMeta(fillerMeta);
        }

        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
    }

    static ItemStack visualItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    static void placeArenaItems(
            Inventory inventory,
            List<Arena> arenas,
            int page,
            Function<Arena, ItemStack> itemFactory
    ) {
        int start = Math.max(0, page) * ARENA_SLOTS.length;
        int end = Math.min(arenas.size(), start + ARENA_SLOTS.length);
        placeIntoSlots(inventory, arenas.subList(start, end), itemFactory, ARENA_SLOTS);
    }

    static int arenaPageCount(int arenaCount) {
        return Math.max(1, (arenaCount + ARENA_SLOTS.length - 1) / ARENA_SLOTS.length);
    }

    static ItemStack pageItem(
            Material material,
            String name,
            String lore,
            NamespacedKey actionKey,
            String action
    ) {
        ItemStack item = visualItem(material, name, List.of(lore));
        if (action == null) return item;

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
            item.setItemMeta(meta);
        }
        return item;
    }

    static ItemStack quickJoinItem(NamespacedKey actionKey, TranslationManager translations) {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(translations.text("menus.arena-selection.quick-join"));
            meta.setLore(translations.list("menus.arena-selection.quick-join-lore"));
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "quick-join");
            item.setItemMeta(meta);
        }
        return item;
    }

    static ItemStack playerArenaItem(
            Arena arena,
            GameSession session,
            NamespacedKey arenaKey,
            TranslationManager translations
    ) {
        ArenaView view = ArenaView.from(arena, session);
        ItemStack item = new ItemStack(view.material());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(view.itemColor() + UiPalette.BOLD + arena.getDisplayName());
            List<String> lore = arenaDetailsLore(arena, view, translations);
            lore.add("");
            lore.add(view.playerActionLore(translations));

            meta.setLore(lore);
            meta.getPersistentDataContainer().set(arenaKey, PersistentDataType.STRING, arena.getWorldName());
            item.setItemMeta(meta);
        }
        return item;
    }

    static ItemStack adminArenaItem(
            Arena arena,
            GameSession session,
            NamespacedKey actionKey,
            NamespacedKey arenaKey,
            TranslationManager translations
    ) {
        ArenaView view = ArenaView.from(arena, session);
        ItemStack item = new ItemStack(view.material());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<String> lore = arenaDetailsLore(arena, view, translations);
            lore.add("");
            lore.add(translations.text("arena-view.edit-action"));

            meta.setDisplayName(view.itemColor() + UiPalette.BOLD + arena.getDisplayName());
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, "edit");
            meta.getPersistentDataContainer().set(arenaKey, PersistentDataType.STRING, arena.getWorldName());
            item.setItemMeta(meta);
        }
        return item;
    }

    private static List<String> arenaDetailsLore(
            Arena arena,
            ArenaView view,
            TranslationManager translations
    ) {
        List<String> lore = new ArrayList<>(List.of(
                translations.text(
                        "arena-view.capacity",
                        "maximum", view.maxPlayers(),
                        "players", translations.plural("units.player", view.maxPlayers())
                ),
                "",
                translations.text(
                        "arena-view.status",
                        "value", view.stateColor() + view.stateDisplay(translations)
                ),
                translations.text("arena-view.joining", "value", view.joiningDisplay(translations)),
                translations.text(
                        "arena-view.players",
                        "current", view.currentPlayers(),
                        "maximum", view.maxPlayers()
                ),
                translations.text(
                        "arena-view.starts-at",
                        "minimum", arena.getMinPlayers(),
                        "players", translations.plural("units.player-at", arena.getMinPlayers())
                ),
                translations.text(
                        "arena-view.item-cooldown",
                        "seconds", arena.getItemCooldownSeconds()
                ),
                "",
                translations.text(
                        "arena-view.platform",
                        "value", translations.text("arena-view.platform-state."
                                + (arena.isFloorEnabled() ? "enabled" : "disabled"))
                )
        ));
        if (arena.isFloorEnabled()) {
            lore.add(translations.text(
                    "arena-view.platform-material",
                    "value", translations.displayName(arena.getFloorMaterial().name())
            ));
            lore.add(translations.text(
                    "arena-view.platform-shape",
                    "value", translations.text(
                            "menus.arena-floor.shapes." + arena.getFloorShape().configValue()
                    )
            ));
        }
        lore.add(translations.text(
                "arena-view.game-mode",
                "value", translations.text(
                        "arena-view.game-modes." + arena.getGameMode().configValue()
                )
        ));
        lore.add(translations.text(
                "arena-view.item-delivery-mode",
                "value", translations.text(
                        "arena-view.item-delivery-modes." + arena.getItemDeliveryMode().configValue()
                )
        ));
        return lore;
    }

    private static void placeIntoSlots(
            Inventory inventory,
            List<Arena> arenas,
            Function<Arena, ItemStack> itemFactory,
            int... slots
    ) {
        int count = Math.min(arenas.size(), slots.length);
        for (int i = 0; i < count; i++) {
            inventory.setItem(slots[i], itemFactory.apply(arenas.get(i)));
        }

    }

    private record ArenaView(
            GameState state,
            int currentPlayers,
            int maxPlayers,
            boolean full,
            boolean joinable
    ) {
        static ArenaView from(Arena arena, GameSession session) {
            GameState state = session == null ? GameState.WAITING : session.getState();
            int currentPlayers = session == null ? 0 : session.getActivePlayerIds().size();
            int maxPlayers = arena.getSpawnPoints().size();
            boolean full = currentPlayers >= maxPlayers;
            boolean joinable = arena.isJoiningOpen()
                    && !full
                    && (state == GameState.WAITING || state == GameState.STARTING);

            return new ArenaView(state, currentPlayers, maxPlayers, full, joinable);
        }

        String stateDisplay(TranslationManager translations) {
            String stateKey = switch (state) {
                case WAITING -> "waiting";
                case STARTING -> "starting";
                case RUNNING -> "running";
                case ENDING -> "ending";
                case RESETTING -> "resetting";
            };
            return translations.text("arena-view.state." + stateKey);
        }

        String stateColor() {
            return switch (state) {
                case WAITING -> UiPalette.SUCCESS;
                case STARTING -> UiPalette.PRIMARY;
                case RUNNING -> UiPalette.DANGER;
                case ENDING, RESETTING -> UiPalette.MUTED;
            };
        }

        String joiningDisplay(TranslationManager translations) {
            if (joinable) {
                return translations.text("arena-view.joining-state.open");
            }

            if (full) {
                return translations.text("arena-view.joining-state.full");
            }

            return translations.text("arena-view.joining-state.closed");
        }

        String playerActionLore(TranslationManager translations) {
            if (joinable) {
                return translations.text("arena-view.action.join");
            }

            if (full) {
                return translations.text("arena-view.action.full");
            }

            if (state == GameState.RUNNING) {
                return translations.text("arena-view.action.running");
            }

            if (state == GameState.ENDING) {
                return translations.text("arena-view.action.ending");
            }

            if (state == GameState.RESETTING) {
                return translations.text("arena-view.action.resetting");
            }

            return translations.text("arena-view.action.closed");
        }

        Material material() {
            if (!joinable) {
                if (full || state == GameState.RUNNING) {
                    return Material.RED_DYE;
                }

                if (state == GameState.ENDING || state == GameState.RESETTING) {
                    return Material.GRAY_DYE;
                }

                return Material.BARRIER;
            }

            if (state == GameState.STARTING) {
                return Material.YELLOW_DYE;
            }

            return Material.LIME_DYE;
        }

        String itemColor() {
            if (joinable) {
                return state == GameState.STARTING
                        ? UiPalette.PRIMARY
                        : UiPalette.SUCCESS;
            }

            if (full || state == GameState.RUNNING) {
                return UiPalette.DANGER;
            }

            if (state == GameState.ENDING || state == GameState.RESETTING) {
                return UiPalette.MUTED;
            }

            return UiPalette.DANGER;
        }
    }

}
