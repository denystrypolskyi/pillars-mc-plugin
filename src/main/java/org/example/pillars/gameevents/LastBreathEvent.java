package org.example.pillars.gameevents;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.example.pillars.GameSession;
import org.example.pillars.enums.GameState;
import org.example.pillars.managers.HudManager;
import org.example.pillars.managers.SoundManager;

import java.util.UUID;

public final class LastBreathEvent implements GameEvent, DynamicGameEventStatus {
    private static final String ID = "last_breath";

    private final JavaPlugin plugin;
    private final GameSession session;
    private final HudManager hudManager;
    private final SoundManager soundManager;
    private final int effectDurationTicks;
    private final long effectPeriodTicks;
    private final int effectAmplifier;

    private BukkitTask effectTask;

    public LastBreathEvent(
            JavaPlugin plugin,
            GameSession session,
            HudManager hudManager,
            SoundManager soundManager,
            int effectDurationTicks,
            long effectPeriodTicks,
            int effectAmplifier
    ) {
        this.plugin = plugin;
        this.session = session;
        this.hudManager = hudManager;
        this.soundManager = soundManager;
        this.effectDurationTicks = effectDurationTicks;
        this.effectPeriodTicks = effectPeriodTicks;
        this.effectAmplifier = effectAmplifier;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public int getDurationSeconds() {
        return 0;
    }

    @Override
    public int getRemainingSeconds() {
        return -1;
    }

    @Override
    public void start() {
        startEffect();
    }

    @Override
    public void stop() {
        if (effectTask != null) {
            effectTask.cancel();
            effectTask = null;
        }

        for (UUID uuid : session.getActivePlayerIds()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.removePotionEffect(PotionEffectType.WITHER);
            }
        }
    }

    private void startEffect() {
        hudManager.sendLastBreathStarted(session.getAllPlayerIds());

        for (UUID uuid : session.getActivePlayerIds()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue;

            soundManager.playLastBreathStartSound(player);
        }

        effectTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (session.getState() != GameState.RUNNING || session.getActivePlayerIds().isEmpty()) {
                stop();
                return;
            }

            for (UUID uuid : session.getActivePlayerIds()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player == null) continue;

                player.addPotionEffect(new PotionEffect(
                        PotionEffectType.WITHER,
                        effectDurationTicks,
                        effectAmplifier,
                        false,
                        true
                ));
            }
        }, 0L, effectPeriodTicks);
    }
}
