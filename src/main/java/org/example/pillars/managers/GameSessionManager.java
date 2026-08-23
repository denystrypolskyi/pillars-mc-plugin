package org.example.pillars.managers;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.example.pillars.entities.Arena;
import org.example.pillars.GameSession;
import org.example.pillars.enums.ArenaResetResult;
import org.example.pillars.enums.GameState;
import org.example.pillars.gameevents.GameEventStatus;
import org.example.pillars.gameevents.NextGameEventStatus;

import java.util.HashMap;
import java.util.Map;

public class GameSessionManager {
    private final JavaPlugin plugin;
    private final HudManager hudManager;
    private final PlayerManager playerManager;
    private final StatsManager statsManager;
    private final SpawnManager spawnManager;
    private final SoundManager soundManager;
    private final TeleportManager teleportManager;
    private final ItemManager itemManager;
    private final ArenaManager arenaManager;

    private final Map<String, GameSession> sessions = new HashMap<>();

    public GameSessionManager(
            JavaPlugin plugin,
            HudManager hudManager,
            PlayerManager playerManager,
            StatsManager statsManager,
            SpawnManager spawnManager,
            SoundManager soundManager,
            TeleportManager teleportManager,
            ItemManager itemManager,
            ArenaManager arenaManager
    ) {
        this.plugin = plugin;
        this.hudManager = hudManager;
        this.playerManager = playerManager;
        this.statsManager = statsManager;
        this.spawnManager = spawnManager;
        this.soundManager = soundManager;
        this.teleportManager = teleportManager;
        this.itemManager = itemManager;
        this.arenaManager = arenaManager;
    }

    public GameSession getOrCreateSession(Arena arena) {
        return sessions.computeIfAbsent(
                arena.getWorldName(),
                k -> new GameSession(
                        plugin,
                        hudManager,
                        playerManager,
                        statsManager,
                        spawnManager,
                        soundManager,
                        teleportManager,
                        itemManager,
                        arenaManager,
                        arena
                )
        );
    }

    public GameSession getSessionByPlayer(Player player) {
        for (GameSession session : sessions.values()) {
            if (session.hasPlayer(player)) {
                return session;
            }
        }
        return null;
    }

    public void joinSession(Player player, Arena arena) {
        GameSession current = getSessionByPlayer(player);
        if (current != null) {
            leaveCurrentSession(player, current);
        }

        GameSession target = getOrCreateSession(arena);
        target.playerJoin(player);
    }

    public boolean spectateSession(Player player, Arena arena) {
        GameSession current = getSessionByPlayer(player);
        if (current != null) {
            leaveCurrentSession(player, current);
        }

        GameSession target = getSession(arena);
        if (target == null) {
            hudManager.sendArenaSpectateUnavailable(player);
            return false;
        }

        return target.adminSpectate(player);
    }

    public void setArenaJoiningOpen(Arena arena, boolean joiningOpen) {
        arenaManager.updateArenaJoiningOpen(arena, joiningOpen);
    }

    public void leaveSession(Player player) {
        GameSession session = getSessionByPlayer(player);
        if (session == null) {
            playerManager.removeLeaveArenaItem(player);
            hudManager.sendNotInGame(player);
            return;
        }

        boolean participant = session.isParticipant(player);
        session.playerLeave(player);
        if (participant) {
            hudManager.broadcastPlayerLeftArena(player, session.getArena().getDisplayName());
        } else {
            hudManager.sendLeftArena(player);
        }
    }

    private void leaveCurrentSession(Player player, GameSession session) {
        boolean participant = session.isParticipant(player);
        session.playerLeave(player);
        if (participant) {
            hudManager.broadcastPlayerLeftArena(player, session.getArena().getDisplayName());
        }
    }

    public GameSession getSession(Arena arena) {
        return sessions.get(arena.getWorldName());
    }

    public Arena findQuickJoinArena() {
        GameSession session = findJoinableSessionWithPlayers();
        if (session != null) {
            return session.getArena();
        }

        Arena smallest = null;

        for (Arena arena : arenaManager.getArenas()) {
            if (!isJoinableArena(arena)) {
                continue;
            }

            if (smallest == null || arena.getSpawnPoints().size() < smallest.getSpawnPoints().size()) {
                smallest = arena;
            }
        }

        return smallest;
    }

    private GameSession findJoinableSessionWithPlayers() {
        GameSession best = null;

        for (GameSession session : sessions.values()) {
            if (!isJoinableSessionWithPlayers(session)) {
                continue;
            }

            if (best == null || session.getActivePlayerIds().size() > best.getActivePlayerIds().size()) {
                best = session;
            }
        }

        return best;
    }

