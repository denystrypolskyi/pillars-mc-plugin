package org.example.pillars.config;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable configuration captured exactly once during plugin enable.
 * Changes to these values require a full plugin/server restart.
 */
public record StartupSettings(Session session, GameEvents gameEvents) {

    public static StartupSettings load(FileConfiguration config) {
        Session session = new Session(
                atLeast(config.getInt("settings.beginCountdownSeconds", 5), 1),
                atLeast(config.getInt("settings.endGameLobbyCountdownSeconds", 5), 1),
                atLeast(config.getLong("settings.endGameSpectatorDelayTicks", 40L), 0L),
                atLeast(config.getLong("settings.arenaResetDelayTicks", 160L), 1L),
                clamp(config.getInt("settings.spawnPillarHeightBlocks", 5), 1, 64),
                clamp(config.getInt("settings.floorColumnsPerTick", 512), 32, 4096),
                config.getString("settings.lobbyWorldName", "world"),
                Math.max(1.0, config.getDouble("settings.borderMinSize", 1.0)),
                Math.max(1.0, config.getDouble("settings.borderSpawnPaddingBlocks", 10.0))
        );

        int minimumDelay = atLeast(config.getInt("settings.gameEvents.minimumDelaySeconds", 30), 0);
        GameEvents gameEvents = new GameEvents(
                config.getBoolean("settings.gameEvents.enabled", true),
                minimumDelay,
                atLeast(config.getInt("settings.gameEvents.maximumDelaySeconds", 60), minimumDelay),
                atLeast(config.getInt("settings.gameEvents.antiRepeatHistorySize", 2), 0),
                new SuperSmashBros(
                        atLeast(config.getInt("settings.gameEvents.superSmashBros.durationSeconds", 20), 1),
                        Math.max(1.0, config.getDouble("settings.gameEvents.superSmashBros.knockbackMultiplier", 2.5))
                ),
                new CosmicDrift(
                        atLeast(config.getInt("settings.gameEvents.cosmicDrift.durationSeconds", 25), 1),
                        clampMultiplier(config.getDouble("settings.gameEvents.cosmicDrift.gravityMultiplier", 0.35)),
                        Math.max(1.0, config.getDouble("settings.gameEvents.cosmicDrift.jumpStrengthMultiplier", 1.3)),
                        clampMultiplier(config.getDouble("settings.gameEvents.cosmicDrift.fallDamageMultiplier", 0.25))
                ),
                new MeteorShower(
                        atLeast(config.getInt("settings.gameEvents.meteorShower.durationSeconds", 24), 1),
                        atLeast(config.getLong("settings.gameEvents.meteorShower.wavePeriodTicks", 60L), 20L),
                        atLeast(config.getInt("settings.gameEvents.meteorShower.warningTicks", 30), 10),
                        atLeast(config.getInt("settings.gameEvents.meteorShower.maxMeteorsPerWave", 3), 1),
                        atLeast(config.getInt("settings.gameEvents.meteorShower.playersPerMeteor", 4), 1),
                        Math.max(1.0, config.getDouble("settings.gameEvents.meteorShower.impactRadius", 3.5)),
                        Math.max(0.0, config.getDouble("settings.gameEvents.meteorShower.maxDamage", 6.0)),
                        Math.max(0.0, config.getDouble("settings.gameEvents.meteorShower.knockbackStrength", 1.15)),
                        Math.max(4.0, config.getDouble("settings.gameEvents.meteorShower.spawnHeight", 16.0)),
                        Math.max(0.0, config.getDouble("settings.gameEvents.meteorShower.randomOffsetRadius", 3.0))
                ),
                new Earthquake(
                        atLeast(config.getInt("settings.gameEvents.earthquake.durationSeconds", 24), 1),
                        atLeast(config.getLong("settings.gameEvents.earthquake.wavePeriodTicks", 60L), 20L),
                        atLeast(config.getInt("settings.gameEvents.earthquake.warningTicks", 30), 10),
                        atLeast(config.getLong("settings.gameEvents.earthquake.missingDurationTicks", 60L), 20L),
                        atLeast(config.getInt("settings.gameEvents.earthquake.maxBlocksPerWave", 4), 1),
                        atLeast(config.getInt("settings.gameEvents.earthquake.playersPerBlock", 3), 1),
                        atLeast(config.getInt("settings.gameEvents.earthquake.horizontalSearchRadius", 3), 0)
                ),
                new HuntBegins(
                        atLeast(config.getInt("settings.gameEvents.huntBegins.durationSeconds", 30), 1),
                        atLeast(config.getInt("settings.gameEvents.huntBegins.rewardItemCount", 2), 1)
                ),
                new HotPotato(
                        atLeast(config.getInt("settings.gameEvents.hotPotato.durationSeconds", 15), 3),
                        atLeast(config.getLong("settings.gameEvents.hotPotato.passCooldownTicks", 30L), 0L),
                        Math.max(0.0, config.getDouble("settings.gameEvents.hotPotato.explosionDamage", 6.0)),
                        Math.max(0.0, config.getDouble("settings.gameEvents.hotPotato.horizontalKnockback", 0.45)),
                        Math.max(0.0, config.getDouble("settings.gameEvents.hotPotato.verticalKnockback", 1.05)),
                        atLeast(config.getInt("settings.gameEvents.hotPotato.urgentSeconds", 3), 1)
                ),
                new LastBreath(
                        atLeast(config.getInt("settings.gameEvents.lastBreath.effectDurationTicks", 40), 1),
                        atLeast(config.getLong("settings.gameEvents.lastBreath.effectPeriodTicks", 40L), 1L),
                        atLeast(config.getInt("settings.gameEvents.lastBreath.effectAmplifier", 1), 0)
                )
        );

        return new StartupSettings(session, gameEvents);
    }

