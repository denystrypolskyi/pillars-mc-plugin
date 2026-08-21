package org.example.pillars.gameevents;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.example.pillars.GameSession;
import org.example.pillars.managers.HudManager;
import org.example.pillars.managers.ItemManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class HuntBeginsEvent implements GameEvent, TimedGameEventCompletion, PlayerLifecycleGameEvent {
    private static final String ID = "hunt_begins";

    private final GameSession session;
    private final HudManager hudManager;
    private final ItemManager itemManager;
    private final int durationSeconds;
    private final int rewardItemCount;

    private UUID targetId;
    private boolean targetWasGlowing;
    private boolean active;
    private boolean resolved;

    public HuntBeginsEvent(
            GameSession session,
            HudManager hudManager,
            ItemManager itemManager,
            int durationSeconds,
            int rewardItemCount
    ) {
        this.session = session;
        this.hudManager = hudManager;
        this.itemManager = itemManager;
        this.durationSeconds = durationSeconds;
        this.rewardItemCount = rewardItemCount;
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

        List<Player> players = getActivePlayers();
        if (players.isEmpty()) return;

        Player target = players.get(ThreadLocalRandom.current().nextInt(players.size()));
        targetId = target.getUniqueId();
        targetWasGlowing = target.isGlowing();
        target.setGlowing(true);
        active = true;

        hudManager.sendHuntBeginsStarted(
                session.getAllPlayerIds(),
                target.getName(),
                durationSeconds,
                rewardItemCount
        );
    }

    @Override
    public void complete() {
        if (!active || resolved || targetId == null) return;

        resolved = true;
        Player target = Bukkit.getPlayer(targetId);
        if (target != null
                && target.isOnline()
                && session.getActivePlayerIds().contains(targetId)) {
            giveReward(target);
            hudManager.sendHuntTargetSurvived(
                    session.getAllPlayerIds(),
                    target.getName(),
                    rewardItemCount
            );
        } else {
            hudManager.sendHuntEndedWithoutReward(session.getAllPlayerIds());
        }
    }

    @Override
    public boolean onPlayerEliminated(Player eliminated, Player killer) {
        if (!isTarget(eliminated)) return false;

        resolved = true;
        if (killer != null
                && !killer.getUniqueId().equals(targetId)
                && session.getActivePlayerIds().contains(killer.getUniqueId())) {
            giveReward(killer);
            hudManager.sendHuntTargetEliminated(
                    session.getAllPlayerIds(),
                    killer.getName(),
                    eliminated.getName(),
                    rewardItemCount
            );
        } else {
            hudManager.sendHuntEndedWithoutReward(session.getAllPlayerIds());
        }
        return true;
    }

    @Override
    public boolean onPlayerRemoved(Player player) {
        if (!isTarget(player)) return false;

        resolved = true;
        hudManager.sendHuntTargetLeft(session.getAllPlayerIds(), player.getName());
        return true;
    }

    @Override
    public void stop() {
        if (!active) return;

        Player target = Bukkit.getPlayer(targetId);
        if (target != null && target.isOnline()) {
            target.setGlowing(targetWasGlowing);
        }
        active = false;
    }

    private boolean isTarget(Player player) {
        return active && !resolved && targetId != null && targetId.equals(player.getUniqueId());
    }

    private List<Player> getActivePlayers() {
        List<Player> players = new ArrayList<>();
        for (UUID uuid : session.getActivePlayerIds()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                players.add(player);
            }
        }
        return players;
    }

    private void giveReward(Player player) {
        for (int i = 0; i < rewardItemCount; i++) {
            itemManager.giveRandomItem(player);
        }
    }
}
