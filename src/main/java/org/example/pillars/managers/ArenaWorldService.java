package org.example.pillars.managers;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
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
    }

    public boolean isTemplateAvailable() {
        return templateWorld.exists() && templateWorld.isDirectory();
    }

    public String getTemplatePath() {
        return templateWorld.getAbsolutePath();
    }

    public World loadOrCreate(String worldName) {
        File arenaFolder = new File(worldContainer, worldName);
        if (!arenaFolder.exists()) {
            try {
                copyFolder(templateWorld.toPath(), arenaFolder.toPath());
                new File(arenaFolder, "uid.dat").delete();
                new File(arenaFolder, "session.lock").delete();
                plugin.getLogger().info(translations.text("logs.arena-world-created", "world", worldName));
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, translations.text(
                        "logs.arena-template-copy-failed",
                        "world", worldName,
                        "error", e.getMessage()
                ), e);
                return null;
            }
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            WorldCreator creator = new WorldCreator(worldName);
            creator.generateStructures(false);
            world = creator.createWorld();
        }

        if (world == null) {
            plugin.getLogger().severe(translations.text("logs.world-load-failed", "world", worldName));
            return null;
        }

        configure(world);
        return world;
    }

    public void rebuild(Arena arena, Consumer<ArenaRebuildResult> callback) {
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

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
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

            Bukkit.getScheduler().runTask(plugin, () -> loadRebuiltArena(
                    arena,
                    arenaFolder,
                    backupFolder,
                    callback
            ));
        });
    }

    private void loadRebuiltArena(
            Arena arena,
            Path arenaFolder,
            Path backupFolder,
            Consumer<ArenaRebuildResult> callback
    ) {
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
            floorService.generate(arena);

            plugin.getLogger().info(translations.text("logs.arena-reset-completed", "world", worldName));
            runCallback(callback, ArenaRebuildResult.SUCCESS);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> cleanupFolder(backupFolder, worldName));
        } catch (RuntimeException e) {
            arena.setSpawnPoints(previousSpawns);
            plugin.getLogger().log(Level.SEVERE, translations.text(
                    "logs.arena-reset-failed",
                    "world", worldName,
                    "error", e.getMessage()
            ), e);
            rollbackFailedWorldLoad(newWorld, arenaFolder, backupFolder, worldName, callback);
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
            Path arenaFolder,
            Path backupFolder,
            String worldName,
            Consumer<ArenaRebuildResult> callback
    ) {
        if (newWorld != null && !Bukkit.unloadWorld(newWorld, false)) {
            plugin.getLogger().severe(translations.text("logs.arena-reset-unload-failed", "world", worldName));
            runCallback(callback, ArenaRebuildResult.WORLD_LOAD_FAILED);
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            rollbackActivatedArena(arenaFolder, backupFolder, worldName);
            runCallback(callback, ArenaRebuildResult.WORLD_LOAD_FAILED);
        });
    }

    private void runCallback(Consumer<ArenaRebuildResult> callback, ArenaRebuildResult result) {
        if (callback != null) {
            Bukkit.getScheduler().runTask(plugin, () -> callback.accept(result));
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
}
