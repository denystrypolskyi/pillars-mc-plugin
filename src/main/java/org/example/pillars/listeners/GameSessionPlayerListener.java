package org.example.pillars.listeners;

import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.example.pillars.GameSession;
import org.example.pillars.PillarsPlugin;
import org.example.pillars.enums.EliminationCause;
import org.example.pillars.enums.GameState;
import org.example.pillars.managers.GameSessionManager;

import java.util.UUID;

public class GameSessionPlayerListener implements Listener {

    private final GameSessionManager gameSessionManager;

    public GameSessionPlayerListener(GameSessionManager gameSessionManager) {
        this.gameSessionManager = gameSessionManager;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {

        Player player = event.getPlayer();

        GameSession session =
                gameSessionManager.getSessionByPlayer(player);

        if (session != null) {
            session.playerDisconnect(player);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {

        if (!(event.getEntity() instanceof Player player)) return;

        GameSession session =
                gameSessionManager.getSessionByPlayer(player);

        if (session == null) return;
        if (session.getState() != GameState.RUNNING) return;

        double effectiveHealth = player.getHealth() + player.getAbsorptionAmount();
        double finalHealth = effectiveHealth - event.getFinalDamage();

        if (finalHealth <= 0) {

            event.setCancelled(true);

            Player killer = resolveKiller(session, player, event);

            session.playerDeath(player, killer, resolveEliminationCause(event));
        }
    }

    private EliminationCause resolveEliminationCause(EntityDamageEvent event) {
        return switch (event.getCause()) {
            case VOID -> EliminationCause.VOID;
            case PROJECTILE -> EliminationCause.PROJECTILE;
            case BLOCK_EXPLOSION, ENTITY_EXPLOSION -> EliminationCause.EXPLOSION;
            case FIRE, FIRE_TICK, CAMPFIRE -> EliminationCause.FIRE;
            case LAVA, HOT_FLOOR -> EliminationCause.LAVA;
            case FALL -> EliminationCause.FALL;
            case ENTITY_ATTACK, ENTITY_SWEEP_ATTACK -> EliminationCause.MELEE;
            default -> EliminationCause.OTHER;
        };
    }

    private Player resolveKiller(GameSession session,
                                 Player victim,
                                 EntityDamageEvent event) {

        if (event instanceof EntityDamageByEntityEvent e) {
            Player damager = resolveDamagingPlayer(e.getDamager());
            if (damager != null && session.hasPlayer(damager)) {

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
                gameSessionManager.getSessionByPlayer(player);

        if (session == null) return;

        if (session.getState() == GameState.RUNNING &&
                player.getLocation().getY() < session.getEliminationY()) {

            Player killer = null;

            UUID lastDamager =
                    session.getLastDamager(player.getUniqueId());

            if (lastDamager != null) {
                killer = Bukkit.getPlayer(lastDamager);
            }

            session.playerDeath(player, killer, EliminationCause.VOID);
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

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPvP(EntityDamageByEntityEvent event) {

        if (!(event.getEntity() instanceof Player victim)) return;

        Player damager = resolveDamagingPlayer(event.getDamager());
        if (damager == null) return;

        GameSession session =
                gameSessionManager.getSessionByPlayer(victim);

        if (session == null) return;
        if (session.getState() != GameState.RUNNING) return;

        if (!session.getActivePlayerIds().contains(victim.getUniqueId())
                || !session.getActivePlayerIds().contains(damager.getUniqueId())) return;

        session.setLastDamager(
                victim.getUniqueId(),
                damager.getUniqueId()
        );

        if (event.getDamager() instanceof Player directAttacker) {
            session.handleDirectPlayerHit(directAttacker, victim);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onKnockback(EntityPushedByEntityAttackEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        Player damager = resolveDamagingPlayer(event.getPushedBy());
        if (damager == null) return;

        GameSession session = gameSessionManager.getSessionByPlayer(victim);
        if (session == null || session.getState() != GameState.RUNNING) return;

        if (!session.getActivePlayerIds().contains(victim.getUniqueId())
                || !session.getActivePlayerIds().contains(damager.getUniqueId())) return;

        event.setKnockback(session.modifyKnockback(
                victim,
                damager,
                event.getKnockback()
        ));
    }

    private Player resolveDamagingPlayer(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }
}
