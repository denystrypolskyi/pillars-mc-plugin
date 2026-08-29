package org.example.pillars.managers;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.example.pillars.enums.GameState;
import org.example.pillars.ui.UiPalette;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns built-in scoreboard creation, retained scoreboard state, and updates.
 */
public final class PlayerScoreboardService {
    private static final String[] UNIQUE_ENTRIES = {
            "§0§r", "§1§r", "§2§r", "§3§r", "§4§r", "§5§r", "§6§r", "§7§r",
            "§8§r", "§9§r", "§a§r", "§b§r", "§c§r", "§d§r", "§e§r", "§f§r"
    };

    private final TranslationManager translations;
    private final boolean externalScoreboard;
    private final Map<UUID, Scoreboard> playerScoreboards = new HashMap<>();
    private final Map<UUID, Map<String, Team>> playerTeams = new HashMap<>();
    private int blankCounter;

    public PlayerScoreboardService(TranslationManager translations, boolean externalScoreboard) {
        this.translations = translations;
        this.externalScoreboard = externalScoreboard;
    }

    public void updatePlayer(
            Player player,
            int players,
            int maxPlayers,
            GameState state,
            String arenaName,
            int kills,
            int wins,
            int gamesPlayed
    ) {
        if (externalScoreboard) return;
        initialize(player, false);

        Map<String, Team> teams = playerTeams.get(player.getUniqueId());
        if (teams == null || !teams.keySet().containsAll(List.of(
                "infoHeader", "playerLine", "onlineLine", "statusLine", "arenaLine"
        ))) return;

        teams.get("infoHeader").setPrefix(isolate(UiPalette.SECTION + text("scoreboard.layout.info-header")));
        teams.get("playerLine").setPrefix(isolate(styledText("scoreboard.layout.player-label")));
        teams.get("playerLine").setSuffix(isolate(UiPalette.VALUE + player.getName()));
        teams.get("onlineLine").setPrefix(isolate(styledText("scoreboard.layout.online-label")));
        teams.get("onlineLine").setSuffix(isolate(
                UiPalette.VALUE + players + UiPalette.SEPARATOR + "/" + UiPalette.VALUE + maxPlayers
        ));
        teams.get("statusLine").setPrefix(isolate(styledText("scoreboard.layout.status-label")));
        teams.get("statusLine").setSuffix(formatState(state));
        teams.get("arenaLine").setPrefix(isolate(styledText("scoreboard.layout.arena-label")));
        teams.get("arenaLine").setSuffix(isolate(UiPalette.VALUE + arenaName));
    }

    public void updateLobby(Player player, int kills, int wins, int gamesPlayed) {
        if (externalScoreboard) return;
        initialize(player, true);

        Map<String, Team> teams = playerTeams.get(player.getUniqueId());
        if (teams == null || !teams.keySet().containsAll(List.of(
                "infoHeader", "playerLine", "statHeader", "killsLine", "rateLine"
        ))) return;

        teams.get("infoHeader").setPrefix(isolate(UiPalette.SECTION + text("scoreboard.layout.info-header")));
        teams.get("playerLine").setPrefix(isolate(styledText("scoreboard.layout.player-label")));
        teams.get("playerLine").setSuffix(isolate(UiPalette.VALUE + player.getName()));
        teams.get("statHeader").setPrefix(isolate(UiPalette.SECTION + text("scoreboard.layout.stats-header")));
        teams.get("killsLine").setPrefix(isolate(styledText("scoreboard.layout.kills-label")));
        teams.get("killsLine").setSuffix(isolate(UiPalette.VALUE + kills));
        int winRate = gamesPlayed <= 0 ? 0 : (int) Math.round(wins * 100.0 / gamesPlayed);
        teams.get("rateLine").setPrefix(isolate(styledText("scoreboard.layout.rate-label")));
        teams.get("rateLine").setSuffix(isolate(
                UiPalette.VALUE + wins
                        + UiPalette.SEPARATOR + "/"
                        + UiPalette.TEXT + gamesPlayed
                        + UiPalette.SEPARATOR + " ("
                        + UiPalette.ACCENT + winRate + "%"
                        + UiPalette.SEPARATOR + ")"
        ));
    }

