package org.example.pillars.managers;

import org.bukkit.entity.Player;
import org.example.pillars.entities.PlayerStats;

import java.util.Collection;
import java.util.UUID;

/**
 * Persistence boundary for statistics changed by one game session.
 */
public final class SessionStatisticsService {
    private final StatsManager statsManager;

    public SessionStatisticsService(StatsManager statsManager) {
        this.statsManager = statsManager;
    }

    public PlayerStats get(Player player) {
        return statsManager.getStats(player.getUniqueId());
    }

    public PlayerStats recordKill(Player player) {
        statsManager.incrementKills(player.getUniqueId());
        return statsManager.getStats(player.getUniqueId());
    }

    public PlayerStats recordWin(Player player) {
        statsManager.incrementWins(player.getUniqueId());
        return statsManager.getStats(player.getUniqueId());
    }

    public void recordGamesPlayed(Collection<UUID> playerIds) {
        for (UUID playerId : playerIds) {
            statsManager.incrementGamesPlayed(playerId);
        }
    }
}
