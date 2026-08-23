package org.example.pillars.managers;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Collections;
import java.util.Map;
import java.util.Deque;
import java.util.Random;
import java.util.UUID;

public class ItemManager {
    private final Random random = new Random();
    private final JavaPlugin plugin;
    private final TranslationManager translations;
    private final File itemPoolsFile;
    private int legendaryPercent;
    private int rarePercent;
    private int antiRepeatHistorySize;
    private Map<Material, Integer> commonItems;
    private Map<Material, Integer> rareItems;
    private Map<Material, Integer> legendaryItems;
    private final Map<UUID, Deque<Material>> recentPlayerItems = new HashMap<>();

    public ItemManager(JavaPlugin plugin, TranslationManager translations) {
        this.plugin = plugin;
        this.translations = translations;
        this.itemPoolsFile = new File(plugin.getDataFolder(), "item-pools.yml");
        saveDefaultItemPools();
        migrateLegacyConfigItemPools();
        reloadConfigValues();
    }

    public void reloadConfigValues() {
        this.legendaryPercent = Math.max(0, Math.min(100, plugin.getConfig().getInt("settings.itemRarity.legendaryPercent", 5)));
        this.rarePercent = Math.max(0, Math.min(100 - legendaryPercent, plugin.getConfig().getInt("settings.itemRarity.rarePercent", 15)));
        this.antiRepeatHistorySize = Math.max(0, Math.min(12, plugin.getConfig().getInt("settings.itemRarity.antiRepeatHistorySize", 4)));
        YamlConfiguration itemPools = YamlConfiguration.loadConfiguration(itemPoolsFile);
        this.commonItems = loadItemPool(itemPools, "common");
        this.rareItems = loadItemPool(itemPools, "rare");
        this.legendaryItems = loadItemPool(itemPools, "legendary");
    }

    public ItemStack getRandomItem() {
        int roll = random.nextInt(100) + 1; // 1-100

        if (roll <= legendaryPercent) {
            return getRandomFromMap(legendaryItems);
        } else if (roll <= legendaryPercent + rarePercent) {
            return getRandomFromMap(rareItems);
        } else {
            return getRandomFromMap(commonItems);
        }
    }

    private ItemStack getRandomFromMap(Map<Material, Integer> map) {
        int totalWeight = map.values().stream().mapToInt(i -> i).sum();
        if (totalWeight <= 0) {
            return new ItemStack(Material.STONE);
        }

        int r = random.nextInt(totalWeight);
        int cumulative = 0;

        for (Map.Entry<Material, Integer> entry : map.entrySet()) {
            cumulative += entry.getValue();
            if (r < cumulative) {
                return new ItemStack(entry.getKey());
            }
        }
        return new ItemStack(Material.STONE);
    }

    public void giveRandomItem(Player player) {
        ItemStack item = getRandomItem();
        for (int attempt = 0; attempt < 8 && wasRecentlyGiven(player, item.getType()); attempt++) {
            item = getRandomItem();
        }

        rememberGivenItem(player, item.getType());
        player.getInventory().addItem(item);
    }

    public void clearRecentItems(Iterable<UUID> playerIds) {
        for (UUID playerId : playerIds) {
            recentPlayerItems.remove(playerId);
        }
    }

    public int getLegendaryPercent() {
        return legendaryPercent;
    }

    public int getRarePercent() {
        return rarePercent;
    }

    public int getCommonPercent() {
        return 100 - legendaryPercent - rarePercent;
    }

    public void setRarityPercentages(int legendaryPercent, int rarePercent) {
        this.legendaryPercent = Math.max(0, Math.min(100, legendaryPercent));
        this.rarePercent = Math.max(0, Math.min(100 - this.legendaryPercent, rarePercent));

        plugin.getConfig().set("settings.itemRarity.legendaryPercent", this.legendaryPercent);
        plugin.getConfig().set("settings.itemRarity.rarePercent", this.rarePercent);
        plugin.saveConfig();
    }

    public boolean setCustomItemWeight(String rarity, Material material, int weight) {
        String normalizedRarity = normalizeRarity(rarity);
        if (normalizedRarity == null || material == null || weight <= 0) {
            return false;
        }

        YamlConfiguration itemPools = YamlConfiguration.loadConfiguration(itemPoolsFile);
        itemPools.set(normalizedRarity + "." + material.name(), weight);
        saveItemPools(itemPools);
        reloadConfigValues();
        return true;
    }

