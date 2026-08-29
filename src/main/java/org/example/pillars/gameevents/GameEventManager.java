package org.example.pillars.gameevents;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.example.pillars.GameSession;
import org.example.pillars.config.StartupSettings;
import org.example.pillars.enums.GameState;
import org.example.pillars.managers.HudManager;
import org.example.pillars.managers.ItemManager;
import org.example.pillars.managers.SoundManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public final class GameEventManager {
    private static final long TICKS_PER_SECOND = 20L;

    private final JavaPlugin plugin;
    private final GameSession session;
    private final HudManager hudManager;
    private final SoundManager soundManager;
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
            ItemManager itemManager,
            StartupSettings.GameEvents settings
    ) {
        this.plugin = plugin;
        this.session = session;
        this.hudManager = hudManager;
        this.soundManager = soundManager;
        this.automaticEventsEnabled = settings.initiallyEnabled();
        this.minimumDelaySeconds = settings.minimumDelaySeconds();
        this.maximumDelaySeconds = settings.maximumDelaySeconds();

        StartupSettings.SuperSmashBros smash = settings.superSmashBros();
        StartupSettings.CosmicDrift cosmicDrift = settings.cosmicDrift();
        StartupSettings.MeteorShower meteorShower = settings.meteorShower();
        StartupSettings.Earthquake earthquake = settings.earthquake();
        StartupSettings.HuntBegins huntBegins = settings.huntBegins();
        StartupSettings.HotPotato hotPotato = settings.hotPotato();

        this.eventFactories = Map.of(
                "super_smash_bros", () -> new SuperSmashBrosEvent(
                        session,
                        hudManager,
                        smash.durationSeconds(),
                        smash.knockbackMultiplier()
                ),
                "cosmic_drift", () -> new CosmicDriftEvent(
                        plugin,
                        session,
                        hudManager,
                        cosmicDrift.durationSeconds(),
                        cosmicDrift.gravityMultiplier(),
                        cosmicDrift.jumpStrengthMultiplier(),
                        cosmicDrift.fallDamageMultiplier()
                ),
                "meteor_shower", () -> new MeteorShowerEvent(
                        plugin,
                        session,
                        hudManager,
                        meteorShower.durationSeconds(),
                        meteorShower.wavePeriodTicks(),
                        meteorShower.warningTicks(),
                        meteorShower.maxMeteorsPerWave(),
                        meteorShower.playersPerMeteor(),
                        meteorShower.impactRadius(),
                        meteorShower.maxDamage(),
                        meteorShower.knockbackStrength(),
                        meteorShower.spawnHeight(),
                        meteorShower.randomOffsetRadius()
                ),
                "earthquake", () -> new EarthquakeEvent(
                        plugin,
                        session,
                        hudManager,
                        earthquake.durationSeconds(),
                        earthquake.wavePeriodTicks(),
                        earthquake.warningTicks(),
                        earthquake.missingDurationTicks(),
                        earthquake.maxBlocksPerWave(),
                        earthquake.playersPerBlock(),
                        earthquake.horizontalSearchRadius()
                ),
                "hunt_begins", () -> new HuntBeginsEvent(
                        session,
                        hudManager,
                        itemManager,
                        huntBegins.durationSeconds(),
                        huntBegins.rewardItemCount()
                ),
                "hot_potato", () -> new HotPotatoEvent(
                        plugin,
                        session,
                        hudManager,
                        hotPotato.durationSeconds(),
                        hotPotato.passCooldownTicks(),
                        hotPotato.explosionDamage(),
                        hotPotato.horizontalKnockback(),
                        hotPotato.verticalKnockback(),
                        hotPotato.urgentSeconds()
                )
        );
        this.randomEventIds = eventFactories.keySet().stream()
                .filter(eventId -> !eventId.equals("meteor_shower"))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.antiRepeatHistorySize = Math.min(
                Math.max(
                        0,
                        settings.antiRepeatHistorySize()
                ),
                Math.max(0, randomEventIds.size() - 1)
        );

        StartupSettings.LastBreath lastBreath = settings.lastBreath();

        this.lastBreathEventFactory = () -> new LastBreathEvent(
                plugin,
                session,
                hudManager,
                soundManager,
                lastBreath.effectDurationTicks(),
                lastBreath.effectPeriodTicks(),
                lastBreath.effectAmplifier()
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
        hudManager.sendGameEventStartedTitle(session.getAllPlayerIds(), activeEvent.getId());

        if (!activeEvent.getId().equals("last_breath")) {
            for (java.util.UUID playerId : session.getAllPlayerIds()) {
                Player player = Bukkit.getPlayer(playerId);
                if (player != null && player.isOnline()) {
                    soundManager.playGameEventStartSound(player);
                }
            }
        }

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
        String normalized = eventId.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "smash", "super-smash-bros", "supersmashbros" -> "super_smash_bros";
            case "cosmic", "drift", "cosmic-drift", "cosmicdrift" -> "cosmic_drift";
            case "meteor", "meteors", "meteor-shower", "meteorshower" -> "meteor_shower";
            case "earthquake", "quake" -> "earthquake";
            case "hunt", "hunting", "hunt-begins", "huntbegins" -> "hunt_begins";
            case "potato", "hot-potato", "hotpotato" -> "hot_potato";
            case "lastbreath" -> "last_breath";
            default -> normalized;
        };
    }
}
