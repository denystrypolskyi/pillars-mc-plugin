package org.example.pillars.managers;

import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.example.pillars.PillarsPlugin;
import org.example.pillars.entities.Arena;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class ArenaManager {

    private final PillarsPlugin plugin;
    private final Map<String, Arena> arenas = new HashMap<>();

    private final File worldContainer = Bukkit.getWorldContainer();
    private final File templateWorld = new File(worldContainer, "arena_template");

    public ArenaManager(PillarsPlugin plugin) {
        this.plugin = plugin;
    }

    public Arena getArena(String worldName) {
        return arenas.get(worldName);
    }

    public void loadArenas() {
        arenas.clear();

        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            return;
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        ConfigurationSection section = config.getConfigurationSection("arenas");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            ConfigurationSection sec = section.getConfigurationSection(key);
            if (sec == null) continue;

            String worldName = sec.getString("worldName");
            if (worldName == null || worldName.isEmpty()) continue;

            File arenaFolder = new File(worldContainer, worldName);

            if (!arenaFolder.exists()) {
                try {
                    copyFolder(templateWorld.toPath(), arenaFolder.toPath());

                    new File(arenaFolder, "uid.dat").delete();
                    new File(arenaFolder, "session.lock").delete();

                    plugin.getLogger().info("Created arena world: " + worldName);
                } catch (IOException e) {
                    plugin.getLogger().severe("Failed to copy arena template for " + worldName);
                    continue;
                }
            }

            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                WorldCreator creator = new WorldCreator(worldName);
                creator.generateStructures(false);
                world = creator.createWorld();
            }

            if (world == null) {
                plugin.getLogger().severe("Failed to load world " + worldName);
                continue;
            }

            world.setAutoSave(false);
            world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
            world.setTime(6000);

            Arena arena = new Arena();
            arena.setWorldName(worldName);
            arena.setDisplayName(sec.getString("displayName", worldName));
            arena.setItemCooldownSeconds(sec.getInt("itemCooldownSeconds", 0));

            List<Location> spawns = new ArrayList<>();
            for (Object obj : sec.getList("spawnPoints", Collections.emptyList())) {
                if (obj instanceof List<?> coords && coords.size() >= 3) {
                    double x = ((Number) coords.get(0)).doubleValue();
                    double y = ((Number) coords.get(1)).doubleValue();
                    double z = ((Number) coords.get(2)).doubleValue();
                    spawns.add(new Location(world, x, y, z));
                }
            }

            arena.setSpawnPoints(spawns);
            arenas.put(worldName, arena);
        }

        plugin.getLogger().info("Loaded " + arenas.size() + " arenas.");
    }

    public void resetArenaAsync(Arena arena, Runnable callback) {
        String worldName = arena.getWorldName();
        World world = Bukkit.getWorld(worldName);

        if (world != null) {
            Bukkit.unloadWorld(world, false);
        }

        File arenaFolder = new File(worldContainer, worldName);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (arenaFolder.exists()) {
                    deleteFolder(arenaFolder.toPath());
                }

                copyFolder(templateWorld.toPath(), arenaFolder.toPath());

                new File(arenaFolder, "uid.dat").delete();
                new File(arenaFolder, "session.lock").delete();

            } catch (IOException e) {
                plugin.getLogger().severe("Failed to reset arena world " + worldName);
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                WorldCreator creator = new WorldCreator(worldName);
                creator.generateStructures(false);
                World newWorld = creator.createWorld();

                if (newWorld != null) {
                    newWorld.setAutoSave(false);
                    newWorld.setGameRule(GameRule.DO_MOB_SPAWNING, false);
                    newWorld.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
                    newWorld.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
                    newWorld.setTime(6000);

                    List<Location> newSpawns = new ArrayList<>();
                    for (Location loc : arena.getSpawnPoints()) {
                        newSpawns.add(new Location(newWorld, loc.getX(), loc.getY(), loc.getZ()));
                    }
                    arena.setSpawnPoints(newSpawns);

                    arenas.put(worldName, arena);

                    plugin.getLogger().info("Arena async reset completed " + worldName);

                    if (callback != null) {
                        callback.run();
                    }
                } else {
                    plugin.getLogger().severe("Failed to load world " + worldName);
                }
            });
        });
    }

    private void copyFolder(Path source, Path target) throws IOException {
        Files.walk(source).forEach(path -> {
            try {
                Path dest = target.resolve(source.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dest);
                } else {
                    Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void deleteFolder(Path path) throws IOException {
        Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }


}