    public boolean addItemWithDefaultWeight(String rarity, Material material) {
        int weight = getDefaultWeight(rarity);
        if (weight <= 0) {
            return false;
        }

        return setCustomItemWeight(rarity, material, weight);
    }

    public boolean removeItem(String rarity, Material material) {
        String normalizedRarity = normalizeRarity(rarity);
        if (normalizedRarity == null || material == null) {
            return false;
        }

        YamlConfiguration itemPools = YamlConfiguration.loadConfiguration(itemPoolsFile);
        itemPools.set(normalizedRarity + "." + material.name(), 0);
        saveItemPools(itemPools);
        reloadConfigValues();
        return true;
    }

    private Map<Material, Integer> loadItemPool(YamlConfiguration itemPools, String rarity) {
        Map<Material, Integer> items = new HashMap<>();

        ConfigurationSection section = itemPools.getConfigurationSection(rarity);
        if (section == null) {
            return items;
        }

        for (String materialName : section.getKeys(false)) {
            Material material = Material.matchMaterial(materialName);
            int weight = section.getInt(materialName, 0);

            if (material == null) {
                continue;
            }

            if (weight > 0) {
                items.put(material, weight);
            } else {
                items.remove(material);
            }
        }

        return items;
    }

    private boolean wasRecentlyGiven(Player player, Material material) {
        if (antiRepeatHistorySize <= 0) {
            return false;
        }

        Deque<Material> recentItems = recentPlayerItems.get(player.getUniqueId());
        return recentItems != null && recentItems.contains(material);
    }

    private void rememberGivenItem(Player player, Material material) {
        if (antiRepeatHistorySize <= 0) {
            return;
        }

        Deque<Material> recentItems = recentPlayerItems.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>());
        recentItems.addLast(material);
        while (recentItems.size() > antiRepeatHistorySize) {
            recentItems.removeFirst();
        }
    }

    public Map<Material, Integer> getItemPool(String rarity) {
        String normalizedRarity = normalizeRarity(rarity);
        if (normalizedRarity == null) {
            return Collections.emptyMap();
        }

        Map<Material, Integer> pool = switch (normalizedRarity) {
            case "common" -> commonItems;
            case "rare" -> rareItems;
            case "legendary" -> legendaryItems;
            default -> Collections.emptyMap();
        };

        return Collections.unmodifiableMap(pool);
    }

    public int getDefaultWeight(String rarity) {
        String normalizedRarity = normalizeRarity(rarity);
        if (normalizedRarity == null) return 0;

        return switch (normalizedRarity) {
            case "common" -> 10;
            case "rare" -> 5;
            case "legendary" -> 1;
            default -> 0;
        };
    }

    private void saveDefaultItemPools() {
        if (!itemPoolsFile.exists()) {
            plugin.saveResource("item-pools.yml", false);
        }
    }

    private void migrateLegacyConfigItemPools() {
        ConfigurationSection legacyPools = plugin.getConfig().getConfigurationSection("settings.itemPools");
        if (legacyPools == null) {
            return;
        }

        YamlConfiguration itemPools = YamlConfiguration.loadConfiguration(itemPoolsFile);
        for (String rarity : legacyPools.getKeys(false)) {
            ConfigurationSection legacyPool = legacyPools.getConfigurationSection(rarity);
            if (legacyPool == null) {
                continue;
            }

            for (String materialName : legacyPool.getKeys(false)) {
                itemPools.set(rarity + "." + materialName, legacyPool.getInt(materialName, 0));
            }
        }

        saveItemPools(itemPools);
        plugin.getConfig().set("settings.itemPools", null);
        plugin.saveConfig();
    }

    private void saveItemPools(YamlConfiguration itemPools) {
        try {
            itemPools.save(itemPoolsFile);
        } catch (Exception e) {
            plugin.getLogger().severe(translations.text(
                    "logs.item-pools-save-failed",
                    "error", e.getMessage()
            ));
        }
    }

    public String normalizeRarity(String rarity) {
        if (rarity == null) {
            return null;
        }

        return switch (rarity.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "common", "обычный", "обычные" -> "common";
            case "rare", "редкий", "редкие" -> "rare";
            case "legendary", "легендарный", "легендарные" -> "legendary";
            default -> null;
        };
    }
}
