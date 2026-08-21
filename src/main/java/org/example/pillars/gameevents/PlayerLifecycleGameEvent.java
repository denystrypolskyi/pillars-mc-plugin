package org.example.pillars.gameevents;

import org.bukkit.entity.Player;

public interface PlayerLifecycleGameEvent {
    boolean onPlayerEliminated(Player eliminated, Player killer);

    boolean onPlayerRemoved(Player player);
}
