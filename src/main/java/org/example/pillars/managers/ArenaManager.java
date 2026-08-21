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
    public static final int MIN_PLAYERS_TO_START = 2;

    private final PillarsPlugin plugin;
    private final TranslationManager translations;
    private final Map<String, Arena> arenas = new HashMap<>();

    private final File worldContainer = Bukkit.getWorldContainer();
    private final File templateWorld = new File(worldContainer, "arena_template");

    public ArenaManager(PillarsPlugin plugin, TranslationManager translations) {
        this.plugin = plugin;
        this.translations = translations;
        loadArenas();
    }

    public Arena getArena(String worldName) {
        return arenas.get(worldName);
    }

    public void loadArenas() {
        arenas.clear();

        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.getLogger().severe(translations.text("logs.config-missing"));
            return;
        }

        if (!templateWorld.exists() || !templateWorld.isDirectory()) {
            plugin.getLogger().severe(translations.text(
                    "logs.template-world-missing",
                    "path", templateWorld.getAbsolutePath()
            ));
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

                    plugin.getLogger().info(translations.text("logs.arena-world-created", "world", worldName));
                } catch (IOException | RuntimeException e) {
                    plugin.getLogger().severe(translations.text(
                            "logs.arena-template-copy-failed",
                            "world", worldName,
                            "error", e.getMessage()
                    ));
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
                plugin.getLogger().severe(translations.text("logs.world-load-failed", "world", worldName));
                continue;
            }

            world.setAutoSave(false);
            world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
            world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
            world.setTime(6000);

            Arena arena = new Arena();
            arena.setConfigKey(key);
            arena.setWorldName(worldName);
            arena.setDisplayName(getLocalizedDisplayName(sec, worldName));
            arena.setJoiningOpen(sec.getBoolean("joiningOpen", true));
            arena.setItemCooldownSeconds(sec.getInt("itemCooldownSeconds", 0));

            List<Location> spawns = new ArrayList<>();
            for (Object obj : sec.getList("spawnPoints", Collections.emptyList())) {
                if (obj instanceof List<?> coords && coords.size() >= 3
                        && coords.get(0) instanceof Number
                        && coords.get(1) instanceof Number
                        && coords.get(2) instanceof Number) {
                    double x = ((Number) coords.get(0)).doubleValue();
                    double y = ((Number) coords.get(1)).doubleValue();
                    double z = ((Number) coords.get(2)).doubleValue();
                    spawns.add(new Location(world, x, y, z));
                }
            }

            if (spawns.isEmpty()) {
                plugin.getLogger().severe(translations.text("logs.arena-no-spawns", "world", worldName));
                continue;
            }

            if (spawns.size() < MIN_PLAYERS_TO_START) {
                plugin.getLogger().severe(translations.text("logs.arena-too-small", "world", worldName));
                continue;
            }

            arena.setSpawnPoints(spawns);
            int defaultMinPlayers = Math.max(MIN_PLAYERS_TO_START, (int) Math.ceil(spawns.size() / 2.0));
            arena.setMinPlayers(Math.max(
                    MIN_PLAYERS_TO_START,
                    Math.min(spawns.size(), sec.getInt("minPlayers", defaultMinPlayers))
            ));
            arenas.put(worldName, arena);
        }

        plugin.getLogger().info(translations.text("logs.arenas-loaded", "count", arenas.size()));
    }

    public void resetArena(Arena arena, Runnable callback) {
        String worldName = arena.getWorldName();
        World world = Bukkit.getWorld(worldName);

        if (!templateWorld.exists() || !templateWorld.isDirectory()) {
            plugin.getLogger().severe(translations.text(
                    "logs.arena-reset-template-missing",
                    "world", worldName,
                    "path", templateWorld.getAbsolutePath()
            ));
            runResetCallback(callback);
            return;
        }

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

            } catch (IOException | RuntimeException e) {
                plugin.getLogger().severe(translations.text(
                        "logs.arena-reset-failed",
                        "world", worldName,
                        "error", e.getMessage()
                ));
                runResetCallback(callback);
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

                    plugin.getLogger().info(translations.text("logs.arena-reset-completed", "world", worldName));

                    if (callback != null) {
                        callback.run();
                    }
                } else {
                    plugin.getLogger().severe(translations.text("logs.world-load-failed", "world", worldName));
                    if (callback != null) {
                        callback.run();
                    }
                }
            });
        });
    }

    private void runResetCallback(Runnable callback) {
        if (callback != null) {
            Bukkit.getScheduler().runTask(plugin, callback);
        }
    }

    private void copyFolder(Path source, Path target) throws IOException {
        try (var paths = Files.walk(source)) {
            paths.forEach(path -> {
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
    }

    private void deleteFolder(Path path) throws IOException {
        try (var paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }

    public Collection<Arena> getArenas() {
        return arenas.values();
    }

    public void updateSafeArenaSettings(Arena arena, int minPlayers, int itemCooldownSeconds) {
        if (arena == null || arena.getSpawnPoints() == null
                || arena.getSpawnPoints().size() < MIN_PLAYERS_TO_START) {
            return;
        }

        int clampedMinPlayers = Math.max(
                MIN_PLAYERS_TO_START,
                Math.min(arena.getSpawnPoints().size(), minPlayers)
        );
        int clampedItemCooldownSeconds = Math.max(1, itemCooldownSeconds);

        arena.setMinPlayers(clampedMinPlayers);
        arena.setItemCooldownSeconds(clampedItemCooldownSeconds);

        String configKey = getConfigKey(arena);
        if (configKey == null) {
            plugin.getLogger().warning(translations.text(
                    "logs.arena-settings-key-missing",
                    "world", arena.getWorldName()
            ));
            return;
        }

        plugin.getConfig().set("arenas." + configKey + ".minPlayers", clampedMinPlayers);
        plugin.getConfig().set("arenas." + configKey + ".itemCooldownSeconds", clampedItemCooldownSeconds);
        plugin.saveConfig();
    }

    public void updateArenaJoiningOpen(Arena arena, boolean joiningOpen) {
        if (arena == null) {
            return;
        }

        arena.setJoiningOpen(joiningOpen);

        String configKey = getConfigKey(arena);
        if (configKey == null) {
            plugin.getLogger().warning(translations.text(
                    "logs.arena-joining-key-missing",
                    "world", arena.getWorldName()
            ));
            return;
        }

        plugin.getConfig().set("arenas." + configKey + ".joiningOpen", joiningOpen);
        plugin.saveConfig();
    }

    private String getConfigKey(Arena arena) {
        if (arena.getConfigKey() != null && !arena.getConfigKey().isEmpty()) {
            return arena.getConfigKey();
        }

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("arenas");
        if (section == null) {
            return null;
        }

        for (String key : section.getKeys(false)) {
            if (arena.getWorldName().equals(section.getString(key + ".worldName"))) {
                arena.setConfigKey(key);
                return key;
            }
        }

        return null;
    }

    public Arena getArenaByDisplayName(String displayName) {
        return arenas.values().stream()
                .filter(a -> a.getDisplayName().equalsIgnoreCase(displayName))
                .findFirst().orElse(null);
    }

    private String getLocalizedDisplayName(ConfigurationSection section, String worldName) {
        String localizedName = section.getString("displayName." + translations.getLanguage());
        if (localizedName == null || localizedName.isBlank()) {
            localizedName = section.getString("displayName.en");
        }
        if (localizedName == null || localizedName.isBlank()) {
            localizedName = section.getString("displayName", worldName);
        }
        return localizedName.toLowerCase(Locale.ROOT);
    }

}
