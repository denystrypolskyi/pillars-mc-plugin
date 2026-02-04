package org.example.pillars.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.example.pillars.GameSession;
import org.example.pillars.PillarsPlugin;
import org.example.pillars.enums.GameState;

import java.util.UUID;

public class SessionPlayerListener implements Listener {

    private final PillarsPlugin pillarsPlugin;

    public SessionPlayerListener(PillarsPlugin pillarsPlugin) {
        this.pillarsPlugin = pillarsPlugin;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {

        Player player = event.getPlayer();

        GameSession session =
                pillarsPlugin.getSessionManager().getSessionByPlayer(player);

        if (session != null) {
            session.playerDisconnect(player);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {

        if (!(event.getEntity() instanceof Player player)) return;

        GameSession session =
                pillarsPlugin.getSessionManager().getSessionByPlayer(player);

        if (session == null) return;
        if (session.getState() != GameState.RUNNING) return;

        double finalHealth = player.getHealth() - event.getFinalDamage();

        if (finalHealth <= 0) {

            event.setCancelled(true);

            Player killer = resolveKiller(session, player, event);

            session.playerDeath(player, killer);
        }
    }

    private Player resolveKiller(GameSession session,
                                 Player victim,
                                 EntityDamageEvent event) {

        if (event instanceof EntityDamageByEntityEvent e) {

            if (e.getDamager() instanceof Player damager &&
                    session.hasPlayer(damager)) {

                return damager;
            }
        }

        UUID lastDamagerUUID =
                session.getLastDamager(victim.getUniqueId());

        if (lastDamagerUUID == null) return null;

        Player lastDamager = Bukkit.getPlayer(lastDamagerUUID);

        if (lastDamager != null && session.hasPlayer(lastDamager)) {
            return lastDamager;
        }

        return null;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {

        Player player = event.getPlayer();

        GameSession session =
                pillarsPlugin.getSessionManager().getSessionByPlayer(player);

        if (session == null) return;

        if (session.getState() == GameState.RUNNING &&
                player.getLocation().getY() < 0) {

            Player killer = null;

            UUID lastDamager =
                    session.getLastDamager(player.getUniqueId());

            if (lastDamager != null) {
                killer = Bukkit.getPlayer(lastDamager);
            }

            session.playerDeath(player, killer);
            return;
        }

        if (!session.isPlayerFrozen(player)) return;

        Location frozen = session.getFrozenPlayerLocation(player);
        Location to = event.getTo();

        if (to == null) return;

        if (to.getBlockX() != frozen.getBlockX()
                || to.getBlockY() != frozen.getBlockY()
                || to.getBlockZ() != frozen.getBlockZ()) {

            player.teleport(frozen.clone());
        }
    }

    @EventHandler
    public void onPvP(EntityDamageByEntityEvent event) {

        if (!(event.getEntity() instanceof Player victim)) return;
        if (!(event.getDamager() instanceof Player damager)) return;

        GameSession session =
                pillarsPlugin.getSessionManager().getSessionByPlayer(victim);

        if (session == null) return;
        if (session.getState() != GameState.RUNNING) return;

        if (!session.hasPlayer(victim) || !session.hasPlayer(damager)) return;

        session.setLastDamager(
                victim.getUniqueId(),
                damager.getUniqueId()
        );
    }
}
