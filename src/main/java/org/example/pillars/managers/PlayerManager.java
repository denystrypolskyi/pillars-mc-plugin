package org.example.pillars.managers;

import org.bukkit.GameMode;
import org.bukkit.entity.Player;

public class PlayerManager {
    public void resetPlayerState(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getActivePotionEffects().forEach(effect ->
                player.removePotionEffect(effect.getType())
        );

        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);

        player.setHealth(player.getMaxHealth());
        player.setFoodLevel(20);
        player.setSaturation(20f);

        player.setExp(0);
        player.setLevel(0);

        player.setFireTicks(0);
    }
}
