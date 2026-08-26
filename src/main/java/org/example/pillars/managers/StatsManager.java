package org.example.pillars.managers;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.plugin.java.JavaPlugin;
import org.example.pillars.entities.PlayerStats;

import java.io.File;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class StatsManager {

    private final JavaPlugin plugin;
    private final TranslationManager translations;
    private final File statsFile;
    private final Gson gson;
    private Map<UUID, PlayerStats> statsMap;

    public StatsManager(JavaPlugin plugin, TranslationManager translations) {
        this.plugin = plugin;
        this.translations = translations;
        this.gson = new Gson();
        this.statsFile = new File(plugin.getDataFolder(), "stats.json");
        loadStats();
    }

    private void loadStats() {
        try {
            if (!statsFile.exists()) {
                statsMap = new HashMap<>();
                saveStats();
                return;
            }

            Type type = new TypeToken<Map<UUID, PlayerStats>>() {}.getType();
            try (var reader = Files.newBufferedReader(statsFile.toPath())) {
                statsMap = gson.fromJson(reader, type);
            }

            if (statsMap == null) statsMap = new HashMap<>();
        } catch (Exception e) {
            plugin.getLogger().severe(translations.text(
                    "logs.stats-load-failed",
                    "error", e.getMessage()
            ));
            statsMap = new HashMap<>();
        }
    }

    public void saveStats() {
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();

            try (var writer = Files.newBufferedWriter(statsFile.toPath())) {
                gson.toJson(statsMap, writer);
            }
        } catch (Exception e) {
            plugin.getLogger().severe(translations.text(
                    "logs.stats-save-failed",
                    "error", e.getMessage()
            ));
        }
    }

    public PlayerStats getStats(UUID playerUUID) {
        return statsMap.computeIfAbsent(playerUUID, k -> new PlayerStats());
    }

    public void setStats(UUID playerUUID, PlayerStats stats) {
        statsMap.put(playerUUID, stats);
        saveStats();
    }

    public void incrementKills(UUID playerUUID) {
        PlayerStats stats = getStats(playerUUID);
        stats.setKills(stats.getKills() + 1);
        saveStats();
    }

    public void incrementWins(UUID playerUUID) {
        PlayerStats stats = getStats(playerUUID);
        stats.setWins(stats.getWins() + 1);
        saveStats();
    }

    public void incrementGamesPlayed(UUID playerUUID) {
        PlayerStats stats = getStats(playerUUID);
        stats.setGamesPlayed(stats.getGamesPlayed() + 1);
        saveStats();
    }
}
