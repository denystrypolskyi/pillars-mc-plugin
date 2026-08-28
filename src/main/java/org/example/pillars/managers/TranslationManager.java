package org.example.pillars.managers;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.example.pillars.ui.UiPalette;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class TranslationManager {
    private static final String RESOURCE_NAME = "messages_ru.yml";
    private static final Set<String> PLAYER_FACING_SECTIONS = Set.of(
            "plugin", "lobby-items", "admin-item", "arena-admin-item", "game-items",
            "scoreboard", "titles", "action-bar", "messages", "rarities", "units",
            "arena-view", "menus"
    );

    private final JavaPlugin plugin;
    private final YamlConfiguration selected;
    private final YamlConfiguration defaultSelected;
    private final Set<String> reportedMissingKeys = new HashSet<>();

    public TranslationManager(JavaPlugin plugin) {
        this.plugin = plugin;

        saveLanguageFile();
        this.selected = loadLanguageFile();
        this.defaultSelected = loadBundledLanguageFile();
    }

    public String displayName(String identifier) {
        if (identifier == null) return "";

        return identifier
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    public String text(String key, Object... placeholders) {
        String value = resolveString(key);
        if (value == null) {
            reportMissingKey(key);
            return key;
        }

        return format(decorateChatLine(key, styleTemplate(key, value), true), placeholders);
    }

    public List<String> list(String key, Object... placeholders) {
        List<String> values = resolveList(key);
        if (values == null) {
            reportMissingKey(key);
            return List.of(key);
        }

        List<String> formatted = new java.util.ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            String styled = styleTemplate(key, values.get(i));
            formatted.add(format(decorateChatLine(key, styled, i == 0), placeholders));
        }
        return List.copyOf(formatted);
    }

    public String randomText(String key, Object... placeholders) {
        List<String> values = resolveList(key);
        if (values == null || values.isEmpty()) {
            reportMissingKey(key);
            return key;
        }

        String value = values.get(ThreadLocalRandom.current().nextInt(values.size()));
        return format(decorateChatLine(key, styleTemplate(key, value), true), placeholders);
    }

    private List<String> resolveList(String key) {
        if (selected.isList(key)) return selected.getStringList(key);
        if (defaultSelected.isList(key)) return defaultSelected.getStringList(key);
        return null;
    }

    public String plural(String key, int amount, Object... placeholders) {
        int lastTwoDigits = Math.abs(amount) % 100;
        int lastDigit = Math.abs(amount) % 10;
        String form;
        if (lastTwoDigits >= 11 && lastTwoDigits <= 14) {
            form = "many";
        } else if (lastDigit == 1) {
            form = "one";
        } else if (lastDigit >= 2 && lastDigit <= 4) {
            form = "few";
        } else {
            form = "many";
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
            String scoreboardTitle = resolveString("brand.scoreboard-title");
            return scoreboardTitle == null ? UiPalette.TITLE + "CHRONICLE" : scoreboardTitle;
        }

        String configuredBrandName = resolveString("brand.name");
        String brandName = configuredBrandName == null ? "Chronicle" : configuredBrandName;
        String styled = value
                .replaceAll("(?i)^\\s*&6&lpillars &8»\\s*", "")
                .replaceAll("(?i)/pillars\\b", "/p")
                .toLowerCase(Locale.ROOT)
                .replace("pillars", brandName)
                .replace(brandName.toLowerCase(Locale.ROOT), brandName);

        return section.equals("messages")
                ? styled.replaceFirst("(?i)^\\s*&8›\\s*", "")
                : styled;
    }

    private String decorateChatLine(String key, String value, boolean firstLine) {
        if (!key.startsWith("messages.")) {
            return value;
        }

        String formatKey = firstLine
                ? "brand.message-format"
                : "brand.message-continuation-format";
        String template = resolveString(formatKey);
        if (template == null) {
            template = firstLine
                    ? "&6&l{name} &8» &r{message}"
                    : "&8  ↳ &r{message}";
        }

        String configuredBrandName = resolveString("brand.name");
        String brandName = configuredBrandName == null ? "Chronicle" : configuredBrandName;
        return template
                .replace("{name}", brandName)
                .replace("{message}", value);
    }

    private String resolveString(String key) {
        String value = selected.getString(key);
        if (value == null) value = defaultSelected.getString(key);
        return value;
    }

    private void saveLanguageFile() {
        File destination = new File(plugin.getDataFolder(), RESOURCE_NAME);
        if (!destination.exists()) {
            plugin.saveResource(RESOURCE_NAME, false);
        }
    }

    private YamlConfiguration loadLanguageFile() {
        return YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), RESOURCE_NAME));
    }

    private YamlConfiguration loadBundledLanguageFile() {
        InputStream stream = plugin.getResource(RESOURCE_NAME);
        if (stream == null) {
            plugin.getLogger().warning("Не найден встроенный файл переводов: " + RESOURCE_NAME);
            return new YamlConfiguration();
        }

        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось загрузить встроенный файл переводов " + RESOURCE_NAME + ": " + e.getMessage());
            return new YamlConfiguration();
        }
    }

    private void reportMissingKey(String key) {
        if (reportedMissingKeys.add(key)) {
            plugin.getLogger().warning("Отсутствует ключ перевода: " + key);
        }
    }
}
