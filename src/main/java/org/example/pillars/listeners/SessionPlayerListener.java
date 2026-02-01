package org.example.pillars.listeners;

import org.bukkit.*;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.example.pillars.PillarsPlugin;
import org.example.pillars.enums.GameState;
import org.example.pillars.entities.PlayerRecord;
import org.example.pillars.GameSession;

import java.util.UUID;

public class SessionPlayerListener implements Listener {

    private final PillarsPlugin pillarsPlugin;

    public SessionPlayerListener(final PillarsPlugin pillarsPlugin) {
        this.pillarsPlugin = pillarsPlugin;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        GameSession gameSession = pillarsPlugin.getSessionManager().getSessionByPlayer(player);
        if (gameSession != null) {
            gameSession.removePlayer(player);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        GameSession gameSession = pillarsPlugin.getSessionManager().getSessionByPlayer(player);
        if (gameSession == null) return;
        if (gameSession.getState() != GameState.RUNNING) return;

        double finalHealth = player.getHealth() - event.getFinalDamage();

        if (finalHealth <= 0) {
            event.setCancelled(true);
            handleDeath(player, event);
        }
    }

    private void handleDeath(Player dead, EntityDamageEvent event) {
        GameSession gameSession = pillarsPlugin.getSessionManager().getSessionByPlayer(dead);
        if (gameSession == null) return;
        if (!gameSession.hasPlayer(dead)) return;

        Player killer = null;

        if (event instanceof EntityDamageByEntityEvent e) {
            if (e.getDamager() instanceof Player p) {
                killer = p;
            }
        }

        if (killer == null) {
            UUID lastDamagerUUID = gameSession.getLastDamager(dead.getUniqueId());
            if (lastDamagerUUID != null) {
                Player p = Bukkit.getPlayer(lastDamagerUUID);
                if (p != null && gameSession.hasPlayer(p)) {
                    killer = p;
                }
            }
        }

        if (killer != null) {
            pillarsPlugin.getStatsManager().incrementKills(killer.getUniqueId());
            PlayerRecord killerStats = pillarsPlugin.getStatsManager().getStats(killer.getUniqueId());

            pillarsPlugin.getHudManager().updateScoreboard(
                    killer,
                    gameSession.getActivePlayers().size(),
                    gameSession.getArena().getSpawnPoints().size(),
                    gameSession.getArena().getDisplayName(),
                    killerStats.getKills(),
                    killerStats.getWins()
            );
        }


        Location deathLocation = dead.getLocation().clone().add(0, 1, 0);

        Firework firework = dead.getWorld().spawn(deathLocation, Firework.class);
        FireworkMeta meta = firework.getFireworkMeta();

        FireworkEffect effect = FireworkEffect.builder()
                .with(FireworkEffect.Type.BURST)
                .withColor(Color.RED)
                .withFade(Color.ORANGE)
                .withFlicker()
                .withTrail()
                .build();

        meta.addEffect(effect);
        meta.setPower(2);
        firework.setFireworkMeta(meta);

        gameSession.removeLastDamager(dead.getUniqueId());

        dead.setHealth(dead.getMaxHealth());

        gameSession.makeSpectator(dead);
    }


    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        GameSession gameSession = pillarsPlugin.getSessionManager().getSessionByPlayer(player);
        if (gameSession == null) return;

        if (gameSession.getState() == GameState.RUNNING && player.getLocation().getY() < 0) {
            handleDeath(player, null);
            return;
        }

        if (!gameSession.isFrozen(player)) return;

        Location frozen = gameSession.getFrozenLocation(player);
        Location to = event.getTo();
        if (to == null) return;

        if (to.getBlockX() != frozen.getBlockX()
                || to.getBlockY() != frozen.getBlockY()
                || to.getBlockZ() != frozen.getBlockZ()) {

            player.teleport(frozen.clone());
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player damager)) return;

        GameSession gameSession = pillarsPlugin.getSessionManager().getSessionByPlayer(victim);
        if (gameSession == null) return;
        if (!gameSession.hasPlayer(victim) || !gameSession.hasPlayer(damager)) return;
        if (gameSession.getState() != GameState.RUNNING) return;

        gameSession.setLastDamager(victim.getUniqueId(), damager.getUniqueId());
    }
}