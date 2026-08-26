package org.example.pillars.managers;

import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.example.pillars.PillarsPlugin;
import org.example.pillars.entities.Arena;
import org.example.pillars.enums.FloorShape;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ArenaManager {
    public static final int MIN_PLAYERS_TO_START = 2;
    public static final int MIN_FLOOR_DISTANCE_BELOW_SPAWNS = 12;
    public static final int MAX_FLOOR_DISTANCE_BELOW_SPAWNS = 70;
    public static final int FLOOR_ELIMINATION_MARGIN = 8;
    private static final Pattern LEGACY_DEFAULT_ARENA_NAME = Pattern.compile("^(4|8|12) (?:#|№)(\\d+)$");

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

    public Arena findArena(String name) {
        if (name == null || name.isBlank()) return null;

        Arena exactWorld = arenas.get(name);
        if (exactWorld != null) return exactWorld;

        String normalized = normalizeDefaultArenaName(translations.displayName(name));
        return arenas.values().stream()
                .filter(arena -> arena.getWorldName().equalsIgnoreCase(name)
                        || arena.getConfigKey().equalsIgnoreCase(name)
                        || arena.getDisplayName().equalsIgnoreCase(normalized))
                .findFirst()
                .orElse(null);
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
            loadFloorSettings(arena, sec);
            int defaultMinPlayers = Math.max(MIN_PLAYERS_TO_START, (int) Math.ceil(spawns.size() / 2.0));
            arena.setMinPlayers(Math.max(
                    MIN_PLAYERS_TO_START,
                    Math.min(spawns.size(), sec.getInt("minPlayers", defaultMinPlayers))
            ));
            arenas.put(worldName, arena);
            generateArenaFloor(arena);
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
                    generateArenaFloor(arena);

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

    public void updateArenaFloorSettings(
            Arena arena,
            boolean enabled,
            Material material,
            FloorShape shape,
            int radius,
            int y
    ) {
        if (arena == null || !isFloorMaterial(material)) {
            return;
        }

        arena.setFloorEnabled(enabled);
        arena.setFloorMaterial(material);
        arena.setFloorShape(shape == null ? FloorShape.SQUARE : shape);
        arena.setFloorRadius(Math.max(2, Math.min(64, radius)));
        arena.setFloorY(Math.max(getMinimumFloorY(arena), Math.min(getMaximumFloorY(arena), y)));

        String configKey = getConfigKey(arena);
        if (configKey == null) {
            plugin.getLogger().warning(translations.text(
                    "logs.arena-settings-key-missing",
                    "world", arena.getWorldName()
            ));
            return;
        }

        String path = "arenas." + configKey + ".floor.";
        plugin.getConfig().set(path + "enabled", arena.isFloorEnabled());
        plugin.getConfig().set(path + "shape", arena.getFloorShape().configValue());
        plugin.getConfig().set(path + "radius", arena.getFloorRadius());
        plugin.getConfig().set(path + "y", arena.getFloorY());
        plugin.getConfig().set(path + "material", arena.getFloorMaterial().name());
        plugin.saveConfig();
    }

    private void loadFloorSettings(Arena arena, ConfigurationSection section) {
        int defaultRadius = switch (arena.getSpawnPoints().size()) {
            case 4 -> 8;
            case 8 -> 16;
            default -> 20;
        };
        int defaultY = (int) Math.floor(arena.getSpawnPoints().stream()
                .mapToDouble(Location::getY)
                .min()
                .orElse(100.0)) - 25;
        Material material = Material.matchMaterial(section.getString("floor.material", "LAVA"));
        if (!isFloorMaterial(material)) {
            material = Material.LAVA;
        }

        arena.setFloorEnabled(section.getBoolean("floor.enabled", true));
        arena.setFloorMaterial(material);
        arena.setFloorShape(FloorShape.fromConfig(section.getString("floor.shape", "square")));
        arena.setFloorRadius(Math.max(2, Math.min(64, section.getInt("floor.radius", defaultRadius))));

        arena.setFloorY(Math.max(
                getMinimumFloorY(arena),
                Math.min(getMaximumFloorY(arena), section.getInt("floor.y", defaultY))
        ));
    }

    public int getMinimumFloorY(Arena arena) {
        World world = arena.getSpawnPoints().get(0).getWorld();
        int worldMinimum = world == null ? 0 : world.getMinHeight() + FLOOR_ELIMINATION_MARGIN + 1;
        int spawnMinimum = minimumSpawnY(arena);
        return Math.max(worldMinimum, spawnMinimum - MAX_FLOOR_DISTANCE_BELOW_SPAWNS);
    }

    public int getMaximumFloorY(Arena arena) {
        World world = arena.getSpawnPoints().get(0).getWorld();
        int worldMaximum = world == null ? 319 : world.getMaxHeight() - 2;
        return Math.max(getMinimumFloorY(arena), Math.min(
                worldMaximum,
                minimumSpawnY(arena) - MIN_FLOOR_DISTANCE_BELOW_SPAWNS
        ));
    }

    private int minimumSpawnY(Arena arena) {
        return (int) Math.floor(arena.getSpawnPoints().stream()
                .mapToDouble(Location::getY)
                .min()
                .orElse(100.0));
    }

    private void generateArenaFloor(Arena arena) {
        if (!arena.isFloorEnabled() || arena.getSpawnPoints().isEmpty()) return;

        Location center = arena.getCenter();
        World world = center.getWorld();
        if (world == null) return;

        int y = arena.getFloorY();
        Material material = arena.getFloorMaterial();
        boolean basin = material == Material.LAVA || material == Material.WATER;
        Set<Long> blocks = floorBlocks(arena, center.getBlockX(), center.getBlockZ());

        for (long packed : blocks) {
            int x = (int) (packed >> 32);
            int z = (int) packed;
            if (!basin) {
                world.getBlockAt(x, y, z).setType(material, false);
                continue;
            }

            world.getBlockAt(x, y - 1, z).setType(Material.GLASS, false);
            world.getBlockAt(x, y, z).setType(isFloorEdge(blocks, x, z) ? Material.GLASS : material, false);
        }
    }

    private Set<Long> floorBlocks(Arena arena, int centerX, int centerZ) {
        Set<Long> blocks = new HashSet<>();
        int radius = arena.getFloorRadius();

        if (arena.getFloorShape() == FloorShape.ISLANDS) {
            int islandRadius = Math.max(2, radius / 5);
            for (Location spawn : arena.getSpawnPoints()) {
                addSquare(blocks, spawn.getBlockX(), spawn.getBlockZ(), islandRadius, 0);
            }
            return blocks;
        }

        int innerRadius = arena.getFloorShape() == FloorShape.SQUARE_RING ? Math.max(1, radius / 2) : 0;
        addSquare(blocks, centerX, centerZ, radius, innerRadius);
        return blocks;
    }

    private void addSquare(Set<Long> blocks, int centerX, int centerZ, int radius, int innerRadius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int distance = Math.max(Math.abs(dx), Math.abs(dz));
                if (distance <= radius && distance >= innerRadius) {
                    blocks.add(pack(centerX + dx, centerZ + dz));
                }
            }
        }
    }

    private long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private boolean isFloorEdge(Set<Long> blocks, int x, int z) {
        int[][] neighbors = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, -1}, {-1, 1}};
        for (int[] offset : neighbors) {
            if (!blocks.contains(pack(x + offset[0], z + offset[1]))) return true;
        }
        return false;
    }

    private boolean isFloorMaterial(Material material) {
        return material != null
                && material.isBlock()
                && !material.isAir()
                && ((material.isItem() && material.isSolid())
                || material == Material.LAVA
                || material == Material.WATER);
    }

    public boolean symmetrizeArenaSpawns(Arena arena) {
        if (arena == null || arena.getSpawnPoints() == null || arena.getSpawnPoints().size() < 2) return false;

        Location center = arena.getCenter();
        World world = center.getWorld();
        if (world == null) return false;

        double radius = arena.getSpawnPoints().stream()
                .mapToDouble(spawn -> Math.max(
                        Math.abs(spawn.getX() - center.getX()),
                        Math.abs(spawn.getZ() - center.getZ())
                ))
                .average()
                .orElse(1.0);
        double y = arena.getSpawnPoints().stream().mapToDouble(Location::getY).average().orElse(center.getY());
        int count = arena.getSpawnPoints().size();
        List<Location> symmetricSpawns = new ArrayList<>(count);
        List<List<Double>> serializedSpawns = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            double perimeterPosition = 8.0 * radius * i / count;
            double[] offset = squarePerimeterOffset(perimeterPosition, radius);
            double x = roundCoordinate(center.getX() + offset[0]);
            double z = roundCoordinate(center.getZ() + offset[1]);
            symmetricSpawns.add(new Location(world, x, y, z));
            serializedSpawns.add(List.of(x, roundCoordinate(y), z));
        }

        arena.setSpawnPoints(symmetricSpawns);
        String configKey = getConfigKey(arena);
        if (configKey == null) return false;
        plugin.getConfig().set("arenas." + configKey + ".spawnPoints", serializedSpawns);
        plugin.saveConfig();
        return true;
    }

    private double[] squarePerimeterOffset(double position, double radius) {
        double sideLength = radius * 2.0;
        if (position < sideLength) return new double[]{-radius + position, -radius};
        if (position < sideLength * 2.0) return new double[]{radius, -radius + position - sideLength};
        if (position < sideLength * 3.0) return new double[]{radius - (position - sideLength * 2.0), radius};
        return new double[]{-radius, radius - (position - sideLength * 3.0)};
    }

    private double roundCoordinate(double value) {
        return Math.round(value * 100.0) / 100.0;
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
        return normalizeDefaultArenaName(translations.displayName(localizedName));
    }

    private String normalizeDefaultArenaName(String displayName) {
        String normalized = displayName.replaceFirst("(?iu)^(?:arena|арена) (?=\\d+ (?:#|№)\\d+$)", "");
        Matcher legacyName = LEGACY_DEFAULT_ARENA_NAME.matcher(normalized);
        if (!legacyName.matches()) {
            return normalized;
        }

        String sizeKey = switch (legacyName.group(1)) {
            case "4" -> "mini";
            case "8" -> "standard";
            case "12" -> "large";
            default -> throw new IllegalStateException("Unexpected default arena size");
        };
        String numberSign = translations.getLanguage().equals("ru") ? "№" : "#";
        return translations.text("arena-sizes." + sizeKey) + " " + numberSign + legacyName.group(2);
    }

}
