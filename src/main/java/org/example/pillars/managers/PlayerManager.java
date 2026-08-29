package org.example.pillars.managers;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PlayerManager {

    private final TeleportManager teleportManager;
    private final HudManager hudManager;
    private final TranslationManager translations;
    private final NamespacedKey actionKey;
    private final String lobbyWorldName;
    private final StatsManager statsManager;
    private final Map<UUID, PlayerStateSnapshot> preMatchStates = new HashMap<>();

    public PlayerManager(
            JavaPlugin plugin,
            TeleportManager teleportManager,
            HudManager hudManager,
            StatsManager statsManager,
            String lobbyWorldName
    ) {
        this.teleportManager = teleportManager;
        this.hudManager = hudManager;
        this.statsManager = statsManager;
        this.translations = hudManager.getTranslations();
        this.actionKey = new NamespacedKey(plugin, "lobby_action");
        this.lobbyWorldName = lobbyWorldName;
    }

    public void resetPlayerState(Player player) {
        preMatchStates.computeIfAbsent(player.getUniqueId(), ignored -> PlayerStateSnapshot.capture(player));
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        double normalizedHealth = Math.min(20.0, player.getMaxHealth());
        if (normalizedHealth > 0.0) {
            player.setHealth(normalizedHealth);
        }
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.setExhaustion(0f);
        player.setTotalExperience(0);
        player.setLevel(0);
        player.setExp(0f);
        player.setAbsorptionAmount(0.0);
        player.setFireTicks(0);
        player.setFallDistance(0.0f);
        player.setVelocity(new Vector());
        player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setGlowing(false);
    }

    public void resetAndReturnToLobby(Player player) {
        resetAndReturnToLobby(player, lobbyWorldName);
    }

    public void resetAndReturnToLobby(Player player, String lobbyWorldName) {
        restorePreMatchState(player, false);
        if (!teleportManager.teleportToLobby(player, lobbyWorldName)) {
            hudManager.sendLobbyWorldMissing(player, lobbyWorldName);
        } else {
            giveLobbyItems(player);
        }
        hudManager.resetScoreboard(player);
        var stats = statsManager.getStats(player.getUniqueId());
        hudManager.updateLobbyScoreboard(player, stats.getKills(), stats.getWins(), stats.getGamesPlayed());
    }

    public void enterLobby(Player player) {
        if (!teleportManager.teleportToLobby(player, lobbyWorldName)) {
            hudManager.sendLobbyWorldMissing(player, lobbyWorldName);
        } else {
            giveLobbyItems(player);
        }
        hudManager.resetScoreboard(player);
        var stats = statsManager.getStats(player.getUniqueId());
        hudManager.updateLobbyScoreboard(player, stats.getKills(), stats.getWins(), stats.getGamesPlayed());
    }

    public void releasePlayer(Player player) {
        restorePreMatchState(player, true);
        hudManager.resetScoreboard(player);
    }

    public void shutdown() {
        for (UUID playerId : List.copyOf(preMatchStates.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                releasePlayer(player);
            }
        }
        preMatchStates.clear();
    }

    public void prepareSpectatorInventory(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        giveLeaveArenaItem(player);
    }

    public String getAction(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;

        return item.getItemMeta().getPersistentDataContainer()
                .get(actionKey, PersistentDataType.STRING);
    }

    public void giveForceStartItem(Player player) {
        if (!player.hasPermission("pillars.forcestart")) return;

        player.getInventory().setItem(0, actionItem(
                Material.LIME_DYE,
                translations.text("game-items.force-start"),
                "force_start",
                translations.text("game-items.force-start-lore")
        ));
    }

    public void removeForceStartItem(Player player) {
        removeActionItem(player, "force_start");
    }

    public void giveLeaveArenaItem(Player player) {
        player.getInventory().setItem(8, actionItem(
                Material.RED_BED,
                translations.text("game-items.leave-arena"),
                "leave_arena",
                translations.text("game-items.leave-arena-lore")
        ));
    }

    public void removeLeaveArenaItem(Player player) {
        removeActionItem(player, "leave_arena");
    }

    public void giveAdminMenuItem(Player player) {
        removeAdminMenuItem(player);
        if (!player.hasPermission("pillars.admin")) return;
        player.getInventory().setItem(4, adminMenuItem());
    }

    public void removeAdminMenuItem(Player player) {
        removeActionItem(player, "admin_menu");
    }

    public void giveCurrentArenaSettingsItem(Player player, String arenaName) {
        removeCurrentArenaSettingsItem(player);
        if (!player.hasPermission("pillars.admin")) return;

        player.getInventory().setItem(5, actionItem(
                Material.COMPARATOR,
                translations.text("arena-admin-item.name"),
                "arena_settings",
                translations.text("arena-admin-item.arena", "arena", arenaName),
                translations.text("arena-admin-item.lore")
        ));
    }

    public void removeCurrentArenaSettingsItem(Player player) {
        removeActionItem(player, "arena_settings");
    }

    private void removeActionItem(Player player, String action) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            if (action.equals(getAction(player.getInventory().getItem(slot)))) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    private void giveLobbyItems(Player player) {
        placeActionItem(player, 0, actionItem(
                Material.RECOVERY_COMPASS,
                translations.text("lobby-items.choose-arena"),
                "menu"
        ));
        placeActionItem(player, 8, actionItem(
                Material.NETHER_STAR,
                translations.text("lobby-items.quick-play"),
                "quickjoin"
        ));
        if (player.hasPermission("pillars.admin")) {
            placeActionItem(player, 4, adminMenuItem());
        }
    }

    private ItemStack adminMenuItem() {
        return actionItem(
                Material.COMMAND_BLOCK,
                translations.text("admin-item.name"),
                "admin_menu",
                translations.text("admin-item.lore")
        );
    }

    private void placeActionItem(Player player, int preferredSlot, ItemStack item) {
        String action = getAction(item);
        removeActionItem(player, action);

        if (player.getInventory().getItem(preferredSlot) == null) {
            player.getInventory().setItem(preferredSlot, item);
            return;
        }

        int emptySlot = player.getInventory().firstEmpty();
        if (emptySlot >= 0) {
            player.getInventory().setItem(emptySlot, item);
        }
    }

    private void restorePreMatchState(Player player, boolean restoreLocation) {
        PlayerStateSnapshot snapshot = preMatchStates.remove(player.getUniqueId());
        if (snapshot == null) return;
        snapshot.restore(player, restoreLocation);
    }

    private ItemStack actionItem(Material material, String name, String action, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        if (lore.length > 0) {
            meta.setLore(List.of(lore));
        }
        meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        item.setItemMeta(meta);
        return item;
    }

    private record PlayerStateSnapshot(
            ItemStack[] storage,
            ItemStack[] armor,
            ItemStack[] extra,
            int heldSlot,
            double health,
            double absorption,
            int food,
            float saturation,
            float exhaustion,
            int totalExperience,
            int level,
            float experience,
            int fireTicks,
            float fallDistance,
            Collection<PotionEffect> potionEffects,
            GameMode gameMode,
            boolean allowFlight,
            boolean flying,
            boolean glowing,
            Vector velocity,
            Location location
    ) {
        private static PlayerStateSnapshot capture(Player player) {
            return new PlayerStateSnapshot(
                    cloneItems(player.getInventory().getStorageContents()),
                    cloneItems(player.getInventory().getArmorContents()),
                    cloneItems(player.getInventory().getExtraContents()),
                    player.getInventory().getHeldItemSlot(),
                    player.getHealth(),
                    player.getAbsorptionAmount(),
                    player.getFoodLevel(),
                    player.getSaturation(),
                    player.getExhaustion(),
                    player.getTotalExperience(),
                    player.getLevel(),
                    player.getExp(),
                    player.getFireTicks(),
                    player.getFallDistance(),
                    List.copyOf(player.getActivePotionEffects()),
                    player.getGameMode(),
                    player.getAllowFlight(),
                    player.isFlying(),
                    player.isGlowing(),
                    player.getVelocity().clone(),
                    player.getLocation().clone()
            );
        }

        private void restore(Player player, boolean restoreLocation) {
            player.getInventory().clear();
            player.getInventory().setStorageContents(cloneItems(storage));
            player.getInventory().setArmorContents(cloneItems(armor));
            player.getInventory().setExtraContents(cloneItems(extra));
            player.getInventory().setHeldItemSlot(heldSlot);

            player.getActivePotionEffects().forEach(effect -> player.removePotionEffect(effect.getType()));
            player.addPotionEffects(potionEffects);

            player.setGameMode(gameMode);
            player.setAllowFlight(allowFlight);
            player.setFlying(allowFlight && flying);
            player.setGlowing(glowing);
            player.setFoodLevel(food);
            player.setSaturation(saturation);
            player.setExhaustion(exhaustion);
            player.setTotalExperience(totalExperience);
            player.setLevel(level);
            player.setExp(experience);
            player.setAbsorptionAmount(absorption);
            player.setFireTicks(fireTicks);
            player.setFallDistance(fallDistance);
            player.setVelocity(velocity.clone());

            double restoredHealth = Math.min(health, player.getMaxHealth());
            if (restoredHealth > 0.0) {
                player.setHealth(restoredHealth);
            }
            if (restoreLocation && location.getWorld() != null) {
                player.teleport(location);
            }
        }

        private static ItemStack[] cloneItems(ItemStack[] items) {
            ItemStack[] cloned = new ItemStack[items.length];
            for (int i = 0; i < items.length; i++) {
                cloned[i] = items[i] == null ? null : items[i].clone();
            }
            return cloned;
        }
    }
}