    private static int atLeast(int value, int minimum) {
        return Math.max(minimum, value);
    }

    private static long atLeast(long value, long minimum) {
        return Math.max(minimum, value);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double clampMultiplier(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public record Session(
            int beginCountdownSeconds,
            int endGameLobbyCountdownSeconds,
            long endGameSpectatorDelayTicks,
            long arenaResetDelayTicks,
            int spawnPillarHeightBlocks,
            int floorColumnsPerTick,
            String lobbyWorldName,
            double borderMinimumSize,
            double borderSpawnPaddingBlocks
    ) {}

    public record GameEvents(
            boolean initiallyEnabled,
            int minimumDelaySeconds,
            int maximumDelaySeconds,
            int antiRepeatHistorySize,
            SuperSmashBros superSmashBros,
            CosmicDrift cosmicDrift,
            MeteorShower meteorShower,
            Earthquake earthquake,
            HuntBegins huntBegins,
            HotPotato hotPotato,
            LastBreath lastBreath
    ) {}

    public record SuperSmashBros(int durationSeconds, double knockbackMultiplier) {}

    public record CosmicDrift(
            int durationSeconds,
            double gravityMultiplier,
            double jumpStrengthMultiplier,
            double fallDamageMultiplier
    ) {}

    public record MeteorShower(
            int durationSeconds,
            long wavePeriodTicks,
            int warningTicks,
            int maxMeteorsPerWave,
            int playersPerMeteor,
            double impactRadius,
            double maxDamage,
            double knockbackStrength,
            double spawnHeight,
            double randomOffsetRadius
    ) {}

    public record Earthquake(
            int durationSeconds,
            long wavePeriodTicks,
            int warningTicks,
            long missingDurationTicks,
            int maxBlocksPerWave,
            int playersPerBlock,
            int horizontalSearchRadius
    ) {}

    public record HuntBegins(int durationSeconds, int rewardItemCount) {}

    public record HotPotato(
            int durationSeconds,
            long passCooldownTicks,
            double explosionDamage,
            double horizontalKnockback,
            double verticalKnockback,
            int urgentSeconds
    ) {}

    public record LastBreath(int effectDurationTicks, long effectPeriodTicks, int effectAmplifier) {}
}
