package org.example.pillars.managers;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;
import org.example.pillars.entities.Arena;
import org.example.pillars.enums.ArenaGameMode;
import org.example.pillars.enums.GameState;
import org.example.pillars.enums.EliminationCause;
import org.example.pillars.enums.ItemDeliveryMode;
import org.example.pillars.gameevents.GameEventStatus;
import org.example.pillars.gameevents.NextGameEventStatus;
import org.example.pillars.ui.UiPalette;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class HudManager {
    private final TranslationManager translations;
    private final boolean externalScoreboard;
    private final Map<UUID, Scoreboard> playerScoreboards = new HashMap<>();
    private final Map<UUID, Map<String, Team>> playerTeams = new HashMap<>();

    private static final String[] UNIQUE_ENTRIES = {
            "§0§r", "§1§r", "§2§r", "§3§r", "§4§r", "§5§r", "§6§r", "§7§r",
            "§8§r", "§9§r", "§a§r", "§b§r", "§c§r", "§d§r", "§e§r", "§f§r"
    };

    private int blankCounter = 0;

    private static final int FADE_IN = 5;
    private static final int FADE_OUT = 5;

    private static final int SHORT_STAY = 25;
    private static final int MEDIUM_STAY = 30;
    private static final int LONG_STAY = 50;

    public HudManager(TranslationManager translations, boolean externalScoreboard) {
        this.translations = translations;
        this.externalScoreboard = externalScoreboard;
    }

    public TranslationManager getTranslations() {
        return translations;
    }

    private void initializeScoreboard(@NotNull Player player, boolean lobby) {
        UUID uuid = player.getUniqueId();
        playerScoreboards.remove(uuid);
        playerTeams.remove(uuid);

        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective obj = board.registerNewObjective("pillarshud", "dummy", translations.text("scoreboard.title"));
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        Map<String, Team> teams = new LinkedHashMap<>();

        String[] lineKeys = lobby
                ? new String[]{"infoHeader", "playerLine", "statHeader", "killsLine", "rateLine"}
                : new String[]{"infoHeader", "playerLine", "onlineLine", "statusLine", "arenaLine"};

        int score = lineKeys.length + 2;

        obj.getScore(nextBlank()).setScore(score--);

        for (String key : lineKeys) {
            Team team = board.registerNewTeam("hud_" + key);
            String entry = UNIQUE_ENTRIES[teams.size() % UNIQUE_ENTRIES.length];
            team.addEntry(entry);

            if (key.equals("statHeader")) {
                obj.getScore(nextBlank()).setScore(score--);
            }

            obj.getScore(entry).setScore(score--);
            teams.put(key, team);
        }

        obj.getScore(nextBlank()).setScore(score--);

        playerScoreboards.put(uuid, board);
        playerTeams.put(uuid, teams);

        player.setScoreboard(board);
    }

    private String nextBlank() {
        return UNIQUE_ENTRIES[blankCounter++ % UNIQUE_ENTRIES.length] + " ";
    }

    public void updatePlayerScoreboard(Player player, int players, int maxPlayers, GameState state,
                                       String arenaName, int kills, int wins, int gamesPlayed) {
        if (externalScoreboard) return;
        initializeScoreboard(player, false);

        UUID uuid = player.getUniqueId();
        Map<String, Team> teams = playerTeams.get(uuid);
        if (teams == null || !teams.keySet().containsAll(List.of(
                "infoHeader", "playerLine", "onlineLine", "statusLine", "arenaLine"
        ))) return;

        teams.get("infoHeader").setPrefix(isolate(UiPalette.SECTION + scoreboardText("scoreboard.info-header")));

        teams.get("playerLine").setPrefix(isolate(UiPalette.LABEL + scoreboardText("scoreboard.player-label")));
        teams.get("playerLine").setSuffix(isolate(UiPalette.VALUE + player.getName()));

        teams.get("onlineLine").setPrefix(isolate(UiPalette.LABEL + scoreboardText("scoreboard.online-label")));
        teams.get("onlineLine").setSuffix(isolate(
                UiPalette.VALUE + players + UiPalette.SEPARATOR + "/" + UiPalette.VALUE + maxPlayers
        ));

        teams.get("statusLine").setPrefix(isolate(UiPalette.LABEL + scoreboardText("scoreboard.status-label")));
        teams.get("statusLine").setSuffix(formatState(state));

        teams.get("arenaLine").setPrefix(isolate(UiPalette.LABEL + scoreboardText("scoreboard.arena-label")));
        teams.get("arenaLine").setSuffix(isolate(UiPalette.VALUE + arenaName));

    }

    public void updateLobbyScoreboard(Player player, int kills, int wins, int gamesPlayed) {
        if (externalScoreboard) return;
        initializeScoreboard(player, true);
        Map<String, Team> teams = playerTeams.get(player.getUniqueId());
        if (teams == null || !teams.keySet().containsAll(List.of(
                "infoHeader", "playerLine", "statHeader", "killsLine", "rateLine"
        ))) return;
        teams.get("infoHeader").setPrefix(isolate(UiPalette.SECTION + scoreboardText("scoreboard.info-header")));
        teams.get("playerLine").setPrefix(isolate(UiPalette.LABEL + scoreboardText("scoreboard.player-label")));
        teams.get("playerLine").setSuffix(isolate(UiPalette.VALUE + player.getName()));
        teams.get("statHeader").setPrefix(isolate(UiPalette.SECTION + scoreboardText("scoreboard.stats-header")));
        teams.get("killsLine").setPrefix(isolate(UiPalette.LABEL + scoreboardText("scoreboard.kills-label")));
        teams.get("killsLine").setSuffix(isolate(UiPalette.VALUE + kills));
        int winRate = gamesPlayed <= 0 ? 0 : (int) Math.round(wins * 100.0 / gamesPlayed);
        teams.get("rateLine").setPrefix(isolate(UiPalette.LABEL + scoreboardText("scoreboard.rate-label")));
        teams.get("rateLine").setSuffix(isolate(
                UiPalette.VALUE + wins
                        + UiPalette.SEPARATOR + "/"
                        + UiPalette.TEXT + gamesPlayed
                        + UiPalette.SEPARATOR + " ("
                        + UiPalette.ACCENT + winRate + "%"
                        + UiPalette.SEPARATOR + ")"
        ));
    }

    public void updateArenaInfoForAllPlayers(Set<UUID> playersSet, int activeCount, int max, GameState state, String arenaName) {
        if (externalScoreboard) return;
        for (UUID uuid : playersSet) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) continue;

            Map<String, Team> teams = playerTeams.get(uuid);
            if (teams == null) continue;

            Team online = teams.get("onlineLine");
            if (online != null) {
                online.setSuffix(isolate(
                        UiPalette.TEXT + activeCount + UiPalette.SEPARATOR + "/" + UiPalette.TEXT + max
                ));
            }

            Team status = teams.get("statusLine");
            if (status != null) {
                status.setSuffix(formatState(state));
            }

            Team arena = teams.get("arenaLine");
            if (arena != null) {
                arena.setSuffix(isolate(UiPalette.VALUE + arenaName));
            }
        }
    }

    public void updatePlayerStats(Player player, int kills, int wins) {
        if (externalScoreboard) return;
        UUID uuid = player.getUniqueId();
        Map<String, Team> teams = playerTeams.get(uuid);
        if (teams == null) return;

        Team killsTeam = teams.get("killsLine");
        if (killsTeam != null) {
            killsTeam.setSuffix(isolate(UiPalette.VALUE + kills));
        }

        Team winsTeam = teams.get("winsLine");
        if (winsTeam != null) {
            winsTeam.setSuffix(isolate(UiPalette.VALUE + wins));
        }
    }

    public void cleanupPlayerScoreboard(Player player) {
        UUID uuid = player.getUniqueId();
        playerScoreboards.remove(uuid);
        playerTeams.remove(uuid);
        if (!externalScoreboard) {
            player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
        }
    }

    public void resetScoreboard(Player player) {
        cleanupPlayerScoreboard(player);
    }

    public void sendReturnToLobbyTitle(Player player, int seconds) {
        player.sendTitle(
                translations.text("titles.return-to-lobby.title"),
                translations.text("titles.return-to-lobby.subtitle", "seconds", seconds),
                0,
                MEDIUM_STAY,
                0
        );
    }

    public void sendWinnerTitle(Player player, String winnerName) {
        player.sendTitle(
                translations.text("titles.winner.title"),
                translations.text("titles.winner.subtitle", "winner", winnerName),
                FADE_IN,
                LONG_STAY,
                FADE_OUT
        );
    }

    public void sendCountdownTitle(Player player, int secondsLeft) {
        player.sendTitle(
                translations.text("titles.countdown.title", "seconds", secondsLeft),
                translations.text("titles.countdown.subtitle"),
                FADE_IN,
                MEDIUM_STAY,
                FADE_OUT
        );
    }

    public void sendGameStartTitle(Player player) {
        player.sendTitle(
                translations.text("titles.game-start.title"),
                translations.text("titles.game-start.subtitle"),
                FADE_IN,
                LONG_STAY,
                FADE_OUT
        );
    }

    public void sendGameEventStartedTitle(Set<UUID> playerIds, String eventId) {
        String eventName = translations.text("game-events." + eventId);

        for (UUID uuid : playerIds) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;

            player.sendTitle(
                    translations.text("titles.game-event-start.title"),
                    translations.text("titles.game-event-start.subtitle", "event", eventName),
                    FADE_IN,
                    MEDIUM_STAY,
                    FADE_OUT
            );
        }
    }

    public void sendSuperSmashBrosStarted(Set<UUID> playerIds, int durationSeconds, double multiplier) {
        for (UUID uuid : playerIds) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;

            player.sendMessage(translations.text(
                    "messages.super-smash-bros-start",
                    "multiplier", multiplier,
                    "seconds", durationSeconds
            ));
        }
    }

    public void sendSuperSmashBrosEnded(Set<UUID> playerIds) {
        for (UUID uuid : playerIds) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;

            player.sendMessage(translations.text("messages.super-smash-bros-end"));
        }
    }

    public void sendCosmicDriftStarted(Set<UUID> playerIds, int durationSeconds) {
        for (UUID uuid : playerIds) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;

            player.sendMessage(translations.text(
                    "messages.cosmic-drift-start",
                    "seconds", durationSeconds
            ));
        }
    }

    public void sendCosmicDriftEnded(Set<UUID> playerIds) {
        for (UUID uuid : playerIds) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;

            player.sendMessage(translations.text("messages.cosmic-drift-end"));
        }
    }

    public void sendMeteorShowerStarted(Set<UUID> playerIds, int durationSeconds) {
        for (UUID uuid : playerIds) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;

            player.sendMessage(translations.text(
                    "messages.meteor-shower-start",
                    "seconds", durationSeconds
            ));
        }
    }

    public void sendMeteorShowerEnded(Set<UUID> playerIds) {
        for (UUID uuid : playerIds) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;

            player.sendMessage(translations.text("messages.meteor-shower-end"));
        }
    }

    public void sendEarthquakeStarted(Set<UUID> playerIds, int durationSeconds) {
        for (UUID uuid : playerIds) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;

            player.sendMessage(translations.text(
                    "messages.earthquake-start",
                    "seconds", durationSeconds
            ));
        }
    }

    public void sendEarthquakeEnded(Set<UUID> playerIds) {
        for (UUID uuid : playerIds) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;

            player.sendMessage(translations.text("messages.earthquake-end"));
        }
    }

    public void sendHuntBeginsStarted(
            Set<UUID> playerIds,
            String targetName,
            int durationSeconds,
            int rewardItemCount
    ) {
        sendMessageToPlayers(
                playerIds,
                "messages.hunt-begins-start",
                "target", targetName,
                "seconds", durationSeconds,
                "reward", rewardItemCount
        );
    }

    public void sendHuntTargetEliminated(
            Set<UUID> playerIds,
            String killerName,
            String targetName,
            int rewardItemCount
    ) {
        sendMessageToPlayers(
                playerIds,
                "messages.hunt-target-eliminated",
                "killer", killerName,
                "target", targetName,
                "reward", rewardItemCount
        );
    }

    public void sendHuntTargetSurvived(Set<UUID> playerIds, String targetName, int rewardItemCount) {
        sendMessageToPlayers(
                playerIds,
                "messages.hunt-target-survived",
                "target", targetName,
                "reward", rewardItemCount
        );
    }

    public void sendHuntTargetLeft(Set<UUID> playerIds, String targetName) {
        sendMessageToPlayers(playerIds, "messages.hunt-target-left", "target", targetName);
    }

    public void sendHuntEndedWithoutReward(Set<UUID> playerIds) {
        sendMessageToPlayers(playerIds, "messages.hunt-ended-without-reward");
    }

    public void sendHotPotatoStarted(Set<UUID> playerIds, String holderName, int durationSeconds) {
        sendMessageToPlayers(
                playerIds,
                "messages.hot-potato-start",
                "holder", holderName,
                "seconds", durationSeconds
        );
    }

    public void sendHotPotatoPassed(Set<UUID> playerIds, String oldHolderName, String newHolderName) {
        sendMessageToPlayers(
                playerIds,
                "messages.hot-potato-passed",
                "old_holder", oldHolderName,
                "new_holder", newHolderName
        );
    }

    public void sendHotPotatoReassigned(Set<UUID> playerIds, String holderName) {
        sendMessageToPlayers(playerIds, "messages.hot-potato-reassigned", "holder", holderName);
    }

    public void sendHotPotatoExploded(Set<UUID> playerIds, String holderName) {
        sendMessageToPlayers(playerIds, "messages.hot-potato-exploded", "holder", holderName);
    }

    public void sendHotPotatoEndedWithoutHolder(Set<UUID> playerIds) {
        sendMessageToPlayers(playerIds, "messages.hot-potato-no-holder");
    }

    private void sendMessageToPlayers(Set<UUID> playerIds, String key, Object... placeholders) {
        for (UUID uuid : playerIds) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;

            player.sendMessage(translations.text(key, placeholders));
        }
    }

    public void sendArenaResettingTitle(Player player) {
        player.sendTitle(
                translations.text("titles.arena-resetting.title"),
                translations.text("titles.arena-resetting.subtitle"),
                FADE_IN,
                MEDIUM_STAY,
                FADE_OUT
        );
    }

    public void sendGameAlreadyStartedTitle(Player player) {
        player.sendTitle(
                translations.text("titles.game-already-started.title"),
                translations.text("titles.game-already-started.subtitle"),
                FADE_IN,
                SHORT_STAY,
                FADE_OUT
        );
    }

    public void sendItemCooldown(
            Player player,
            int secondsLeft,
            ArenaGameMode gameMode,
            ItemDeliveryMode deliveryMode,
            long secondsUntilNextZoneDecrease,
            GameEventStatus gameEventStatus
    ) {
        String zoneTimer = secondsUntilNextZoneDecrease > 0
                ? translations.text("action-bar.zone-decrease", "seconds", secondsUntilNextZoneDecrease)
                : translations.text("action-bar.zone-final");

        String eventStatus;
        if (gameEventStatus == null) {
            eventStatus = translations.text("game-events.none");
        } else {
            String eventName = translations.text("game-events." + gameEventStatus.id());
            String templateKey = gameEventStatus.remainingSeconds() >= 0
                    ? "action-bar.timed-game-event-value"
                    : "action-bar.indefinite-game-event-value";
            eventStatus = translations.text(
                    templateKey,
                    "event", eventName,
                    "seconds", gameEventStatus.remainingSeconds()
            );
        }

        String deliveryTimer = translations.text(
                gameMode == ArenaGameMode.LUCKY_BLOCKS
                        ? "action-bar.next-lucky-block"
                        : deliveryMode == ItemDeliveryMode.HOTBAR
                                ? "action-bar.hotbar-refresh"
                                : "action-bar.next-item",
                "seconds", secondsLeft
        );

        player.sendActionBar(translations.text(
                "action-bar.match-status",
                "delivery", deliveryTimer,
                "zone", zoneTimer,
                "event", eventStatus
        ));
    }

    public void sendNotEnoughPlayersTitle(Player player) {
        player.sendTitle(
                translations.text("titles.not-enough-players.title"),
                translations.text("titles.not-enough-players.subtitle"),
                FADE_IN,
                MEDIUM_STAY,
                FADE_OUT
        );
    }

    public void sendWaitingForPlayers(Player player, int currentPlayers, int minPlayers) {
        int needed = Math.max(0, minPlayers - currentPlayers);
        String status = needed == 0
                ? translations.text("action-bar.waiting-ready")
                : translations.text(
                        "action-bar.waiting-more",
                        "count", needed,
                        "players", waitingPlayerWord(needed)
                );

        player.sendActionBar(status);
    }

    private String waitingPlayerWord(int amount) {
        if (translations.getLanguage().equals("ru")) {
            int lastTwoDigits = Math.abs(amount) % 100;
            int lastDigit = Math.abs(amount) % 10;
            if (lastTwoDigits >= 11 && lastTwoDigits <= 14) {
                return "игроков";
            }
            if (lastDigit == 1) {
                return "игрока";
            }
            if (lastDigit >= 2 && lastDigit <= 4) {
                return "игрока";
            }
            return "игроков";
        }

        return amount == 1 ? "player" : "players";
    }

    public void sendSpectatorTitle(Player player) {
        player.sendTitle(
                translations.text("titles.spectator.title"),
                translations.text("titles.spectator.subtitle"),
                FADE_IN,
                MEDIUM_STAY,
                FADE_OUT
        );
    }

    public void sendArenaNotFound(Player player, String arenaName) {
        sendTranslatedList(player, "messages.arena-not-found", "arena", arenaName);
    }

    public void sendLeftArena(Player player) {
        player.sendMessage(translations.text("messages.left-arena"));
    }

    public void sendNotInGame(Player player) {
        player.sendMessage(translations.text("messages.not-in-game"));
    }

    public void sendGameAlreadyRunning(Player player) {
        player.sendMessage(translations.text("messages.game-already-running"));
    }

    public void sendArenaClosed(Player player, String arenaName) {
        player.sendMessage(translations.text("messages.arena-closed", "arena", arenaName));
    }

    public void broadcastPlayerJoinedArena(Player player, String arenaName, int currentPlayers, int maxPlayers) {
        Bukkit.broadcastMessage(translations.text(
                "messages.player-joined",
                "player", player.getName(),
                "arena", arenaName,
                "current", currentPlayers,
                "maximum", maxPlayers
        ));
    }

    public void broadcastPlayerLeftArena(Player player, String arenaName) {
        Bukkit.broadcastMessage(translations.text(
                "messages.player-left",
                "player", player.getName(),
                "arena", arenaName
        ));
    }

    public void broadcastForceStartedArena(Player player, String arenaName) {
        Bukkit.broadcastMessage(translations.text(
                "messages.force-started",
                "arena", arenaName,
                "player", player.getName()
        ));
    }

    public void broadcastGameStarted(String arenaName) {
        Bukkit.broadcastMessage(translations.text("messages.game-started", "arena", arenaName));
    }

    public void broadcastWinner(String winnerName, String arenaName) {
        Bukkit.broadcastMessage(translations.text(
                "messages.winner",
                "winner", winnerName,
                "arena", arenaName
        ));
    }

    public void broadcastNoWinner(String arenaName) {
        Bukkit.broadcastMessage(translations.text("messages.no-winner", "arena", arenaName));
    }

    public void broadcastElimination(Player victim, Player killer, String arenaName, EliminationCause cause) {
        boolean creditedKill = killer != null && !killer.getUniqueId().equals(victim.getUniqueId());
        String key = "messages.eliminations." + eliminationPool(cause, creditedKill);

        Bukkit.broadcastMessage(translations.randomText(
                key,
                "victim", victim.getName(),
                "killer", creditedKill ? killer.getName() : "",
                "arena", arenaName
        ));
    }

    private String eliminationPool(EliminationCause cause, boolean creditedKill) {
        if (creditedKill) {
            return switch (cause) {
                case VOID -> "void-kills";
                case MELEE -> "melee-kills";
                case PROJECTILE -> "projectile-kills";
                case EXPLOSION -> "explosion-kills";
                case FIRE -> "fire-kills";
                case LAVA -> "lava-kills";
                case FALL -> "fall-kills";
                case OTHER -> "generic-kills";
            };
        }

        return switch (cause) {
            case VOID -> "void-deaths";
            case PROJECTILE -> "projectile-deaths";
            case EXPLOSION -> "explosion-deaths";
            case FIRE -> "fire-deaths";
            case LAVA -> "lava-deaths";
            case FALL -> "fall-deaths";
            case MELEE, OTHER -> "generic-deaths";
        };
    }

    public void sendNoWinnerTitle(Player player) {
        player.sendTitle(
                translations.text("titles.no-winner.title"),
                translations.text("titles.no-winner.subtitle"),
                FADE_IN,
                LONG_STAY,
                FADE_OUT
        );
    }

    public void sendNoPermission(Player player) {
        player.sendMessage(translations.text("messages.no-permission"));
    }

    public void sendUnknownCommand(Player player, String command) {
        sendTranslatedList(player, "messages.unknown-command", "command", command);
    }

    public void sendCommandSyntax(Player player, String syntax) {
        sendTranslatedList(player, "messages.command-syntax", "syntax", syntax);
    }

    public void sendGameEventUsage(Player player) {
        sendTranslatedList(
                player,
                player.hasPermission("pillars.admin")
                        ? "messages.game-event-usage-admin"
                        : "messages.game-event-usage"
        );
    }

    public void sendGameEventList(Player player) {
        sendTranslatedList(player, "messages.game-event-list");
    }

    public void sendGameEventUnavailable(Player player) {
        player.sendMessage(translations.text("messages.game-event-unavailable"));
    }

    public void sendGameEventBlockedByFinalPhase(Player player) {
        player.sendMessage(translations.text("messages.game-event-final-phase"));
    }

    public void sendUnknownGameEvent(Player player, String eventId) {
        sendTranslatedList(player, "messages.game-event-unknown", "event", eventId);
    }

    public void sendNextGameEvent(Player player, NextGameEventStatus status) {
        player.sendMessage(translations.text(
                "messages.game-event-next",
                "event", translations.text("game-events." + status.id()),
                "seconds", status.secondsUntilStart()
        ));
    }

    public void sendNextGameEventAfterCurrent(Player player) {
        player.sendMessage(translations.text("messages.game-event-next-after-current"));
    }

    public void sendNextGameEventDisabled(Player player) {
        player.sendMessage(translations.text("messages.game-event-next-disabled"));
    }

    public void sendNextGameEventUnavailable(Player player) {
        player.sendMessage(translations.text("messages.game-event-next-unavailable"));
    }

    public void sendRandomEventsToggled(Player player, boolean enabled) {
        player.sendMessage(translations.text(
                enabled ? "messages.random-events-enabled" : "messages.random-events-disabled"
        ));
    }

    public void sendManualArenaResetStarted(Player player, String arenaName) {
        player.sendMessage(translations.text("messages.manual-arena-reset-started", "arena", arenaName));
    }

    public void sendManualArenaResetCompleted(Player player, String arenaName) {
        player.sendMessage(translations.text("messages.manual-arena-reset-completed", "arena", arenaName));
    }

    public void sendManualArenaResetInProgress(Player player, String arenaName) {
        player.sendMessage(translations.text("messages.manual-arena-reset-in-progress", "arena", arenaName));
    }

    public void sendManualArenaResetLobbyUnavailable(Player player) {
        player.sendMessage(translations.text("messages.manual-arena-reset-lobby-unavailable"));
    }

    public void sendJoinUsage(Player player) {
        sendTranslatedList(player, "messages.join-usage");
    }

    public void sendNoJoinableSession(Player player) {
        player.sendMessage(translations.text("messages.no-joinable-arena"));
    }

    public void sendItemAddUsage(Player player) {
        sendTranslatedList(player, "messages.item-add-usage");
    }

    public void sendItemRemoveUsage(Player player) {
        sendTranslatedList(player, "messages.item-remove-usage");
    }

    private void sendTranslatedList(Player player, String key, Object... placeholders) {
        for (String line : translations.list(key, placeholders)) {
            player.sendMessage(line);
        }
    }

    public void sendHoldItemToConfigure(Player player) {
        player.sendMessage(translations.text("messages.hold-item"));
    }

    public void sendUnknownMaterial(Player player, String material) {
        sendTranslatedList(player, "messages.unknown-material", "material", material);
    }

    public void sendUnknownRarity(Player player, String rarity) {
        sendTranslatedList(player, "messages.unknown-rarity", "rarity", rarity);
    }

    public void sendInvalidWeight(Player player, String weight) {
        sendTranslatedList(player, "messages.invalid-weight", "weight", weight);
    }

    public void sendItemConfigured(Player player, Material material, String rarity, int weight) {
        player.sendMessage(translations.text(
                "messages.item-configured",
                "material", displayMaterial(material),
                "rarity", translations.text("rarities." + rarity.toLowerCase())
        ));
        player.sendMessage(translations.text("messages.internal-weight", "weight", weight));
    }

    public void sendItemRemoved(Player player, Material material, String rarity) {
        player.sendMessage(translations.text(
                "messages.item-removed",
                "material", displayMaterial(material),
                "rarity", translations.text("rarities." + rarity.toLowerCase())
        ));
    }

    public void sendAdminConfigUpdated(Player player, int commonPercent, int rarePercent, int legendaryPercent) {
        player.sendMessage(translations.text(
                "messages.rarity-updated",
                "common_name", translations.text("rarities.common"),
                "common", commonPercent,
                "rare_name", translations.text("rarities.rare"),
                "rare", rarePercent,
                "legendary_name", translations.text("rarities.legendary"),
                "legendary", legendaryPercent
        ));
    }

    private String displayMaterial(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    public void sendArenaSettingsUpdated(Player player, Arena arena) {
        player.sendMessage(translations.text(
                "messages.arena-settings-updated",
                "arena", arena.getDisplayName(),
                  "minimum", arena.getMinPlayers(),
                  "players", translations.plural("units.player-at", arena.getMinPlayers()),
                  "cooldown", arena.getItemCooldownSeconds(),
                  "border", arena.getBorderShrinkSeconds()
          ));
    }

    public void sendArenaGameModeUpdated(Player player, Arena arena) {
        player.sendMessage(translations.text(
                "messages.arena-game-mode-updated",
                "arena", arena.getDisplayName(),
                "mode", translations.text(
                        "menus.arena-settings.game-modes." + arena.getGameMode().configValue()
                )
        ));
    }

    public void broadcastArenaJoiningChanged(Player player, Arena arena) {
        String key = arena.isJoiningOpen()
                ? "messages.arena-joining-opened"
                : "messages.arena-joining-closed";
        Bukkit.broadcastMessage(translations.text(
                key,
                "arena", arena.getDisplayName(),
                "player", player.getName()
        ));
    }

    public void sendArenaSpectateUnavailable(Player player) {
        player.sendMessage(translations.text("messages.spectate-unavailable"));
    }

    public void sendCannotSpectateOwnGame(Player player) {
        player.sendMessage(translations.text("messages.cannot-spectate-own-game"));
    }

    public void sendAdminSpectatorJoined(Player player, String arenaName) {
        player.sendMessage(translations.text("messages.spectator-joined", "arena", arenaName));
    }

    public void sendNoSpawnAvailable(Player player) {
        player.sendMessage(translations.text("messages.no-spawn-available"));
    }

    public void sendArenaConfigurationError(Player player) {
        player.sendMessage(translations.text("messages.arena-configuration-error"));
    }

    public void sendLobbyWorldMissing(Player player, String worldName) {
        player.sendMessage(translations.text("messages.lobby-world-missing", "world", worldName));
    }

    public void sendLastBreathStarted(Set<UUID> playerIds) {
        for (UUID uuid : playerIds) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;

            player.sendMessage(translations.text("messages.last-breath-start"));
        }
    }

    private String formatState(GameState state) {
        if (state == null) {
            return isolate(UiPalette.VALUE + scoreboardText("scoreboard.state.unknown"));
        }

        return switch (state) {
            case WAITING -> isolate(UiPalette.VALUE + scoreboardText("scoreboard.state.waiting"));
            case STARTING, COUNTDOWN -> isolate(UiPalette.VALUE + scoreboardText("scoreboard.state.starting"));
            case RUNNING -> isolate(UiPalette.VALUE + scoreboardText("scoreboard.state.running"));
            case ENDING -> isolate(UiPalette.VALUE + scoreboardText("scoreboard.state.ending"));
            case RESETTING -> isolate(UiPalette.VALUE + scoreboardText("scoreboard.state.resetting"));
        };
    }

    private String isolate(String value) {
        return "§r" + value + "§r";
    }

    private String scoreboardText(String key) {
        String stripped = ChatColor.stripColor(translations.text(key));
        return stripped == null ? "" : stripped;
    }
}
