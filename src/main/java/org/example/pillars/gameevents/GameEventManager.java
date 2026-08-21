package org.example.pillars.gameevents;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.example.pillars.GameSession;
import org.example.pillars.enums.GameState;
import org.example.pillars.managers.HudManager;
import org.example.pillars.managers.ItemManager;
import org.example.pillars.managers.SoundManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public final class GameEventManager {
    private static final long TICKS_PER_SECOND = 20L;

    private final JavaPlugin plugin;
    private final GameSession session;
    private boolean automaticEventsEnabled;
    private final int minimumDelaySeconds;
    private final int maximumDelaySeconds;
    private final int antiRepeatHistorySize;
    private final Map<String, Supplier<GameEvent>> eventFactories;
    private final Set<String> randomEventIds;
    private final Supplier<GameEvent> lastBreathEventFactory;
    private final Deque<String> recentEventIds = new ArrayDeque<>();

    private BukkitTask startTask;
    private BukkitTask stopTask;
    private GameEvent activeEvent;
    private long activeEventEndTick = -1L;
    private String scheduledEventId;
    private long scheduledEventStartTick = -1L;
    private boolean finalPhaseActive;
    private boolean automaticEventsRunning;

    public GameEventManager(
            JavaPlugin plugin,
            GameSession session,
            HudManager hudManager,
            SoundManager soundManager,
            ItemManager itemManager
    ) {
        this.plugin = plugin;
        this.session = session;
        this.automaticEventsEnabled = plugin.getConfig().getBoolean("settings.gameEvents.enabled", true);

        int configuredMinimumDelay = Math.max(
                0,
                plugin.getConfig().getInt("settings.gameEvents.minimumDelaySeconds", 30)
        );
        int configuredMaximumDelay = Math.max(
                configuredMinimumDelay,
                plugin.getConfig().getInt("settings.gameEvents.maximumDelaySeconds", 60)
        );
        this.minimumDelaySeconds = configuredMinimumDelay;
        this.maximumDelaySeconds = configuredMaximumDelay;

        int smashDurationSeconds = Math.max(
                1,
                plugin.getConfig().getInt("settings.gameEvents.superSmashBros.durationSeconds", 20)
        );
        double smashKnockbackMultiplier = Math.max(
                1.0,
                plugin.getConfig().getDouble("settings.gameEvents.superSmashBros.knockbackMultiplier", 2.5)
        );

        int cosmicDriftDurationSeconds = Math.max(
                1,
                plugin.getConfig().getInt("settings.gameEvents.cosmicDrift.durationSeconds", 25)
        );
        double cosmicDriftGravityMultiplier = clampMultiplier(
                plugin.getConfig().getDouble("settings.gameEvents.cosmicDrift.gravityMultiplier", 0.35)
        );
        double cosmicDriftJumpStrengthMultiplier = Math.max(
                1.0,
                plugin.getConfig().getDouble("settings.gameEvents.cosmicDrift.jumpStrengthMultiplier", 1.3)
        );
        double cosmicDriftFallDamageMultiplier = clampMultiplier(
                plugin.getConfig().getDouble("settings.gameEvents.cosmicDrift.fallDamageMultiplier", 0.25)
        );

        int meteorShowerDurationSeconds = Math.max(
                1,
                plugin.getConfig().getInt("settings.gameEvents.meteorShower.durationSeconds", 24)
        );
        long meteorShowerWavePeriodTicks = Math.max(
                20L,
                plugin.getConfig().getLong("settings.gameEvents.meteorShower.wavePeriodTicks", 60L)
        );
        int meteorShowerWarningTicks = Math.max(
                10,
                plugin.getConfig().getInt("settings.gameEvents.meteorShower.warningTicks", 30)
        );

        int earthquakeDurationSeconds = Math.max(
                1,
                plugin.getConfig().getInt("settings.gameEvents.earthquake.durationSeconds", 24)
        );
        long earthquakeWavePeriodTicks = Math.max(
                20L,
                plugin.getConfig().getLong("settings.gameEvents.earthquake.wavePeriodTicks", 60L)
        );
        int earthquakeWarningTicks = Math.max(
                10,
                plugin.getConfig().getInt("settings.gameEvents.earthquake.warningTicks", 30)
        );

        int huntDurationSeconds = Math.max(
                1,
                plugin.getConfig().getInt("settings.gameEvents.huntBegins.durationSeconds", 30)
        );
        int huntRewardItemCount = Math.max(
                1,
                plugin.getConfig().getInt("settings.gameEvents.huntBegins.rewardItemCount", 2)
        );

        int hotPotatoDurationSeconds = Math.max(
                3,
                plugin.getConfig().getInt("settings.gameEvents.hotPotato.durationSeconds", 15)
        );

        this.eventFactories = Map.of(
                "super_smash_bros", () -> new SuperSmashBrosEvent(
                        session,
                        hudManager,
                        smashDurationSeconds,
                        smashKnockbackMultiplier
                ),
                "cosmic_drift", () -> new CosmicDriftEvent(
                        plugin,
                        session,
                        hudManager,
                        cosmicDriftDurationSeconds,
                        cosmicDriftGravityMultiplier,
                        cosmicDriftJumpStrengthMultiplier,
                        cosmicDriftFallDamageMultiplier
                ),
                "meteor_shower", () -> new MeteorShowerEvent(
                        plugin,
                        session,
                        hudManager,
                        meteorShowerDurationSeconds,
                        meteorShowerWavePeriodTicks,
                        meteorShowerWarningTicks,
                        Math.max(1, plugin.getConfig().getInt("settings.gameEvents.meteorShower.maxMeteorsPerWave", 3)),
                        Math.max(1, plugin.getConfig().getInt("settings.gameEvents.meteorShower.playersPerMeteor", 4)),
                        Math.max(1.0, plugin.getConfig().getDouble("settings.gameEvents.meteorShower.impactRadius", 3.5)),
                        Math.max(0.0, plugin.getConfig().getDouble("settings.gameEvents.meteorShower.maxDamage", 6.0)),
                        Math.max(0.0, plugin.getConfig().getDouble("settings.gameEvents.meteorShower.knockbackStrength", 1.15)),
                        Math.max(4.0, plugin.getConfig().getDouble("settings.gameEvents.meteorShower.spawnHeight", 16.0)),
                        Math.max(0.0, plugin.getConfig().getDouble("settings.gameEvents.meteorShower.randomOffsetRadius", 3.0))
                ),
                "earthquake", () -> new EarthquakeEvent(
                        plugin,
                        session,
                        hudManager,
                        earthquakeDurationSeconds,
                        earthquakeWavePeriodTicks,
                        earthquakeWarningTicks,
                        Math.max(20L, plugin.getConfig().getLong("settings.gameEvents.earthquake.missingDurationTicks", 60L)),
                        Math.max(1, plugin.getConfig().getInt("settings.gameEvents.earthquake.maxBlocksPerWave", 4)),
                        Math.max(1, plugin.getConfig().getInt("settings.gameEvents.earthquake.playersPerBlock", 3)),
                        Math.max(0, plugin.getConfig().getInt("settings.gameEvents.earthquake.horizontalSearchRadius", 3))
                ),
                "hunt_begins", () -> new HuntBeginsEvent(
                        session,
                        hudManager,
                        itemManager,
                        huntDurationSeconds,
                        huntRewardItemCount
                ),
                "hot_potato", () -> new HotPotatoEvent(
                        plugin,
                        session,
                        hudManager,
                        hotPotatoDurationSeconds,
                        Math.max(0L, plugin.getConfig().getLong("settings.gameEvents.hotPotato.passCooldownTicks", 30L)),
                        Math.max(0.0, plugin.getConfig().getDouble("settings.gameEvents.hotPotato.explosionDamage", 6.0)),
                        Math.max(0.0, plugin.getConfig().getDouble("settings.gameEvents.hotPotato.horizontalKnockback", 0.45)),
                        Math.max(0.0, plugin.getConfig().getDouble("settings.gameEvents.hotPotato.verticalKnockback", 1.05)),
                        Math.max(1, plugin.getConfig().getInt("settings.gameEvents.hotPotato.urgentSeconds", 3))
                )
        );
        this.randomEventIds = eventFactories.keySet().stream()
                .filter(eventId -> !eventId.equals("meteor_shower"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.antiRepeatHistorySize = Math.min(
                Math.max(
                        0,
                        plugin.getConfig().getInt("settings.gameEvents.antiRepeatHistorySize", 2)
                ),
                Math.max(0, randomEventIds.size() - 1)
        );

        int effectDurationTicks = Math.max(
                1,
                plugin.getConfig().getInt("settings.gameEvents.lastBreath.effectDurationTicks", 40)
        );
        long effectPeriodTicks = Math.max(
                1L,
                plugin.getConfig().getLong("settings.gameEvents.lastBreath.effectPeriodTicks", 40L)
        );
        int effectAmplifier = Math.max(
                0,
                plugin.getConfig().getInt("settings.gameEvents.lastBreath.effectAmplifier", 1)
        );

        this.lastBreathEventFactory = () -> new LastBreathEvent(
                plugin,
                session,
                hudManager,
                soundManager,
                effectDurationTicks,
                effectPeriodTicks,
                effectAmplifier
        );
    }

    public void scheduleRandomEvent() {
        stop();
        if (!automaticEventsEnabled || randomEventIds.isEmpty()) return;

        automaticEventsRunning = true;
        scheduleNextRandomEvent();
    }

    private void scheduleNextRandomEvent() {
        if (!automaticEventsRunning
                || finalPhaseActive
                || session.getState() != GameState.RUNNING
                || randomEventIds.isEmpty()) {
            return;
        }

        cancelStartTask();

        int delaySeconds = (int) ThreadLocalRandom.current().nextLong(
                minimumDelaySeconds,
                (long) maximumDelaySeconds + 1L
        );
        scheduledEventId = selectNextEventId();
        scheduledEventStartTick = (long) Bukkit.getCurrentTick() + delaySeconds * TICKS_PER_SECOND;

        startTask = plugin.getServer().getScheduler().runTaskLater(
                plugin,
                this::startRandomEvent,
                delaySeconds * TICKS_PER_SECOND
        );
    }

    public boolean startEvent(String eventId) {
        String normalizedEventId = normalizeEventId(eventId);
        if (session.getState() != GameState.RUNNING || finalPhaseActive) return false;

        if (normalizedEventId.equals("last_breath")) {
            startLastBreathEvent();
            return true;
        }

        Supplier<GameEvent> eventFactory = eventFactories.get(normalizedEventId);
        if (eventFactory == null) return false;

        cancelStartTask();
        cancelStopTask();
        stopActiveEvent();
        rememberEvent(normalizedEventId);
        startEvent(eventFactory.get());
        return true;
    }

    public void startLastBreathEvent() {
        if (session.getState() != GameState.RUNNING || finalPhaseActive) return;

        stop();
        finalPhaseActive = true;
        startEvent(lastBreathEventFactory.get());
    }

    public void stop() {
        automaticEventsRunning = false;
        cancelStartTask();
        cancelStopTask();
        stopActiveEvent();
        finalPhaseActive = false;
        recentEventIds.clear();
    }

    public void setAutomaticEventsEnabled(boolean enabled) {
        automaticEventsEnabled = enabled;

        if (!enabled) {
            automaticEventsRunning = false;
            cancelStartTask();
            return;
        }

        if (session.getState() != GameState.RUNNING || finalPhaseActive) return;

        automaticEventsRunning = true;
        if (activeEvent == null) {
            scheduleNextRandomEvent();
        }
    }

    public boolean areAutomaticEventsEnabled() {
        return automaticEventsEnabled;
    }

    public Vector modifyKnockback(Player victim, Player damager, Vector knockback) {
        if (activeEvent instanceof KnockbackGameEvent knockbackEvent) {
            return knockbackEvent.modifyKnockback(victim, damager, knockback);
        }
        return knockback;
    }

    public void onPlayerEliminated(Player eliminated, Player killer) {
        if (activeEvent instanceof PlayerLifecycleGameEvent playerEvent
                && playerEvent.onPlayerEliminated(eliminated, killer)) {
            finishResolvedEvent();
        }
    }

    public void onPlayerRemoved(Player player) {
        if (activeEvent instanceof PlayerLifecycleGameEvent playerEvent
                && playerEvent.onPlayerRemoved(player)) {
            finishResolvedEvent();
        }
    }

    public void onDirectHit(Player attacker, Player victim) {
        if (activeEvent instanceof DirectHitGameEvent hitEvent) {
            hitEvent.onDirectHit(attacker, victim);
        }
    }

    public GameEventStatus getActiveEventStatus() {
        if (activeEvent == null) return null;

        int remainingSeconds;
        if (activeEvent instanceof DynamicGameEventStatus dynamicStatus) {
            remainingSeconds = dynamicStatus.getRemainingSeconds();
        } else {
            long remainingTicks = Math.max(0L, activeEventEndTick - Bukkit.getCurrentTick());
            remainingSeconds = (int) Math.max(
                    1L,
                    (remainingTicks + TICKS_PER_SECOND - 1L) / TICKS_PER_SECOND
            );
        }
        return new GameEventStatus(activeEvent.getId(), remainingSeconds);
    }

    public NextGameEventStatus getNextEventStatus() {
        if (scheduledEventId == null || scheduledEventStartTick < 0L) return null;

        long remainingTicks = Math.max(0L, scheduledEventStartTick - Bukkit.getCurrentTick());
        int remainingSeconds = (int) Math.max(
                1L,
                (remainingTicks + TICKS_PER_SECOND - 1L) / TICKS_PER_SECOND
        );
        return new NextGameEventStatus(scheduledEventId, remainingSeconds);
    }

    public boolean isFinalPhaseActive() {
        return finalPhaseActive;
    }

    private void startRandomEvent() {
        startTask = null;
        String eventId = scheduledEventId;
        clearScheduledEvent();
        if (!automaticEventsRunning || finalPhaseActive || session.getState() != GameState.RUNNING) return;
        if (eventId == null || !eventFactories.containsKey(eventId)) {
            scheduleNextRandomEvent();
            return;
        }
        rememberEvent(eventId);
        startEvent(eventFactories.get(eventId).get());
    }

    private String selectNextEventId() {
        List<String> availableEventIds = new ArrayList<>(randomEventIds);
        availableEventIds.removeAll(recentEventIds);
        if (availableEventIds.isEmpty()) {
            recentEventIds.clear();
            availableEventIds.addAll(randomEventIds);
        }

        return availableEventIds.get(ThreadLocalRandom.current().nextInt(availableEventIds.size()));
    }

    private void startEvent(GameEvent event) {
        activeEvent = event;
        activeEventEndTick = (long) Bukkit.getCurrentTick()
                + activeEvent.getDurationSeconds() * TICKS_PER_SECOND;
        activeEvent.start();

        if (activeEvent.getDurationSeconds() > 0) {
            stopTask = plugin.getServer().getScheduler().runTaskLater(
                    plugin,
                    this::finishTimedEvent,
                    activeEvent.getDurationSeconds() * TICKS_PER_SECOND
            );
        }
    }

    private void finishTimedEvent() {
        if (activeEvent instanceof TimedGameEventCompletion completion) {
            completion.complete();
        }
        stopActiveEvent();
        scheduleNextRandomEvent();
    }

    private void finishResolvedEvent() {
        cancelStopTask();
        stopActiveEvent();
        scheduleNextRandomEvent();
    }

    private void stopActiveEvent() {
        if (activeEvent == null) return;

        activeEvent.stop();
        activeEvent = null;
        activeEventEndTick = -1L;
        stopTask = null;
    }

    private void rememberEvent(String eventId) {
        if (antiRepeatHistorySize <= 0) return;

        recentEventIds.remove(eventId);
        recentEventIds.addLast(eventId);
        while (recentEventIds.size() > antiRepeatHistorySize) {
            recentEventIds.removeFirst();
        }
    }

    private void cancelStartTask() {
        if (startTask != null) {
            startTask.cancel();
            startTask = null;
        }
        clearScheduledEvent();
    }

    private void clearScheduledEvent() {
        scheduledEventId = null;
        scheduledEventStartTick = -1L;
    }

    private void cancelStopTask() {
        if (stopTask == null) return;

        stopTask.cancel();
        stopTask = null;
    }

    private String normalizeEventId(String eventId) {
        return switch (eventId.toLowerCase()) {
            case "smash", "super-smash-bros", "supersmashbros" -> "super_smash_bros";
            case "cosmic", "drift", "cosmic-drift", "cosmicdrift" -> "cosmic_drift";
            case "meteor", "meteors", "meteor-shower", "meteorshower" -> "meteor_shower";
            case "earthquake", "quake" -> "earthquake";
            case "hunt", "hunting", "hunt-begins", "huntbegins" -> "hunt_begins";
            case "potato", "hot-potato", "hotpotato" -> "hot_potato";
            case "lastbreath" -> "last_breath";
            default -> eventId.toLowerCase();
        };
    }

    private double clampMultiplier(double multiplier) {
        return Math.max(0.0, Math.min(1.0, multiplier));
    }
}
