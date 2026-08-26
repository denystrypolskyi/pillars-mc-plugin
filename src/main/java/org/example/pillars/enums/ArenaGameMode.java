package org.example.pillars.enums;

import java.util.Locale;

public enum ArenaGameMode {
    STANDARD,
    LUCKY_BLOCKS;

    public static ArenaGameMode fromConfig(String value) {
        if (value == null) return STANDARD;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return STANDARD;
        }
    }

    public ArenaGameMode next() {
        return this == STANDARD ? LUCKY_BLOCKS : STANDARD;
    }

    public String configValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
