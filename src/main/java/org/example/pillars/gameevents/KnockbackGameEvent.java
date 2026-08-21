package org.example.pillars.gameevents;

import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

public interface KnockbackGameEvent extends GameEvent {
    Vector modifyKnockback(Player victim, Player damager, Vector knockback);
}