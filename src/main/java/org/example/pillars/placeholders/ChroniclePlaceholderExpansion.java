package org.example.pillars.placeholders;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.example.pillars.GameSession;
import org.example.pillars.entities.PlayerStats;
import org.example.pillars.enums.GameState;
import org.example.pillars.managers.GameSessionManager;
import org.example.pillars.managers.StatsManager;
import org.example.pillars.managers.TranslationManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public final class ChroniclePlaceholderExpansion extends PlaceholderExpansion {
    private final String version;
    private final TranslationManager translations;
    private final StatsManager statsManager;
    private final GameSessionManager sessionManager;

    public ChroniclePlaceholderExpansion(
            org.bukkit.plugin.Plugin plugin,
            TranslationManager translations,
            StatsManager statsManager,
            GameSessionManager sessionManager
    ) {
        this.version = plugin.getPluginMeta().getVersion();
        this.translations = translations;
        this.statsManager = statsManager;
        this.sessionManager = sessionManager;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "chronicle";
    }

    @Override
    public @NotNull String getAuthor() {
        return "ChronicleMC";
    }

    @Override
    public @NotNull String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer offlinePlayer, @NotNull String identifier) {
        if (offlinePlayer == null) return "";

        PlayerStats stats = statsManager.getStats(offlinePlayer.getUniqueId());
        String key = identifier.toLowerCase(Locale.ROOT);
        switch (key) {
            case "kills":
                return Integer.toString(stats.getKills());
            case "wins":
                return Integer.toString(stats.getWins());
            case "games":
                return Integer.toString(stats.getGamesPlayed());
            case "winrate":
                return Integer.toString(winRate(stats));
            case "winrate_display":
                return stats.getWins() + "/" + stats.getGamesPlayed() + " (" + winRate(stats) + "%)";
            default:
                break;
        }

        Player player = offlinePlayer.getPlayer();
        GameSession session = player == null ? null : sessionManager.getSessionByPlayer(player);
        return switch (key) {
            case "in_arena" -> Boolean.toString(session != null);
            case "arena" -> session == null ? "—" : session.getArena().getDisplayName();
            case "players" -> session == null ? "0" : Integer.toString(session.getParticipantCount());
            case "max_players" -> session == null || session.getArena().getSpawnPoints() == null
                    ? "0"
                    : Integer.toString(session.getArena().getSpawnPoints().size());
            case "status" -> session == null
                    ? plain("scoreboard.state.lobby")
                    : stateName(session.getState());
            default -> null;
        };
    }

    private int winRate(PlayerStats stats) {
        return stats.getGamesPlayed() <= 0
                ? 0
                : (int) Math.round(stats.getWins() * 100.0 / stats.getGamesPlayed());
    }

    private String stateName(GameState state) {
        if (state == null) return plain("scoreboard.state.unknown");
        return switch (state) {
            case WAITING -> plain("scoreboard.state.waiting");
            case STARTING -> plain("scoreboard.state.starting");
            case RUNNING -> plain("scoreboard.state.running");
            case ENDING -> plain("scoreboard.state.ending");
            case RESETTING -> plain("scoreboard.state.resetting");
        };
    }

    private String plain(String key) {
        String value = ChatColor.stripColor(translations.text(key));
        return value == null ? "" : value;
    }
}
