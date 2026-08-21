package org.example.pillars.gameevents;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.example.pillars.GameSession;
import org.example.pillars.managers.HudManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class HotPotatoEvent implements
        GameEvent,
        TimedGameEventCompletion,
        PlayerLifecycleGameEvent,
        DirectHitGameEvent {
    private static final String ID = "hot_potato";
    private static final long DISPLAY_UPDATE_TICKS = 2L;

    private final JavaPlugin plugin;
    private final GameSession session;
    private final HudManager hudManager;
    private final int durationSeconds;
    private final long passCooldownTicks;
    private final double explosionDamage;
    private final double horizontalKnockback;
    private final double verticalKnockback;
    private final int urgentSeconds;
    private final Map<UUID, Boolean> originalGlowStates = new HashMap<>();

    private UUID holderId;
    private ItemDisplay potatoDisplay;
    private BukkitTask displayTask;
    private long endTick;
    private long passCooldownEndTick;
    private long lastBeepTick = -1000L;
    private boolean active;
    private boolean resolved;

    public HotPotatoEvent(
            JavaPlugin plugin,
            GameSession session,
            HudManager hudManager,
            int durationSeconds,
            long passCooldownTicks,
            double explosionDamage,
            double horizontalKnockback,
            double verticalKnockback,
            int urgentSeconds
    ) {
        this.plugin = plugin;
        this.session = session;
        this.hudManager = hudManager;
        this.durationSeconds = durationSeconds;
        this.passCooldownTicks = passCooldownTicks;
        this.explosionDamage = explosionDamage;
        this.horizontalKnockback = horizontalKnockback;
        this.verticalKnockback = verticalKnockback;
        this.urgentSeconds = urgentSeconds;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public int getDurationSeconds() {
        return durationSeconds;
    }

    @Override
    public void start() {
        if (active) return;

        List<Player> players = getActivePlayers(null);
        if (players.isEmpty()) return;

        active = true;
        endTick = (long) Bukkit.getCurrentTick() + durationSeconds * 20L;
        Player holder = players.get(ThreadLocalRandom.current().nextInt(players.size()));
        setHolder(holder);
        spawnPotatoDisplay(holder);
        hudManager.sendHotPotatoStarted(session.getAllPlayerIds(), holder.getName(), durationSeconds);

        displayTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateDisplay, 0L, DISPLAY_UPDATE_TICKS);
    }

    @Override
    public void onDirectHit(Player attacker, Player victim) {
        if (!active
                || resolved
                || holderId == null
                || !holderId.equals(attacker.getUniqueId())
                || Bukkit.getCurrentTick() < passCooldownEndTick
                || !session.getActivePlayerIds().contains(victim.getUniqueId())) {
            return;
        }

        setHolder(victim);
        passCooldownEndTick = (long) Bukkit.getCurrentTick() + passCooldownTicks;
        hudManager.sendHotPotatoPassed(session.getAllPlayerIds(), attacker.getName(), victim.getName());
    }

    @Override
    public void complete() {
        if (!active || resolved || holderId == null) return;

        resolved = true;
        Player holder = Bukkit.getPlayer(holderId);
        if (holder == null || !holder.isOnline()) {
            hudManager.sendHotPotatoEndedWithoutHolder(session.getAllPlayerIds());
            return;
        }

        Location explosionLocation = holder.getLocation().clone().add(0.0, 0.8, 0.0);
        holder.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, explosionLocation, 1);
        holder.getWorld().spawnParticle(Particle.FLAME, explosionLocation, 35, 0.7, 0.8, 0.7, 0.1);
        holder.getWorld().playSound(explosionLocation, Sound.ENTITY_GENERIC_EXPLODE, 1.2F, 0.9F);
        hudManager.sendHotPotatoExploded(session.getAllPlayerIds(), holder.getName());

        if (explosionDamage > 0.0) {
            holder.damage(explosionDamage);
        }
        if (session.getActivePlayerIds().contains(holder.getUniqueId())) {
            applyExplosionKnockback(holder);
        }
    }

    @Override
    public boolean onPlayerEliminated(Player eliminated, Player killer) {
        return replaceUnavailableHolder(eliminated);
    }

    @Override
    public boolean onPlayerRemoved(Player player) {
        return replaceUnavailableHolder(player);
    }

    @Override
    public void stop() {
        if (!active) return;

        active = false;
        if (displayTask != null) {
            displayTask.cancel();
            displayTask = null;
        }
        if (potatoDisplay != null && potatoDisplay.isValid()) {
            potatoDisplay.remove();
        }
        potatoDisplay = null;

        for (Map.Entry<UUID, Boolean> entry : originalGlowStates.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                player.setGlowing(entry.getValue());
            }
        }
        originalGlowStates.clear();
    }

    private boolean replaceUnavailableHolder(Player unavailablePlayer) {
        if (!active
                || resolved
                || holderId == null
                || !holderId.equals(unavailablePlayer.getUniqueId())) {
            return false;
        }

        List<Player> replacements = getActivePlayers(unavailablePlayer.getUniqueId());
        if (replacements.isEmpty()) {
            resolved = true;
            hudManager.sendHotPotatoEndedWithoutHolder(session.getAllPlayerIds());
            return true;
        }

        Player replacement = replacements.get(ThreadLocalRandom.current().nextInt(replacements.size()));
        setHolder(replacement);
        passCooldownEndTick = (long) Bukkit.getCurrentTick() + passCooldownTicks;
        hudManager.sendHotPotatoReassigned(session.getAllPlayerIds(), replacement.getName());
        return false;
    }

    private void setHolder(Player newHolder) {
        restoreCurrentHolderGlow();
        holderId = newHolder.getUniqueId();
        originalGlowStates.putIfAbsent(holderId, newHolder.isGlowing());
        newHolder.setGlowing(true);

        if (potatoDisplay != null && potatoDisplay.isValid()) {
            potatoDisplay.teleport(getDisplayLocation(newHolder));
        }
    }

    private void restoreCurrentHolderGlow() {
        if (holderId == null) return;

        Player currentHolder = Bukkit.getPlayer(holderId);
        Boolean originalGlow = originalGlowStates.get(holderId);
        if (currentHolder != null && currentHolder.isOnline() && originalGlow != null) {
            currentHolder.setGlowing(originalGlow);
        }
    }

    private void spawnPotatoDisplay(Player holder) {
        potatoDisplay = holder.getWorld().spawn(getDisplayLocation(holder), ItemDisplay.class, display -> {
            display.setItemStack(new ItemStack(Material.BAKED_POTATO));
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);
            display.setBillboard(Display.Billboard.CENTER);
            display.setGlowing(true);
            display.setGlowColorOverride(Color.RED);
            display.setBrightness(new Display.Brightness(15, 15));
        });
    }

    private void updateDisplay() {
        if (!active || resolved || holderId == null) return;

        Player holder = Bukkit.getPlayer(holderId);
        if (holder == null || !holder.isOnline()) return;

        if (potatoDisplay != null && potatoDisplay.isValid()) {
            potatoDisplay.teleport(getDisplayLocation(holder));
        }

        long remainingTicks = Math.max(0L, endTick - Bukkit.getCurrentTick());
        int remainingSeconds = (int) ((remainingTicks + 19L) / 20L);
        long beepInterval = remainingSeconds <= urgentSeconds ? 5L : 20L;
        if ((long) Bukkit.getCurrentTick() - lastBeepTick >= beepInterval) {
            float pitch = remainingSeconds <= urgentSeconds ? 1.8F : 1.15F;
            holder.playSound(holder.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.9F, pitch);
            lastBeepTick = Bukkit.getCurrentTick();
        }

        if (remainingSeconds <= urgentSeconds) {
            holder.getWorld().spawnParticle(
                    Particle.FLAME,
                    getDisplayLocation(holder),
                    5,
                    0.2,
                    0.25,
                    0.2,
                    0.02
            );
        }
    }

    private void applyExplosionKnockback(Player holder) {
        Vector direction = new Vector(
                ThreadLocalRandom.current().nextDouble(-1.0, 1.0),
                0.0,
                ThreadLocalRandom.current().nextDouble(-1.0, 1.0)
        );
        if (direction.lengthSquared() < 0.01) {
            direction.setX(1.0);
        }
        direction.normalize().multiply(horizontalKnockback);
        direction.setY(verticalKnockback);
        holder.setVelocity(holder.getVelocity().add(direction));
    }

    private Location getDisplayLocation(Player holder) {
        return holder.getLocation().clone().add(0.0, 2.4, 0.0);
    }

    private List<Player> getActivePlayers(UUID excludedPlayerId) {
        List<Player> players = new ArrayList<>();
        for (UUID uuid : session.getActivePlayerIds()) {
            if (uuid.equals(excludedPlayerId)) continue;

            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                players.add(player);
            }
        }
        return players;
    }
}
