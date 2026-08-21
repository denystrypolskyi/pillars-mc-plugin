package org.example.pillars.gameevents;

import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.example.pillars.GameSession;
import org.example.pillars.managers.HudManager;

public final class SuperSmashBrosEvent implements KnockbackGameEvent {
    private static final String ID = "super_smash_bros";

    private final GameSession session;
    private final HudManager hudManager;
    private final int durationSeconds;
    private final double knockbackMultiplier;

    private boolean active;

    public SuperSmashBrosEvent(
            GameSession session,
            HudManager hudManager,
            int durationSeconds,
            double knockbackMultiplier
    ) {
        this.session = session;
        this.hudManager = hudManager;
        this.durationSeconds = durationSeconds;
        this.knockbackMultiplier = knockbackMultiplier;
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

        active = true;
        hudManager.sendSuperSmashBrosStarted(
                session.getAllPlayerIds(),
                durationSeconds,
                knockbackMultiplier
        );
    }

    @Override
    public void stop() {
        if (!active) return;

        active = false;
        hudManager.sendSuperSmashBrosEnded(session.getAllPlayerIds());
    }

    @Override
    public Vector modifyKnockback(Player victim, Player damager, Vector knockback) {
        if (!active) return knockback;

        return knockback.clone().multiply(knockbackMultiplier);
    }
}
