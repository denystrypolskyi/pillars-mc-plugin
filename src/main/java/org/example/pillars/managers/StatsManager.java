package org.example.pillars.managers;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.plugin.java.JavaPlugin;
import org.example.pillars.entities.PlayerRecord;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class StatsManager {

    private final JavaPlugin plugin;
    private final File statsFile;
    private final Gson gson;
    private Map<UUID, PlayerRecord> statsMap;

    public StatsManager(JavaPlugin plugin) {
        this.plugin = plugin;
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

            FileReader reader = new FileReader(statsFile);
            Type type = new TypeToken<Map<UUID, PlayerRecord>>() {}.getType();
            statsMap = gson.fromJson(reader, type);
            reader.close();

            if (statsMap == null) statsMap = new HashMap<>();
        } catch (Exception e) {
            e.printStackTrace();
            statsMap = new HashMap<>();
        }
    }

    public void saveStats() {
        try {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();

            FileWriter writer = new FileWriter(statsFile);
            gson.toJson(statsMap, writer);
            writer.flush();
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public PlayerRecord getStats(UUID playerUUID) {
        return statsMap.computeIfAbsent(playerUUID, k -> new PlayerRecord());
    }

    public void setStats(UUID playerUUID, PlayerRecord stats) {
        statsMap.put(playerUUID, stats);
        saveStats();
    }

    public void incrementKills(UUID playerUUID) {
        PlayerRecord stats = getStats(playerUUID);
        stats.setKills(stats.getKills() + 1);
        saveStats();
    }

    public void incrementWins(UUID playerUUID) {
        PlayerRecord stats = getStats(playerUUID);
        stats.setWins(stats.getWins() + 1);
        saveStats();
    }
}
