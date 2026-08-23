package org.example.pillars.gameevents;

import org.bukkit.entity.Player;

public interface DirectHitGameEvent {
    void onDirectHit(Player attacker, Player victim);
}