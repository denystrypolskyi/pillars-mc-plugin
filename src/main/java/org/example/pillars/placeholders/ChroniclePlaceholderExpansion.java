package org.example.pillars.placeholders;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.example.pillars.GameSession;
import org.example.pillars.entities.PlayerStats;
import org.example.pillars.enums.GameState;
import org.example.pillars.managers.GameSessionManager;
import org.example.pillars.managers.StatsManager;
import org.example.pillars.managers.TranslationManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.EnumMap;
import java.util.Map;

public final class ChroniclePlaceholderExpansion extends PlaceholderExpansion {
    private final String version;
    private final StatsManager statsManager;
    private final GameSessionManager sessionManager;
    private final Map<GameState, String> stateNames;
    private final String lobbyState;
    private final String unknownState;

    public ChroniclePlaceholderExpansion(
            org.bukkit.plugin.Plugin plugin,
            TranslationManager translations,
            StatsManager statsManager,
            GameSessionManager sessionManager
    ) {
        this.version = plugin.getPluginMeta().getVersion();
        this.statsManager = statsManager;
        this.sessionManager = sessionManager;
        EnumMap<GameState, String> localizedStates = new EnumMap<>(GameState.class);
        localizedStates.put(GameState.WAITING, plain(translations, "scoreboard.state.waiting"));
        localizedStates.put(GameState.STARTING, plain(translations, "scoreboard.state.starting"));
        localizedStates.put(GameState.RUNNING, plain(translations, "scoreboard.state.running"));
        localizedStates.put(GameState.ENDING, plain(translations, "scoreboard.state.ending"));
        localizedStates.put(GameState.RESETTING, plain(translations, "scoreboard.state.resetting"));
        this.stateNames = Map.copyOf(localizedStates);
        this.lobbyState = plain(translations, "scoreboard.state.lobby");
        this.unknownState = plain(translations, "scoreboard.state.unknown");
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

        GameSession.PlaceholderView session = sessionManager.getPlaceholderView(offlinePlayer.getUniqueId());
        return switch (key) {
            case "in_arena" -> Boolean.toString(session != null);
            case "arena" -> session == null ? "—" : session.arenaName();
            case "players" -> session == null ? "0" : Integer.toString(session.participantCount());
            case "max_players" -> session == null ? "0" : Integer.toString(session.maxPlayers());
            case "status" -> session == null
                    ? lobbyState
                    : stateName(session.state());
            default -> null;
        };
    }

    private int winRate(PlayerStats stats) {
        return stats.getGamesPlayed() <= 0
                ? 0
                : (int) Math.round(stats.getWins() * 100.0 / stats.getGamesPlayed());
    }

    private String stateName(GameState state) {
        return state == null ? unknownState : stateNames.getOrDefault(state, unknownState);
    }

    private static String plain(TranslationManager translations, String key) {
        String value = ChatColor.stripColor(translations.text(key));
        return value == null ? "" : value;
    }
}
