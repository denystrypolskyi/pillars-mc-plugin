package org.example.pillars.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.example.pillars.GameSession;
import org.example.pillars.entities.Arena;
import org.example.pillars.enums.GameState;
import org.example.pillars.managers.ArenaManager;
import org.example.pillars.managers.GameSessionManager;
import org.example.pillars.managers.HudManager;

public class PillarsCommand implements CommandExecutor {

    private final ArenaManager arenaManager;
    private final GameSessionManager gameSessionManager;
    private final HudManager hudManager;

    public PillarsCommand(
            ArenaManager arenaManager,
            GameSessionManager gameSessionManager,
            HudManager hudManager
    ) {
        this.arenaManager = arenaManager;
        this.gameSessionManager = gameSessionManager;
        this.hudManager = hudManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!(sender instanceof Player player)) return true;
        if (args.length < 1) return true;

        switch (args[0].toLowerCase()) {

            case "join" -> {
                if (args.length < 2) return true;

                Arena arena = arenaManager.getArena(args[1]);
                if (arena == null) {
                    hudManager.sendArenaNotFound(player);
                    return true;
                }

                gameSessionManager.joinSession(player, arena);
            }

            case "leave" -> {
                GameSession session = gameSessionManager.getSessionByPlayer(player);

                if (session == null) {
                    hudManager.sendNotInGame(player);
                    return true;
                }

                gameSessionManager.leaveSession(player);
                hudManager.sendLeftArena(player);
            }

            case "forcestart" -> {
                GameSession session = gameSessionManager.getSessionByPlayer(player);

                if (session == null) {
                    hudManager.sendNotInGame(player);
                    return true;
                }

                if (session.getState() == GameState.RUNNING) {
                    hudManager.sendGameAlreadyRunning(player);
                    return true;
                }

                session.forceStart();
                hudManager.sendForceStartSuccess(player);
            }
        }

        return true;
    }
}
