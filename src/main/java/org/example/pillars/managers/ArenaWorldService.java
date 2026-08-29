package org.example.pillars.managers;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.scheduler.BukkitTask;
import org.example.pillars.PillarsPlugin;
import org.example.pillars.entities.Arena;
import org.example.pillars.enums.ArenaRebuildResult;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Owns arena world directories, Bukkit world loading, and transactional rebuilds.
 */
public final class ArenaWorldService {
    private final PillarsPlugin plugin;
    private final TranslationManager translations;
    private final ArenaFloorService floorService;
    private final File worldContainer;
    private final File templateWorld;
    private final ExecutorService fileExecutor;
    private final Set<PendingActivation> pendingActivations = ConcurrentHashMap.newKeySet();
    private final Deque<WorldActivation> worldActivations = new ArrayDeque<>();
    private BukkitTask worldActivationTask;
    private volatile boolean shuttingDown;

    public ArenaWorldService(
            PillarsPlugin plugin,
            TranslationManager translations,
            ArenaFloorService floorService
    ) {
        this.plugin = plugin;
        this.translations = translations;
        this.floorService = floorService;
        this.worldContainer = Bukkit.getWorldContainer();
        this.templateWorld = new File(worldContainer, "arena_template");
        this.fileExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "pillars-arena-rebuild");
            thread.setDaemon(true);
            return thread;
        });
    }

    public boolean isTemplateAvailable() {
        return templateWorld.exists() && templateWorld.isDirectory();
    }

    public String getTemplatePath() {
        return templateWorld.getAbsolutePath();
    }

    public void loadOrCreateAsync(String worldName, Consumer<World> callback) {
        if (shuttingDown) return;

        Path worldContainerPath = worldContainer.toPath().toAbsolutePath().normalize();
        Path arenaFolder;
        try {
            arenaFolder = resolveArenaFolder(worldContainerPath, worldName);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().log(Level.SEVERE, translations.text(
                    "logs.arena-reset-invalid-path",
                    "world", worldName
            ), e);
            callback.accept(null);
            return;
        }

        if (Files.exists(arenaFolder)) {
            queueWorldActivation(worldName, callback);
            return;
        }

        Path stagingFolder = worldContainerPath.resolve(".pillars-create-" + UUID.randomUUID());
        executeFileTask(() -> {
            try {
                copyFolder(templateWorld.toPath(), stagingFolder);
                Files.deleteIfExists(stagingFolder.resolve("uid.dat"));
                Files.deleteIfExists(stagingFolder.resolve("session.lock"));
                moveFolder(stagingFolder, arenaFolder);
                plugin.getLogger().info(translations.text("logs.arena-world-created", "world", worldName));
            } catch (IOException | RuntimeException e) {
                cleanupFolder(stagingFolder, worldName);
                plugin.getLogger().log(Level.SEVERE, translations.text(
                        "logs.arena-template-copy-failed",
                        "world", worldName,
                        "error", e.getMessage()
                ), e);
                scheduleWorldResult(callback, null);
                return;
            }

            if (!shuttingDown) {
                Bukkit.getScheduler().runTask(plugin, () -> queueWorldActivation(worldName, callback));
            }
        });
    }

    private void queueWorldActivation(String worldName, Consumer<World> callback) {
        if (shuttingDown) return;
        worldActivations.addLast(new WorldActivation(worldName, callback));
        if (worldActivationTask == null) {
            worldActivationTask = Bukkit.getScheduler().runTaskTimer(
                    plugin,
                    this::activateNextWorld,
                    1L,
                    1L
            );
        }
    }

    private void activateNextWorld() {
        WorldActivation activation = worldActivations.pollFirst();
        if (activation == null) {
            cancelWorldActivationTask();
            return;
        }

        World world = Bukkit.getWorld(activation.worldName());
        if (world == null) {
            WorldCreator creator = new WorldCreator(activation.worldName());
            creator.generateStructures(false);
            world = creator.createWorld();
        }

        if (world == null) {
            plugin.getLogger().severe(translations.text(
                    "logs.world-load-failed",
                    "world", activation.worldName()
            ));
        } else {
            configure(world);
        }
        activation.callback().accept(world);

        if (worldActivations.isEmpty()) {
            cancelWorldActivationTask();
        }
    }

    private void scheduleWorldResult(Consumer<World> callback, World world) {
        if (!shuttingDown) {
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(world));
        }
    }

    private void cancelWorldActivationTask() {
        if (worldActivationTask != null) {
            worldActivationTask.cancel();
            worldActivationTask = null;
        }
    }

    public void rebuild(Arena arena, Consumer<ArenaRebuildResult> callback) {
        if (shuttingDown) return;

        String worldName = arena.getWorldName();
        World world = Bukkit.getWorld(worldName);

        if (!isTemplateAvailable()) {
            plugin.getLogger().severe(translations.text(
                    "logs.arena-reset-template-missing",
                    "world", worldName,
                    "path", templateWorld.getAbsolutePath()
            ));
            runCallback(callback, ArenaRebuildResult.TEMPLATE_MISSING);
            return;
        }

        Path worldContainerPath = worldContainer.toPath().toAbsolutePath().normalize();
        Path templateFolder = templateWorld.toPath().toAbsolutePath().normalize();
        Path arenaFolder;
        try {
            arenaFolder = resolveArenaFolder(worldContainerPath, worldName);
            if (arenaFolder.equals(templateFolder)) {
                throw new IllegalArgumentException("Мир арены не может совпадать с шаблоном");
            }
        } catch (IllegalArgumentException e) {
            plugin.getLogger().log(Level.SEVERE, translations.text(
                    "logs.arena-reset-invalid-path",
                    "world", worldName
            ), e);
            runCallback(callback, ArenaRebuildResult.INVALID_ARENA_PATH);
            return;
        }

        if (world != null) {
            if (!world.getPlayers().isEmpty()) {
                plugin.getLogger().severe(translations.text(
                        "logs.arena-reset-world-occupied",
                        "world", worldName,
                        "count", world.getPlayers().size()
                ));
                runCallback(callback, ArenaRebuildResult.WORLD_OCCUPIED);
                return;
            }

            if (!Bukkit.unloadWorld(world, false)) {
                plugin.getLogger().severe(translations.text(
                        "logs.arena-reset-unload-failed",
                        "world", worldName
                ));
                runCallback(callback, ArenaRebuildResult.WORLD_UNLOAD_FAILED);
                return;
            }
        }

        String operationId = UUID.randomUUID().toString();
        Path stagingFolder = worldContainerPath.resolve(".pillars-reset-" + operationId);
        Path backupFolder = worldContainerPath.resolve(".pillars-backup-" + operationId);

        executeFileTask(() -> {
            try {
                stageArenaReplacement(templateFolder, arenaFolder, stagingFolder, backupFolder);
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, translations.text(
                        "logs.arena-reset-failed",
                        "world", worldName,
                        "error", e.getMessage()
                ), e);
                cleanupFolder(stagingFolder, worldName);
                restoreBackupAfterFailedSwap(arenaFolder, backupFolder, worldName);
                runCallback(callback, ArenaRebuildResult.FILE_OPERATION_FAILED);
                return;
            }

            if (shuttingDown) {
                rollbackActivatedArena(arenaFolder, backupFolder, worldName);
                return;
            }

            PendingActivation pending = new PendingActivation(arenaFolder, backupFolder, worldName);
            pendingActivations.add(pending);
            try {
                Bukkit.getScheduler().runTask(plugin, () -> loadRebuiltArena(arena, pending, callback));
            } catch (RuntimeException schedulingError) {
                pendingActivations.remove(pending);
                rollbackActivatedArena(arenaFolder, backupFolder, worldName);
                if (!shuttingDown) {
                    plugin.getLogger().log(Level.SEVERE, translations.text(
                            "logs.arena-reset-failed",
                            "world", worldName,
                            "error", schedulingError.getMessage()
                    ), schedulingError);
                }
            }
        });
    }

    private void loadRebuiltArena(
            Arena arena,
            PendingActivation pending,
            Consumer<ArenaRebuildResult> callback
    ) {
        if (shuttingDown) return;

        Path arenaFolder = pending.arenaFolder();
        Path backupFolder = pending.backupFolder();
        String worldName = arena.getWorldName();
        List<Location> previousSpawns = new ArrayList<>(arena.getSpawnPoints());
        World newWorld = null;
        try {
            WorldCreator creator = new WorldCreator(worldName);
            creator.generateStructures(false);
            newWorld = creator.createWorld();
            if (newWorld == null) {
                throw new IllegalStateException(translations.text("logs.world-load-failed", "world", worldName));
            }

            configure(newWorld);
            List<Location> newSpawns = new ArrayList<>();
            for (Location location : arena.getSpawnPoints()) {
                newSpawns.add(new Location(newWorld, location.getX(), location.getY(), location.getZ()));
            }
            arena.setSpawnPoints(newSpawns);
            World activatedWorld = newWorld;
            floorService.generate(
                    arena,
                    () -> {
                        pendingActivations.remove(pending);
                        plugin.getLogger().info(translations.text(
                                "logs.arena-reset-completed",
                                "world", worldName
                        ));
                        runCallback(callback, ArenaRebuildResult.SUCCESS);
                        executeFileTask(() -> cleanupFolder(backupFolder, worldName));
                    },
                    error -> {
                        arena.setSpawnPoints(previousSpawns);
                        plugin.getLogger().log(Level.SEVERE, translations.text(
                                "logs.arena-reset-failed",
                                "world", worldName,
                                "error", error.getMessage()
                        ), error);
                        rollbackFailedWorldLoad(activatedWorld, pending, callback);
                    }
            );
        } catch (RuntimeException e) {
            arena.setSpawnPoints(previousSpawns);
            plugin.getLogger().log(Level.SEVERE, translations.text(
                    "logs.arena-reset-failed",
                    "world", worldName,
                    "error", e.getMessage()
            ), e);
            rollbackFailedWorldLoad(newWorld, pending, callback);
        }
    }

    private void configure(World world) {
        world.setAutoSave(false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        world.setTime(6000);
    }

    private void rollbackFailedWorldLoad(
            World newWorld,
            PendingActivation pending,
            Consumer<ArenaRebuildResult> callback
    ) {
        String worldName = pending.worldName();
        if (newWorld != null && !Bukkit.unloadWorld(newWorld, false)) {
            plugin.getLogger().severe(translations.text("logs.arena-reset-unload-failed", "world", worldName));
            pendingActivations.remove(pending);
            runCallback(callback, ArenaRebuildResult.WORLD_LOAD_FAILED);
            return;
        }

        executeFileTask(() -> {
            rollbackActivatedArena(pending.arenaFolder(), pending.backupFolder(), worldName);
            pendingActivations.remove(pending);
            runCallback(callback, ArenaRebuildResult.WORLD_LOAD_FAILED);
        });
    }

    private void runCallback(Consumer<ArenaRebuildResult> callback, ArenaRebuildResult result) {
        if (!shuttingDown && callback != null) {
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result));
        }
    }

    public void shutdown() {
        if (shuttingDown) return;
        shuttingDown = true;
        cancelWorldActivationTask();
        worldActivations.clear();
        fileExecutor.shutdown();
        try {
            if (!fileExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                plugin.getLogger().warning(translations.text("logs.arena-reset-shutdown-timeout"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().log(Level.WARNING, translations.text("logs.arena-reset-shutdown-interrupted"), e);
        }

        for (PendingActivation pending : new ArrayList<>(pendingActivations)) {
            rollbackActivatedArena(pending.arenaFolder(), pending.backupFolder(), pending.worldName());
            pendingActivations.remove(pending);
        }
    }

    private void executeFileTask(Runnable task) {
        if (shuttingDown) return;
        try {
            fileExecutor.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Shutdown owns rollback for every staged activation still registered.
        }
    }

    private static Path resolveArenaFolder(Path worldContainer, String worldName) {
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("Имя мира арены не задано");
        }

        Path normalizedContainer = worldContainer.toAbsolutePath().normalize();
        Path resolved = normalizedContainer.resolve(worldName).normalize();
        if (!normalizedContainer.equals(resolved.getParent())) {
            throw new IllegalArgumentException("Мир арены должен находиться непосредственно в каталоге миров");
        }
        return resolved;
    }

    private static void stageArenaReplacement(
            Path templateFolder,
            Path arenaFolder,
            Path stagingFolder,
            Path backupFolder
    ) throws IOException {
        deleteFolderIfExists(stagingFolder);
        deleteFolderIfExists(backupFolder);
        copyFolder(templateFolder, stagingFolder);
        Files.deleteIfExists(stagingFolder.resolve("uid.dat"));
        Files.deleteIfExists(stagingFolder.resolve("session.lock"));

        boolean backupCreated = false;
        try {
            if (Files.exists(arenaFolder)) {
                moveFolder(arenaFolder, backupFolder);
                backupCreated = true;
            }
            moveFolder(stagingFolder, arenaFolder);
        } catch (IOException | RuntimeException e) {
            if (backupCreated && !Files.exists(arenaFolder) && Files.exists(backupFolder)) {
                moveFolder(backupFolder, arenaFolder);
            }
            throw e;
        }
    }

    private static void restoreArenaBackup(Path arenaFolder, Path backupFolder) throws IOException {
        if (!Files.exists(backupFolder)) return;
        deleteFolderIfExists(arenaFolder);
        moveFolder(backupFolder, arenaFolder);
    }

    private void restoreBackupAfterFailedSwap(Path arenaFolder, Path backupFolder, String worldName) {
        try {
            restoreArenaBackup(arenaFolder, backupFolder);
        } catch (IOException | RuntimeException rollbackError) {
            plugin.getLogger().log(Level.SEVERE, translations.text(
                    "logs.arena-reset-rollback-failed",
                    "world", worldName,
                    "error", rollbackError.getMessage()
            ), rollbackError);
        }
    }

    private void rollbackActivatedArena(Path arenaFolder, Path backupFolder, String worldName) {
        try {
            deleteFolderIfExists(arenaFolder);
            if (Files.exists(backupFolder)) {
                moveFolder(backupFolder, arenaFolder);
            }
        } catch (IOException | RuntimeException rollbackError) {
            plugin.getLogger().log(Level.SEVERE, translations.text(
                    "logs.arena-reset-rollback-failed",
                    "world", worldName,
                    "error", rollbackError.getMessage()
            ), rollbackError);
        }
    }

    private void cleanupFolder(Path folder, String worldName) {
        try {
            deleteFolderIfExists(folder);
        } catch (IOException | RuntimeException cleanupError) {
            plugin.getLogger().log(Level.WARNING, translations.text(
                    "logs.arena-reset-cleanup-failed",
                    "world", worldName,
                    "path", folder,
                    "error", cleanupError.getMessage()
            ), cleanupError);
        }
    }

    private static void moveFolder(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target);
        }
    }

    private static void copyFolder(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            paths.forEach(path -> {
                try {
                    Path destination = target.resolve(source.relativize(path));
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static void deleteFolderIfExists(Path path) throws IOException {
        if (Files.notExists(path)) return;
        try (var paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(current -> {
                try {
                    Files.delete(current);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private record PendingActivation(Path arenaFolder, Path backupFolder, String worldName) {}

    private record WorldActivation(String worldName, Consumer<World> callback) {}
}
