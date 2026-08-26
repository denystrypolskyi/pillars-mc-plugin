package org.example.pillars.enums;

import java.util.Locale;

public enum ItemDeliveryMode {
    SINGLE,
    HOTBAR;

    public static ItemDeliveryMode fromConfig(String value) {
        if (value == null) return SINGLE;
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return SINGLE;
        }
    }

    public ItemDeliveryMode next() {
        return this == SINGLE ? HOTBAR : SINGLE;
    }

    public String configValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
