package org.example.pillars.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Live Lucky Block settings. Every getter reads the in-memory Bukkit configuration,
 * so administrative menu updates affect subsequent outcomes immediately.
 */
public final class LuckyBlockSettings {
    private final JavaPlugin plugin;
    private final AsyncYamlWriter yamlWriter;

    public LuckyBlockSettings(JavaPlugin plugin, AsyncYamlWriter yamlWriter) {
        this.plugin = plugin;
        this.yamlWriter = yamlWriter;
    }

    public int itemChancePercent() {
        return percent("settings.luckyBlocks.itemChancePercent", 85);
    }

    public int goodChancePercent() {
        return percent("settings.luckyBlocks.categories.goodPercent", 7);
    }

    public int neutralChancePercent() {
        return percent("settings.luckyBlocks.categories.neutralPercent", 3);
    }

    public int badChancePercent() {
        return percent("settings.luckyBlocks.categories.badPercent", 5);
    }

    public int antiRepeatHistorySize() {
        return Math.max(0, Math.min(10, config().getInt("settings.luckyBlocks.antiRepeatHistorySize", 4)));
    }

    public long fluidDurationTicks() {
        return Math.max(20L, config().getLong("settings.luckyBlocks.fluidDurationTicks", 100L));
    }

    public long mobDurationTicks() {
        return Math.max(20L, config().getLong("settings.luckyBlocks.mobDurationTicks", 400L));
    }

    public int tntFuseTicks() {
        return Math.max(1, config().getInt("settings.luckyBlocks.tntFuseTicks", 60));
    }

    public float explosionPower() {
        return (float) Math.max(0.0, config().getDouble("settings.luckyBlocks.explosionPower", 2.0));
    }

    public boolean tntBlockDamage() {
        return config().getBoolean("settings.luckyBlocks.tntBlockDamage", false);
    }

    public void setTntBlockDamage(boolean enabled) {
        config().set("settings.luckyBlocks.tntBlockDamage", enabled);
        yamlWriter.savePluginConfig();
    }

    public void setChances(int item, int good, int neutral, int bad) {
        config().set("settings.luckyBlocks.itemChancePercent", item);
        config().set("settings.luckyBlocks.categories.goodPercent", good);
        config().set("settings.luckyBlocks.categories.neutralPercent", neutral);
        config().set("settings.luckyBlocks.categories.badPercent", bad);
        yamlWriter.savePluginConfig();
    }

    private int percent(String path, int fallback) {
        return Math.max(0, Math.min(100, config().getInt(path, fallback)));
    }

    private FileConfiguration config() {
        return plugin.getConfig();
    }
}
