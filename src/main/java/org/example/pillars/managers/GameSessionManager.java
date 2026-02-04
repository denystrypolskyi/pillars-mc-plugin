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
            current.playerLeave(player);
        }

        GameSession target = getOrCreateSession(arena);
        target.playerJoin(player);
    }

    public void leaveSession(Player player) {

        GameSession session = getSessionByPlayer(player);

        if (session != null) {
            session.playerLeave(player);
        }
    }
}
