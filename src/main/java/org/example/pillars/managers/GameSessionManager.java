package org.example.pillars.managers;

import org.bukkit.entity.Player;
import org.example.pillars.PillarsPlugin;
import org.example.pillars.entities.Arena;
import org.example.pillars.GameSession;

import java.util.HashMap;
import java.util.Map;

public class GameSessionManager {

    private final PillarsPlugin pillarsPlugin;
    private final Map<String, GameSession> sessions = new HashMap<>();

    public GameSessionManager(PillarsPlugin pillarsPlugin) {
        this.pillarsPlugin = pillarsPlugin;
    }

    public GameSession getOrCreateSession(Arena arena) {
        return sessions.computeIfAbsent(
                arena.getWorldName(),
                k -> new GameSession(arena, pillarsPlugin)
        );
    }

    public GameSession getSessionByPlayer(Player player) {
        for (GameSession gameSession : sessions.values()) {
            if (gameSession.hasPlayer(player)) {
                return gameSession;
            }
        }
        return null;
    }

    public void joinSession(Player player, Arena arena) {
        GameSession current = getSessionByPlayer(player);
        if (current != null) {
            current.removePlayer(player);
        }

        GameSession target = getOrCreateSession(arena);
        target.joinPlayer(player);
    }

    public void leaveSession(Player player) {
        GameSession gameSession = getSessionByPlayer(player);
        if (gameSession != null) {
            gameSession.removePlayer(player);
        }
    }

}