    public void updateArenaInfo(
            Set<UUID> playerIds,
            int activeCount,
            int maxPlayers,
            GameState state,
            String arenaName
    ) {
        if (externalScoreboard) return;
        for (UUID playerId : playerIds) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) continue;

            Map<String, Team> teams = playerTeams.get(playerId);
            if (teams == null) continue;

            Team online = teams.get("onlineLine");
            if (online != null) {
                online.setSuffix(isolate(
                        UiPalette.TEXT + activeCount + UiPalette.SEPARATOR + "/" + UiPalette.TEXT + maxPlayers
                ));
            }

            Team status = teams.get("statusLine");
            if (status != null) status.setSuffix(formatState(state));

            Team arena = teams.get("arenaLine");
            if (arena != null) arena.setSuffix(isolate(UiPalette.VALUE + arenaName));
        }
    }

    public void updateStats(Player player, int kills, int wins) {
        if (externalScoreboard) return;
        Map<String, Team> teams = playerTeams.get(player.getUniqueId());
        if (teams == null) return;

        Team killsTeam = teams.get("killsLine");
        if (killsTeam != null) killsTeam.setSuffix(isolate(UiPalette.VALUE + kills));

        Team winsTeam = teams.get("winsLine");
        if (winsTeam != null) winsTeam.setSuffix(isolate(UiPalette.VALUE + wins));
    }

    public void reset(Player player) {
        UUID playerId = player.getUniqueId();
        playerScoreboards.remove(playerId);
        playerTeams.remove(playerId);
        if (!externalScoreboard) {
            player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
        }
    }

    private void initialize(Player player, boolean lobby) {
        UUID playerId = player.getUniqueId();
        playerScoreboards.remove(playerId);
        playerTeams.remove(playerId);

        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective objective = board.registerNewObjective("pillarshud", "dummy", translations.text("scoreboard.title"));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        Map<String, Team> teams = new LinkedHashMap<>();
        String[] lineKeys = lobby
                ? new String[]{"infoHeader", "playerLine", "statHeader", "killsLine", "rateLine"}
                : new String[]{"infoHeader", "playerLine", "onlineLine", "statusLine", "arenaLine"};
        int score = lineKeys.length + 2;

        objective.getScore(nextBlank()).setScore(score--);
        for (String key : lineKeys) {
            Team team = board.registerNewTeam("hud_" + key);
            String entry = UNIQUE_ENTRIES[teams.size() % UNIQUE_ENTRIES.length];
            team.addEntry(entry);

            if (key.equals("statHeader")) {
                objective.getScore(nextBlank()).setScore(score--);
            }

            objective.getScore(entry).setScore(score--);
            teams.put(key, team);
        }
        objective.getScore(nextBlank()).setScore(score);

        playerScoreboards.put(playerId, board);
        playerTeams.put(playerId, teams);
        player.setScoreboard(board);
    }

    private String nextBlank() {
        return UNIQUE_ENTRIES[blankCounter++ % UNIQUE_ENTRIES.length] + " ";
    }

    private String formatState(GameState state) {
        if (state == null) return isolate(UiPalette.VALUE + text("scoreboard.state.unknown"));

        return switch (state) {
            case WAITING -> isolate(UiPalette.VALUE + text("scoreboard.state.waiting"));
            case STARTING -> isolate(UiPalette.VALUE + text("scoreboard.state.starting"));
            case RUNNING -> isolate(UiPalette.VALUE + text("scoreboard.state.running"));
            case ENDING -> isolate(UiPalette.VALUE + text("scoreboard.state.ending"));
            case RESETTING -> isolate(UiPalette.VALUE + text("scoreboard.state.resetting"));
        };
    }

    private String isolate(String value) {
        return "§r" + value + "§r";
    }

    private String text(String key) {
        String stripped = ChatColor.stripColor(translations.text(key));
        return stripped == null ? "" : stripped;
    }

    private String styledText(String key) {
        return translations.text(key);
    }
}
