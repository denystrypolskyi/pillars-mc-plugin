package org.example.pillars.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.example.pillars.managers.TranslationManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Captures YAML on the caller thread and serializes only plain strings on one file thread.
 */
public final class AsyncYamlWriter {
    private final JavaPlugin plugin;
    private final TranslationManager translations;
    private final ExecutorService writer;
    private final Object lock = new Object();
    private final Map<Path, PendingWrite> pendingWrites = new LinkedHashMap<>();
    private boolean writeScheduled;
    private boolean closed;

    public AsyncYamlWriter(JavaPlugin plugin, TranslationManager translations) {
        this.plugin = plugin;
        this.translations = translations;
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pillars-yaml-writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    public CompletableFuture<Boolean> savePluginConfig() {
        return requestWrite(
                plugin.getDataFolder().toPath().resolve("config.yml"),
                plugin.getConfig().saveToString()
        );
    }

    public CompletableFuture<Boolean> save(Path target, YamlConfiguration configuration) {
        return requestWrite(target, configuration.saveToString());
    }

    private CompletableFuture<Boolean> requestWrite(Path target, String yaml) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        boolean schedule = false;
        synchronized (lock) {
            if (closed) {
                result.complete(false);
                return result;
            }
            Path normalizedTarget = target.toAbsolutePath().normalize();
            PendingWrite pendingWrite = pendingWrites.get(normalizedTarget);
            if (pendingWrite == null) {
                pendingWrites.put(normalizedTarget, new PendingWrite(yaml, result));
            } else {
                pendingWrite.replaceWith(yaml, result);
            }
            if (!writeScheduled) {
                writeScheduled = true;
                schedule = true;
            }
        }
        if (schedule) writer.execute(this::drainWrites);
        return result;
    }

    private void drainWrites() {
        while (true) {
            Map<Path, PendingWrite> writes;
            synchronized (lock) {
                if (pendingWrites.isEmpty()) {
                    writeScheduled = false;
                    return;
                }
                writes = new LinkedHashMap<>(pendingWrites);
                pendingWrites.clear();
            }

            for (Map.Entry<Path, PendingWrite> entry : writes.entrySet()) {
                PendingWrite pendingWrite = entry.getValue();
                boolean saved = writeAtomically(entry.getKey(), pendingWrite.yaml());
                pendingWrite.complete(saved);
            }
        }
    }

    private boolean writeAtomically(Path target, String yaml) {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(temporary, yaml, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, translations.text(
                    "logs.yaml-save-failed",
                    "file", target.getFileName(),
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

    private static final class PendingWrite {
        private String yaml;
        private final List<CompletableFuture<Boolean>> results = new ArrayList<>();

        private PendingWrite(String yaml, CompletableFuture<Boolean> result) {
            this.yaml = yaml;
            this.results.add(result);
        }

        private String yaml() {
            return yaml;
        }

        private void replaceWith(String yaml, CompletableFuture<Boolean> result) {
            this.yaml = yaml;
            this.results.add(result);
        }

        private void complete(boolean saved) {
            for (CompletableFuture<Boolean> result : results) {
                result.complete(saved);
            }
        }
    }

    public void shutdown() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
        }
        writer.shutdown();
        try {
            if (!writer.awaitTermination(30, TimeUnit.SECONDS)) {
                plugin.getLogger().warning(translations.text("logs.yaml-shutdown-timeout"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().log(Level.WARNING, translations.text("logs.yaml-shutdown-interrupted"), e);
        }
    }
}
