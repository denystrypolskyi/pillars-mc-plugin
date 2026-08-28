package org.example.pillars.managers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public class TeleportManager {
    public void teleportToSpawnPoint(Player player, Location spawn) {
        player.teleport(spawn);
    }

    public boolean teleportToLobby(Player player, String worldName) {
        org.bukkit.World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return false;
        }

        player.teleport(world.getSpawnLocation());
        return true;
    }

}