    private boolean isJoinableSessionWithPlayers(GameSession session) {
        if (!session.getArena().isJoiningOpen()) {
            return false;
        }

        if (session.getState() != GameState.WAITING && session.getState() != GameState.STARTING) {
            return false;
        }

        if (session.getActivePlayerIds().isEmpty()) {
            return false;
        }

        if (session.getArena().getSpawnPoints() == null) {
            return false;
        }

        return session.getActivePlayerIds().size() < session.getArena().getSpawnPoints().size();
    }

    private boolean isJoinableArena(Arena arena) {
        if (arena.getSpawnPoints() == null || arena.getSpawnPoints().isEmpty()) {
            return false;
        }

        GameSession session = getSession(arena);
        if (session == null) {
            return arena.isJoiningOpen();
        }

        return arena.isJoiningOpen()
                && session.getState() == GameState.WAITING
                && session.getActivePlayerIds().size() < arena.getSpawnPoints().size();
    }

    public void forceStartSession(Player player) {
        if (!player.hasPermission("pillars.forcestart")) {
            playerManager.removeForceStartItem(player);
            hudManager.sendNoPermission(player);
            return;
        }

        GameSession session = getSessionByPlayer(player);
        if (session == null) {
            playerManager.removeForceStartItem(player);
            hudManager.sendNotInGame(player);
            return;
        }

        if (session.getState() != GameState.WAITING) {
            playerManager.removeForceStartItem(player);
            hudManager.sendGameAlreadyRunning(player);
            return;
        }

        session.forceStart(player);
    }

    public void forceStartGameEvent(Player player, String eventId) {
        if (!player.hasPermission("pillars.admin")) {
            hudManager.sendNoPermission(player);
            return;
        }

        GameSession session = getSessionByPlayer(player);
        if (session == null) {
            hudManager.sendNotInGame(player);
            return;
        }

        if (session.getState() != GameState.RUNNING) {
            hudManager.sendGameEventUnavailable(player);
            return;
        }

        if (session.isFinalPhaseActive()) {
            hudManager.sendGameEventBlockedByFinalPhase(player);
            return;
        }

        if (!session.startGameEvent(eventId)) {
            hudManager.sendUnknownGameEvent(player, eventId);
        }
    }

    public void showNextGameEvent(Player player) {
        GameSession session = getSessionByPlayer(player);
        if (session == null) {
            hudManager.sendNotInGame(player);
            return;
        }
        if (session.getState() != GameState.RUNNING) {
            hudManager.sendGameEventUnavailable(player);
            return;
        }
        if (!session.areAutomaticGameEventsEnabled()) {
            hudManager.sendNextGameEventDisabled(player);
            return;
        }

        NextGameEventStatus nextEvent = session.getNextGameEventStatus();
        if (nextEvent != null) {
            hudManager.sendNextGameEvent(player, nextEvent);
            return;
        }

        GameEventStatus activeEvent = session.getActiveGameEventStatus();
        if (activeEvent != null) {
            hudManager.sendNextGameEventAfterCurrent(player);
        } else {
            hudManager.sendNextGameEventUnavailable(player);
        }
    }

    public boolean areRandomEventsEnabled() {
        return plugin.getConfig().getBoolean("settings.gameEvents.enabled", true);
    }

    public boolean toggleRandomEvents() {
        boolean enabled = !areRandomEventsEnabled();
        plugin.getConfig().set("settings.gameEvents.enabled", enabled);
        plugin.saveConfig();

        for (GameSession session : sessions.values()) {
            session.setAutomaticGameEventsEnabled(enabled);
        }
        return enabled;
    }

    public boolean isArenaResetting(Arena arena) {
        GameSession session = getSession(arena);
        return session != null && session.isResetInProgress();
    }

    public ArenaResetResult resetArenaManually(Player startedBy, Arena arena) {
        GameSession session = getOrCreateSession(arena);
        ArenaResetResult result = session.resetArenaManually(() -> {
            if (startedBy.isOnline()) {
                hudManager.sendManualArenaResetCompleted(startedBy, arena.getDisplayName());
            }
        });

        switch (result) {
            case STARTED -> hudManager.sendManualArenaResetStarted(startedBy, arena.getDisplayName());
            case ALREADY_RESETTING -> hudManager.sendManualArenaResetInProgress(startedBy, arena.getDisplayName());
            case LOBBY_UNAVAILABLE -> hudManager.sendManualArenaResetLobbyUnavailable(startedBy);
        }
        return result;
    }
}
