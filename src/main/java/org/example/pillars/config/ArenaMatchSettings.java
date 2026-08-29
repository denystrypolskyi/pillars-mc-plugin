package org.example.pillars.config;

import org.example.pillars.entities.Arena;
import org.example.pillars.enums.ArenaGameMode;
import org.example.pillars.enums.ItemDeliveryMode;

/**
 * Immutable arena settings captured at the transition to RUNNING.
 * Administrative edits apply to the following match, never midway through this one.
 */
public record ArenaMatchSettings(
        ArenaGameMode gameMode,
        ItemDeliveryMode itemDeliveryMode,
        int itemIntervalSeconds,
        int borderShrinkSeconds
) {
    public static ArenaMatchSettings capture(Arena arena) {
        return new ArenaMatchSettings(
                arena.getGameMode(),
                arena.getItemDeliveryMode(),
                Math.max(1, arena.getItemCooldownSeconds()),
                Math.max(1, arena.getBorderShrinkSeconds())
        );
    }
}
