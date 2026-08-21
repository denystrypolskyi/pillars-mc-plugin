package org.example.pillars.managers;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class TranslationManager {
    private static final String DEFAULT_LANGUAGE = "en";
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("en", "ru");
    private static final Set<String> PLAYER_FACING_SECTIONS = Set.of(
            "plugin", "welcome-v2", "welcome-v3", "lobby-items", "admin-item", "game-items",
            "scoreboard", "titles", "action-bar", "messages", "rarities", "units",
            "arena-view", "menus"
    );

    private final JavaPlugin plugin;
    private final String language;
    private final YamlConfiguration english;
    private final YamlConfiguration selected;
    private final YamlConfiguration defaultEnglish;
    private final YamlConfiguration defaultSelected;
    private final Set<String> reportedMissingKeys = new HashSet<>();

    public TranslationManager(JavaPlugin plugin) {
        this.plugin = plugin;

        saveLanguageFile(DEFAULT_LANGUAGE);
        saveLanguageFile("ru");

        String configuredLanguage = plugin.getConfig().getString("language", DEFAULT_LANGUAGE);
        String normalizedLanguage = configuredLanguage == null
                ? DEFAULT_LANGUAGE
                : configuredLanguage.trim().toLowerCase(Locale.ROOT);

        if (!SUPPORTED_LANGUAGES.contains(normalizedLanguage)) {
            plugin.getLogger().warning(
                    "Unsupported language '" + configuredLanguage + "'. Falling back to '" + DEFAULT_LANGUAGE + "'."
            );
            normalizedLanguage = DEFAULT_LANGUAGE;
        }

        this.language = normalizedLanguage;
        this.english = loadLanguageFile(DEFAULT_LANGUAGE);
        this.selected = language.equals(DEFAULT_LANGUAGE) ? english : loadLanguageFile(language);
        this.defaultEnglish = loadBundledLanguageFile(DEFAULT_LANGUAGE);
        this.defaultSelected = language.equals(DEFAULT_LANGUAGE) ? defaultEnglish : loadBundledLanguageFile(language);
    }

    public String getLanguage() {
        return language;
    }

    public String text(String key, Object... placeholders) {
        String value = selected.getString(key);
        if (value == null) {
            value = defaultSelected.getString(key);
        }
        if (value == null) {
            value = english.getString(key);
        }
        if (value == null) {
            value = defaultEnglish.getString(key);
        }
        if (value == null) {
            reportMissingKey(key);
            return key;
        }

        return format(styleTemplate(key, value), placeholders);
    }

    public List<String> list(String key, Object... placeholders) {
        List<String> values = selected.isList(key) ? selected.getStringList(key) : null;
        if (values == null) {
            values = defaultSelected.isList(key) ? defaultSelected.getStringList(key) : null;
        }
        if (values == null) {
            values = english.isList(key) ? english.getStringList(key) : null;
        }
        if (values == null) {
            values = defaultEnglish.isList(key) ? defaultEnglish.getStringList(key) : null;
        }
        if (values == null) {
            reportMissingKey(key);
            return List.of(key);
        }

        return values.stream()
                .map(value -> format(styleTemplate(key, value), placeholders))
                .toList();
    }

    public String plural(String key, int amount, Object... placeholders) {
        String form;
        if (language.equals("ru")) {
            int lastTwoDigits = Math.abs(amount) % 100;
            int lastDigit = Math.abs(amount) % 10;
            if (lastTwoDigits >= 11 && lastTwoDigits <= 14) {
                form = "many";
            } else if (lastDigit == 1) {
                form = "one";
            } else if (lastDigit >= 2 && lastDigit <= 4) {
                form = "few";
            } else {
                form = "many";
            }
        } else {
            form = amount == 1 ? "one" : "many";
        }

        return text(key + "." + form, placeholders);
    }

    private String format(String value, Object... placeholders) {
        String formatted = value;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            formatted = formatted.replace(
                    "{" + placeholders[i] + "}",
                    String.valueOf(placeholders[i + 1])
            );
        }
        return ChatColor.translateAlternateColorCodes('&', formatted);
    }

    private String styleTemplate(String key, String value) {
        String section = key.contains(".") ? key.substring(0, key.indexOf('.')) : key;
        if (!PLAYER_FACING_SECTIONS.contains(section)) {
            return value;
        }

        if (key.equals("scoreboard.title")) {
            return "&6&l◆";
        }

        return value
                .replaceAll("(?i)&6&lpillars &8»\\s*", "&8› ")
                .replaceAll("(?i)/pillars\\b", "/p")
                .replaceAll("(?i)(?<!/)\\bpillars\\s*", "")
                .toLowerCase(Locale.ROOT);
    }

    private void saveLanguageFile(String languageCode) {
        String resourceName = resourceName(languageCode);
        File destination = new File(plugin.getDataFolder(), resourceName);
        if (!destination.exists()) {
            plugin.saveResource(resourceName, false);
        }
    }

    private YamlConfiguration loadLanguageFile(String languageCode) {
        return YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), resourceName(languageCode)));
    }

    private YamlConfiguration loadBundledLanguageFile(String languageCode) {
        String resourceName = resourceName(languageCode);
        InputStream stream = plugin.getResource(resourceName);
        if (stream == null) {
            plugin.getLogger().warning("Bundled translation file not found: " + resourceName);
            return new YamlConfiguration();
        }

        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not load bundled translation file " + resourceName + ": " + e.getMessage());
            return new YamlConfiguration();
        }
    }

    private String resourceName(String languageCode) {
        return "messages_" + languageCode + ".yml";
    }

    private void reportMissingKey(String key) {
        if (reportedMissingKeys.add(key)) {
            plugin.getLogger().warning("Missing translation key: " + key);
        }
    }
}
