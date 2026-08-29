package org.example.pillars.managers;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.plugin.java.JavaPlugin;
import org.example.pillars.entities.PlayerStats;

import java.io.File;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class StatsManager {

    private final JavaPlugin plugin;
    private final TranslationManager translations;
    private final File statsFile;
    private final Gson gson;
    private final Object lock = new Object();
    private final ExecutorService writer;
    private Map<UUID, PlayerStats> statsMap = new HashMap<>();
    private boolean dirty;
    private boolean writeScheduled;
    private boolean closed;

    public StatsManager(JavaPlugin plugin, TranslationManager translations) {
        this.plugin = plugin;
        this.translations = translations;
        this.gson = new Gson();
        this.statsFile = new File(plugin.getDataFolder(), "stats.json");
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pillars-stats-writer");
            thread.setDaemon(true);
            return thread;
        });
        loadStats();
    }

    private void loadStats() {
        try {
            if (!statsFile.exists()) {
                requestSave();
                return;
            }

            Type type = new TypeToken<Map<UUID, PlayerStats>>() {}.getType();
            try (var reader = Files.newBufferedReader(statsFile.toPath())) {
                statsMap = gson.fromJson(reader, type);
            }

            if (statsMap == null) statsMap = new HashMap<>();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, translations.text(
                    "logs.stats-load-failed",
                    "error", e.getMessage()
            ), e);
            statsMap = new HashMap<>();
        }
    }

    private void requestSave() {
        boolean scheduleWriter = false;
        synchronized (lock) {
            if (closed) return;
            dirty = true;
            if (!writeScheduled) {
                writeScheduled = true;
                scheduleWriter = true;
            }
        }
        if (scheduleWriter) writer.execute(this::drainWrites);
    }

    private void drainWrites() {
        while (true) {
            Map<UUID, PlayerStats> snapshot;
            synchronized (lock) {
                if (!dirty) {
                    writeScheduled = false;
                    lock.notifyAll();
                    return;
                }
                dirty = false;
                snapshot = copyStatsMap();
            }

            if (!writeSnapshot(snapshot)) {
                synchronized (lock) {
                    dirty = true;
                    writeScheduled = false;
                    lock.notifyAll();
                }
                return;
            }
        }
    }

    private boolean writeSnapshot(Map<UUID, PlayerStats> snapshot) {
        Path target = statsFile.toPath();
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.toAbsolutePath().getParent());

            try (var fileWriter = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                gson.toJson(snapshot, fileWriter);
            }

            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, translations.text(
                    "logs.stats-save-failed",
                    "error", e.getMessage()
            ), e);
            try {
                Files.deleteIfExists(temporary);
            } catch (Exception cleanupError) {
                e.addSuppressed(cleanupError);
            }
            return false;
        }
    }

    public PlayerStats getStats(UUID playerUUID) {
        synchronized (lock) {
            return copyStats(statsMap.computeIfAbsent(playerUUID, ignored -> new PlayerStats()));
        }
    }

    public void setStats(UUID playerUUID, PlayerStats stats) {
        synchronized (lock) {
            statsMap.put(playerUUID, copyStats(stats));
        }
        requestSave();
    }

    public void incrementKills(UUID playerUUID) {
        synchronized (lock) {
            PlayerStats stats = statsMap.computeIfAbsent(playerUUID, ignored -> new PlayerStats());
            stats.setKills(stats.getKills() + 1);
        }
        requestSave();
    }

    public void incrementWins(UUID playerUUID) {
        synchronized (lock) {
            PlayerStats stats = statsMap.computeIfAbsent(playerUUID, ignored -> new PlayerStats());
            stats.setWins(stats.getWins() + 1);
        }
        requestSave();
    }

    public void incrementGamesPlayed(UUID playerUUID) {
        synchronized (lock) {
            PlayerStats stats = statsMap.computeIfAbsent(playerUUID, ignored -> new PlayerStats());
            stats.setGamesPlayed(stats.getGamesPlayed() + 1);
        }
        requestSave();
    }

    public void shutdown() {
        boolean scheduleWriter = false;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            if (dirty && !writeScheduled) {
                writeScheduled = true;
                scheduleWriter = true;
            }
        }
        if (scheduleWriter) writer.execute(this::drainWrites);

        writer.shutdown();
        try {
            if (!writer.awaitTermination(30, TimeUnit.SECONDS)) {
                plugin.getLogger().warning(translations.text("logs.stats-shutdown-timeout"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().log(Level.WARNING, translations.text("logs.stats-shutdown-interrupted"), e);
        }
    }

    private Map<UUID, PlayerStats> copyStatsMap() {
        Map<UUID, PlayerStats> snapshot = new HashMap<>();
        for (Map.Entry<UUID, PlayerStats> entry : statsMap.entrySet()) {
            snapshot.put(entry.getKey(), copyStats(entry.getValue()));
        }
        return snapshot;
    }

    private static PlayerStats copyStats(PlayerStats source) {
        PlayerStats copy = new PlayerStats();
        copy.setKills(source.getKills());
        copy.setWins(source.getWins());
        copy.setGamesPlayed(source.getGamesPlayed());
        return copy;
    }
}
