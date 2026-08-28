package org.example.pillars.gameevents;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.example.pillars.GameSession;
import org.example.pillars.managers.HudManager;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class CosmicDriftEvent implements GameEvent, PlayerLifecycleGameEvent {
    private static final String ID = "cosmic_drift";

    private final GameSession session;
    private final HudManager hudManager;
    private final int durationSeconds;
    private final AttributeModifier gravityModifier;
    private final AttributeModifier jumpModifier;
    private final AttributeModifier fallDamageModifier;
    private final Set<UUID> modifiedPlayers = new HashSet<>();

    private boolean active;

    public CosmicDriftEvent(
            JavaPlugin plugin,
            GameSession session,
            HudManager hudManager,
            int durationSeconds,
            double gravityMultiplier,
            double jumpStrengthMultiplier,
            double fallDamageMultiplier
    ) {
        this.session = session;
        this.hudManager = hudManager;
        this.durationSeconds = durationSeconds;
        this.gravityModifier = createMultiplierModifier(
                plugin,
                "cosmic_drift_gravity",
                gravityMultiplier
        );
        this.jumpModifier = createMultiplierModifier(
                plugin,
                "cosmic_drift_jump_strength",
                jumpStrengthMultiplier
        );
        this.fallDamageModifier = createMultiplierModifier(
                plugin,
                "cosmic_drift_fall_damage",
                fallDamageMultiplier
        );
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
        for (UUID uuid : session.getActivePlayerIds()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;

            applyModifier(player, Attribute.GRAVITY, gravityModifier);
            applyModifier(player, Attribute.JUMP_STRENGTH, jumpModifier);
            applyModifier(player, Attribute.FALL_DAMAGE_MULTIPLIER, fallDamageModifier);
            modifiedPlayers.add(uuid);
        }
        hudManager.sendCosmicDriftStarted(session.getAllPlayerIds(), durationSeconds);
    }

    @Override
    public boolean onPlayerEliminated(Player eliminated, Player killer) {
        removePlayerModifiers(eliminated);
        return false;
    }

    @Override
    public boolean onPlayerRemoved(Player player) {
        removePlayerModifiers(player);
        return false;
    }

    @Override
    public void stop() {
        if (!active) return;

        active = false;
        for (UUID uuid : new HashSet<>(modifiedPlayers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) continue;

            removePlayerModifiers(player);
        }
        modifiedPlayers.clear();
        hudManager.sendCosmicDriftEnded(session.getAllPlayerIds());
    }

    private void removePlayerModifiers(Player player) {
        if (!modifiedPlayers.remove(player.getUniqueId())) return;

        removeModifier(player, Attribute.GRAVITY, gravityModifier);
        removeModifier(player, Attribute.JUMP_STRENGTH, jumpModifier);
        removeModifier(player, Attribute.FALL_DAMAGE_MULTIPLIER, fallDamageModifier);
    }

    private AttributeModifier createMultiplierModifier(
            JavaPlugin plugin,
            String key,
            double multiplier
    ) {
        return new AttributeModifier(
                new NamespacedKey(plugin, key),
                multiplier - 1.0,
                AttributeModifier.Operation.ADD_SCALAR
        );
    }

    private void applyModifier(Player player, Attribute attribute, AttributeModifier modifier) {
        AttributeInstance attributeInstance = player.getAttribute(attribute);
        if (attributeInstance == null) return;

        attributeInstance.removeModifier(modifier.getKey());
        attributeInstance.addTransientModifier(modifier);
    }

    private void removeModifier(Player player, Attribute attribute, AttributeModifier modifier) {
        AttributeInstance attributeInstance = player.getAttribute(attribute);
        if (attributeInstance != null) {
            attributeInstance.removeModifier(modifier.getKey());
        }
    }
}
