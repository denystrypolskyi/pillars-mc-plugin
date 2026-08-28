package org.example.pillars.managers;

import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.example.pillars.PillarsPlugin;
import org.example.pillars.entities.Arena;
import org.example.pillars.enums.ArenaGameMode;
import org.example.pillars.enums.ArenaRebuildResult;
import org.example.pillars.enums.FloorShape;
import org.example.pillars.enums.ItemDeliveryMode;

import java.io.File;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ArenaManager {
    public static final int MIN_PLAYERS_TO_START = 2;
    public static final int MIN_BORDER_SHRINK_SECONDS = 60;
    public static final int MAX_BORDER_SHRINK_SECONDS = 1200;
    private static final Pattern LEGACY_DEFAULT_ARENA_NAME = Pattern.compile("^(4|8|12) (?:#|№)(\\d+)$");

    private final PillarsPlugin plugin;
    private final TranslationManager translations;
    private final ArenaFloorService floorService;
    private final ArenaWorldService worldService;
    private final Map<String, Arena> arenas = new HashMap<>();
    private final Map<String, ArenaRebuildDraft> rebuildDrafts = new HashMap<>();

    private record ArenaRebuildDraft(
            boolean floorEnabled,
            Material floorMaterial,
            FloorShape floorShape,
            int floorRadius,
            int floorY,
            List<Location> spawnPoints
    ) {
    }

    public ArenaManager(
            PillarsPlugin plugin,
            TranslationManager translations,
            ArenaFloorService floorService,
            ArenaWorldService worldService
    ) {
        this.plugin = plugin;
        this.translations = translations;
        this.floorService = floorService;
        this.worldService = worldService;
        removeLegacyGlobalBorderShrinkSetting();
        loadArenas();
    }

    private void removeLegacyGlobalBorderShrinkSetting() {
        String legacyPath = "settings.borderShrinkSeconds";
        if (!plugin.getConfig().contains(legacyPath)) return;

        plugin.getConfig().set(legacyPath, null);
        plugin.saveConfig();
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
        rebuildDrafts.clear();

        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            plugin.getLogger().severe(translations.text("logs.config-missing"));
            return;
        }

        if (!worldService.isTemplateAvailable()) {
            plugin.getLogger().severe(translations.text(
                    "logs.template-world-missing",
                    "path", worldService.getTemplatePath()
            ));
            return;
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        ConfigurationSection section = config.getConfigurationSection("arenas");
        if (section == null) return;

        Map<String, Integer> worldNameCounts = new HashMap<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection arenaSection = section.getConfigurationSection(key);
            if (arenaSection == null) continue;
            String configuredWorldName = arenaSection.getString("worldName");
            if (configuredWorldName == null || configuredWorldName.isEmpty()) continue;
            worldNameCounts.merge(configuredWorldName.toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        Set<String> loggedDuplicateWorldNames = new HashSet<>();

        for (String key : section.getKeys(false)) {
            ConfigurationSection sec = section.getConfigurationSection(key);
            if (sec == null) continue;

            String worldName = sec.getString("worldName");
            if (worldName == null || worldName.isEmpty()) continue;

            String normalizedWorldName = worldName.toLowerCase(Locale.ROOT);
            if (worldNameCounts.getOrDefault(normalizedWorldName, 0) > 1) {
                if (loggedDuplicateWorldNames.add(normalizedWorldName)) {
                    plugin.getLogger().severe(translations.text(
                            "logs.arena-duplicate-world",
                            "world", worldName
                    ));
                }
                continue;
            }

            World world = worldService.loadOrCreate(worldName);
            if (world == null) continue;

            Arena arena = new Arena();
            arena.setConfigKey(key);
            arena.setWorldName(worldName);
            arena.setDisplayName(getLocalizedDisplayName(sec, worldName));
            arena.setJoiningOpen(sec.getBoolean("joiningOpen", true));
            arena.setItemCooldownSeconds(sec.getInt("itemCooldownSeconds", 0));
            arena.setItemDeliveryMode(ItemDeliveryMode.fromConfig(sec.getString("itemDeliveryMode", "single")));
            arena.setGameMode(ArenaGameMode.fromConfig(sec.getString("gameMode", "standard")));

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
            int defaultBorderShrinkSeconds = switch (spawns.size()) {
                case 4 -> 240;
                case 8 -> 360;
                default -> 480;
            };
            arena.setBorderShrinkSeconds(Math.max(
                    MIN_BORDER_SHRINK_SECONDS,
                    Math.min(MAX_BORDER_SHRINK_SECONDS,
                            sec.getInt("borderShrinkSeconds", defaultBorderShrinkSeconds))
            ));
            floorService.loadSettings(arena, sec);
            int defaultMinPlayers = Math.max(MIN_PLAYERS_TO_START, (int) Math.ceil(spawns.size() / 2.0));
            arena.setMinPlayers(Math.max(
                    MIN_PLAYERS_TO_START,
                    Math.min(spawns.size(), sec.getInt("minPlayers", defaultMinPlayers))
            ));
            arenas.put(worldName, arena);
            floorService.generate(arena);
        }

        plugin.getLogger().info(translations.text("logs.arenas-loaded", "count", arenas.size()));
    }

    public void resetArena(Arena arena, Consumer<ArenaRebuildResult> callback) {
        ArenaRebuildDraft draft = rebuildDrafts.get(arena.getWorldName());
        if (draft == null) {
            worldService.rebuild(arena, callback);
            return;
        }

        ArenaRebuildDraft previousLiveSettings = snapshot(arena);
        applyDraft(arena, draft);
        worldService.rebuild(arena, result -> {
            if (result == ArenaRebuildResult.SUCCESS) {
                persistRebuildSettings(arena, draft);
                rebuildDrafts.remove(arena.getWorldName(), draft);
            } else {
                applyDraft(arena, previousLiveSettings);
            }

            if (callback != null) {
                callback.accept(result);
            }
        });
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

    public void updateArenaItemDeliveryMode(Arena arena, ItemDeliveryMode mode) {
        if (arena == null || mode == null) return;
        arena.setItemDeliveryMode(mode);

        String configKey = getConfigKey(arena);
        if (configKey == null) {
            plugin.getLogger().warning(translations.text(
                    "logs.arena-settings-key-missing",
                    "world", arena.getWorldName()
            ));
            return;
        }

        plugin.getConfig().set("arenas." + configKey + ".itemDeliveryMode", mode.name());
        plugin.saveConfig();
    }

    public void updateArenaGameMode(Arena arena, ArenaGameMode mode) {
        if (arena == null || mode == null) return;
        arena.setGameMode(mode);

        String configKey = getConfigKey(arena);
        if (configKey == null) {
            plugin.getLogger().warning(translations.text(
                    "logs.arena-settings-key-missing",
                    "world", arena.getWorldName()
            ));
            return;
        }

        plugin.getConfig().set("arenas." + configKey + ".gameMode", mode.name());
        plugin.saveConfig();
    }

    public void updateArenaBorderShrinkSeconds(Arena arena, int seconds) {
        if (arena == null) return;

        int clampedSeconds = Math.max(
                MIN_BORDER_SHRINK_SECONDS,
                Math.min(MAX_BORDER_SHRINK_SECONDS, seconds)
        );
        arena.setBorderShrinkSeconds(clampedSeconds);

        String configKey = getConfigKey(arena);
        if (configKey == null) {
            plugin.getLogger().warning(translations.text(
                    "logs.arena-settings-key-missing",
                    "world", arena.getWorldName()
            ));
            return;
        }

        plugin.getConfig().set("arenas." + configKey + ".borderShrinkSeconds", clampedSeconds);
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
        if (arena == null || !floorService.isFloorMaterial(material)) {
            return;
        }

        String configKey = getConfigKey(arena);
        if (configKey == null) {
            plugin.getLogger().warning(translations.text(
                    "logs.arena-settings-key-missing",
                    "world", arena.getWorldName()
            ));
            return;
        }

        ArenaRebuildDraft current = getDraft(arena);
        ArenaRebuildDraft updated = new ArenaRebuildDraft(
                enabled,
                material,
                shape == null ? FloorShape.SQUARE : shape,
                Math.max(2, Math.min(64, radius)),
                Math.max(getMinimumFloorY(arena), Math.min(getMaximumFloorY(arena), y)),
                current.spawnPoints()
        );
        rebuildDrafts.put(arena.getWorldName(), updated);
    }

    public boolean isArenaFloorEnabled(Arena arena) {
        return getDraft(arena).floorEnabled();
    }

    public Material getArenaFloorMaterial(Arena arena) {
        return getDraft(arena).floorMaterial();
    }

    public FloorShape getArenaFloorShape(Arena arena) {
        return getDraft(arena).floorShape();
    }

    public int getArenaFloorRadius(Arena arena) {
        return getDraft(arena).floorRadius();
    }

    public int getArenaFloorY(Arena arena) {
        return getDraft(arena).floorY();
    }

    public int getMinimumFloorY(Arena arena) {
        return floorService.getMinimumY(arena);
    }

    public int getMaximumFloorY(Arena arena) {
        return floorService.getMaximumY(arena);
    }

    public boolean symmetrizeArenaSpawns(Arena arena) {
        if (arena == null || arena.getSpawnPoints() == null || arena.getSpawnPoints().size() < 2) return false;

        String configKey = getConfigKey(arena);
        if (configKey == null) return false;

        ArenaRebuildDraft current = getDraft(arena);
        List<Location> currentSpawns = current.spawnPoints();

        Location center = centerOf(currentSpawns);
        World world = center.getWorld();
        if (world == null) return false;

        double radius = currentSpawns.stream()
                .mapToDouble(spawn -> Math.max(
                        Math.abs(spawn.getX() - center.getX()),
                        Math.abs(spawn.getZ() - center.getZ())
                ))
                .average()
                .orElse(1.0);
        double y = currentSpawns.stream().mapToDouble(Location::getY).average().orElse(center.getY());
        int count = currentSpawns.size();
        List<Location> symmetricSpawns = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            double perimeterPosition = 8.0 * radius * i / count;
            double[] offset = squarePerimeterOffset(perimeterPosition, radius);
            double x = roundCoordinate(center.getX() + offset[0]);
            double z = roundCoordinate(center.getZ() + offset[1]);
            symmetricSpawns.add(new Location(world, x, y, z));
        }

        rebuildDrafts.put(arena.getWorldName(), new ArenaRebuildDraft(
                current.floorEnabled(),
                current.floorMaterial(),
                current.floorShape(),
                current.floorRadius(),
                current.floorY(),
                List.copyOf(symmetricSpawns)
        ));
        return true;
    }

    private ArenaRebuildDraft getDraft(Arena arena) {
        return rebuildDrafts.getOrDefault(arena.getWorldName(), snapshot(arena));
    }

    private ArenaRebuildDraft snapshot(Arena arena) {
        return new ArenaRebuildDraft(
                arena.isFloorEnabled(),
                arena.getFloorMaterial(),
                arena.getFloorShape(),
                arena.getFloorRadius(),
                arena.getFloorY(),
                copyLocations(arena.getSpawnPoints())
        );
    }

    private void applyDraft(Arena arena, ArenaRebuildDraft draft) {
        arena.setFloorEnabled(draft.floorEnabled());
        arena.setFloorMaterial(draft.floorMaterial());
        arena.setFloorShape(draft.floorShape());
        arena.setFloorRadius(draft.floorRadius());
        arena.setFloorY(draft.floorY());
        arena.setSpawnPoints(copyLocations(draft.spawnPoints()));
    }

    private void persistRebuildSettings(Arena arena, ArenaRebuildDraft draft) {
        String configKey = getConfigKey(arena);
        if (configKey == null) return;

        String floorPath = "arenas." + configKey + ".floor.";
        plugin.getConfig().set(floorPath + "enabled", draft.floorEnabled());
        plugin.getConfig().set(floorPath + "shape", draft.floorShape().configValue());
        plugin.getConfig().set(floorPath + "radius", draft.floorRadius());
        plugin.getConfig().set(floorPath + "y", draft.floorY());
        plugin.getConfig().set(floorPath + "material", draft.floorMaterial().name());

        List<List<Double>> serializedSpawns = draft.spawnPoints().stream()
                .map(location -> List.of(
                        roundCoordinate(location.getX()),
                        roundCoordinate(location.getY()),
                        roundCoordinate(location.getZ())
                ))
                .toList();
        plugin.getConfig().set("arenas." + configKey + ".spawnPoints", serializedSpawns);
        plugin.saveConfig();
    }

    private List<Location> copyLocations(List<Location> locations) {
        return locations.stream().map(Location::clone).toList();
    }

    private Location centerOf(List<Location> locations) {
        double x = 0;
        double y = 0;
        double z = 0;
        for (Location location : locations) {
            x += location.getX();
            y += location.getY();
            z += location.getZ();
        }

        Location base = locations.getFirst();
        return new Location(base.getWorld(), x / locations.size(), y / locations.size(), z / locations.size());
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
        String localizedName = section.getString("displayName.ru");
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
        return translations.text("arena-sizes." + sizeKey) + " №" + legacyName.group(2);
    }

}
