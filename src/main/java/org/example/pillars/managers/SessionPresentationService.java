package org.example.pillars.managers;

import org.bukkit.entity.Player;
import org.example.pillars.entities.Arena;
import org.example.pillars.entities.PlayerStats;
import org.example.pillars.enums.ArenaGameMode;
import org.example.pillars.enums.EliminationCause;
import org.example.pillars.enums.GameState;
import org.example.pillars.enums.ItemDeliveryMode;
import org.example.pillars.gameevents.GameEventStatus;

import java.util.Set;
import java.util.UUID;

/**
 * Session-facing presentation boundary. It contains no lifecycle decisions or persistence.
 */
public final class SessionPresentationService {
    private final HudManager hud;
    private final SoundManager sounds;

    public SessionPresentationService(HudManager hud, SoundManager sounds) {
        this.hud = hud;
        this.sounds = sounds;
    }

    public void updateArena(Set<UUID> players, int participantCount, int capacity, GameState state, String arenaName) {
        hud.updateArenaInfoForAllPlayers(players, participantCount, capacity, state, arenaName);
    }

    public void updatePlayer(Player player, int participantCount, int capacity, GameState state,
                             String arenaName, PlayerStats stats) {
        hud.updatePlayerScoreboard(player, participantCount, capacity, state, arenaName,
                stats.getKills(), stats.getWins(), stats.getGamesPlayed());
    }

    public void updateStats(Player player, PlayerStats stats) {
        hud.updatePlayerStats(player, stats.getKills(), stats.getWins());
    }

    public void joined(Player player, Arena arena, int currentPlayers) {
        hud.broadcastPlayerJoinedArena(player, arena.getDisplayName(), currentPlayers, arena.getSpawnPoints().size());
    }

    public void arenaConfigurationError(Player player) { hud.sendArenaConfigurationError(player); }
    public void arenaClosed(Player player, String arenaName) { hud.sendArenaClosed(player, arenaName); }
    public void noSpawnAvailable(Player player) { hud.sendNoSpawnAvailable(player); }
    public void gameAlreadyStarted(Player player) { hud.sendGameAlreadyStartedTitle(player); }
    public void arenaSpectateUnavailable(Player player) { hud.sendArenaSpectateUnavailable(player); }
    public void cannotSpectateOwnGame(Player player) { hud.sendCannotSpectateOwnGame(player); }
    public void adminSpectatorJoined(Player player, String arenaName) {
        hud.sendAdminSpectatorJoined(player, arenaName);
    }

    public void forceStarted(Player player, Arena arena) {
        hud.broadcastForceStartedArena(player, arena.getDisplayName());
    }

    public void eliminated(Player victim, Player killer, Arena arena, EliminationCause cause) {
        hud.broadcastElimination(victim, killer, arena.getDisplayName(), cause);
    }

    public void lost(Player player) {
        sounds.playLoseSound(player);
    }

    public void spectator(Player player) {
        hud.sendSpectatorTitle(player);
    }

    public void winner(Player winner, Arena arena, Set<UUID> audience) {
        hud.broadcastWinner(winner.getName(), arena.getDisplayName());
        forOnline(audience, player -> {
            sounds.playWinSound(player);
            hud.sendWinnerTitle(player, winner.getName());
        });
    }

    public void noWinner(Arena arena, Set<UUID> audience) {
        hud.broadcastNoWinner(arena.getDisplayName());
        forOnline(audience, player -> {
            sounds.playLoseSound(player);
            hud.sendNoWinnerTitle(player);
        });
    }

    public void returnCountdown(Player player, int seconds) {
        hud.sendReturnToLobbyTitle(player, seconds);
    }

    public void countdown(Player player, int seconds) {
        hud.sendCountdownTitle(player, seconds);
        sounds.playCountdownTickSound(player);
    }

    public void gameStarted(Player player) {
        hud.sendGameStartTitle(player);
        sounds.playGameStartSound(player);
    }

    public void gameStarted(Arena arena) {
        hud.broadcastGameStarted(arena.getDisplayName());
    }

    public void itemGiven(Player player) {
        sounds.playItemGivenSound(player);
    }

    public void itemCooldown(Player player, int seconds, ArenaGameMode gameMode, ItemDeliveryMode deliveryMode,
                             long borderSeconds, GameEventStatus eventStatus) {
        hud.sendItemCooldown(player, seconds, gameMode, deliveryMode, borderSeconds, eventStatus);
    }

    public void waiting(Player player, int currentPlayers, int minimumPlayers) {
        hud.sendWaitingForPlayers(player, currentPlayers, minimumPlayers);
    }

    public void notEnoughPlayers(Player player) {
        hud.sendNotEnoughPlayersTitle(player);
    }

    public void resetScoreboard(Player player) {
        hud.resetScoreboard(player);
    }

    private void forOnline(Set<UUID> playerIds, java.util.function.Consumer<Player> action) {
        for (UUID playerId : playerIds) {
            Player player = org.bukkit.Bukkit.getPlayer(playerId);
            if (player != null) action.accept(player);
        }
    }
}
