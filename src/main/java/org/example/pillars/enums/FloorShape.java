package org.example.pillars.enums;

import java.util.Locale;

public enum FloorShape {
    SQUARE,
    SQUARE_RING,
    ISLANDS;

    public static FloorShape fromConfig(String value) {
        if (value == null) return SQUARE;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("HEXAGON")) return SQUARE;
        if (normalized.equals("RING")) return SQUARE_RING;
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return SQUARE;
        }
    }

    public FloorShape next() {
        FloorShape[] shapes = values();
        return shapes[(ordinal() + 1) % shapes.length];
    }

    public String configValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
