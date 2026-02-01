package org.example.pillars.managers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public class TeleportManager {
    public void teleport(Player player, Location spawn) {
        player.teleport(spawn.clone().add(0.5, 1, 0.5));
    }

    public void teleportToLobby(Player player) {
        player.teleport(Bukkit.getWorld("world").getSpawnLocation());
    }

}
