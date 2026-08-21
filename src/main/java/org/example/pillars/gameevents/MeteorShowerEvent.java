package org.example.pillars.gameevents;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.example.pillars.GameSession;
import org.example.pillars.managers.HudManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class MeteorShowerEvent implements GameEvent {
    private static final String ID = "meteor_shower";
    private static final long FIRST_WAVE_DELAY_TICKS = 10L;

    private final JavaPlugin plugin;
    private final GameSession session;
    private final HudManager hudManager;
    private final int durationSeconds;
    private final long wavePeriodTicks;
    private final int warningTicks;
    private final int maxMeteorsPerWave;
    private final int playersPerMeteor;
    private final double impactRadius;
    private final double maxDamage;
    private final double knockbackStrength;
    private final double spawnHeight;
    private final double randomOffsetRadius;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final List<BlockDisplay> meteorDisplays = new ArrayList<>();

    private boolean active;

    public MeteorShowerEvent(
            JavaPlugin plugin,
            GameSession session,
            HudManager hudManager,
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
    ) {
        this.plugin = plugin;
        this.session = session;
        this.hudManager = hudManager;
        this.durationSeconds = durationSeconds;
        this.wavePeriodTicks = wavePeriodTicks;
        this.warningTicks = warningTicks;
        this.maxMeteorsPerWave = maxMeteorsPerWave;
        this.playersPerMeteor = playersPerMeteor;
        this.impactRadius = impactRadius;
        this.maxDamage = maxDamage;
        this.knockbackStrength = knockbackStrength;
        this.spawnHeight = spawnHeight;
        this.randomOffsetRadius = randomOffsetRadius;
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
        hudManager.sendMeteorShowerStarted(session.getAllPlayerIds(), durationSeconds);

        long durationTicks = durationSeconds * 20L;
        int maximumWaves = (int) Math.max(
                1L,
                (durationTicks - FIRST_WAVE_DELAY_TICKS - warningTicks) / wavePeriodTicks + 1L
        );

        BukkitTask waveTask = new BukkitRunnable() {
            private int wavesStarted;

            @Override
            public void run() {
                if (!active || wavesStarted >= maximumWaves) {
                    cancel();
                    return;
                }

                startWave();
                wavesStarted++;
            }
        }.runTaskTimer(plugin, FIRST_WAVE_DELAY_TICKS, wavePeriodTicks);
        tasks.add(waveTask);
    }

    @Override
    public void stop() {
        if (!active) return;

        active = false;
        for (BukkitTask task : tasks) {
            task.cancel();
        }
        tasks.clear();

        for (BlockDisplay meteorDisplay : meteorDisplays) {
            if (meteorDisplay.isValid()) {
                meteorDisplay.remove();
            }
        }
        meteorDisplays.clear();

        hudManager.sendMeteorShowerEnded(session.getAllPlayerIds());
    }

    private void startWave() {
        List<Player> players = getActivePlayers();
        if (players.isEmpty()) return;

        Collections.shuffle(players);
        int meteorCount = Math.min(
                maxMeteorsPerWave,
                Math.max(1, (players.size() + playersPerMeteor - 1) / playersPerMeteor)
        );

        for (int i = 0; i < meteorCount; i++) {
            launchMeteor(createImpactLocation(players.get(i % players.size())));
        }
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

    private Location createImpactLocation(Player target) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble(Math.PI * 2.0);
        double distance = Math.sqrt(random.nextDouble()) * randomOffsetRadius;

        return target.getLocation().clone().add(
                Math.cos(angle) * distance,
                0.1,
                Math.sin(angle) * distance
        );
    }

    private void launchMeteor(Location impactLocation) {
        World world = impactLocation.getWorld();
        Location startLocation = impactLocation.clone().add(0.0, spawnHeight, 0.0);
        BlockDisplay meteorDisplay = world.spawn(startLocation, BlockDisplay.class, display -> {
            display.setBlock(Material.MAGMA_BLOCK.createBlockData());
            display.setGlowing(true);
            display.setGlowColorOverride(Color.ORANGE);
            display.setBrightness(new BlockDisplay.Brightness(15, 15));
        });
        meteorDisplays.add(meteorDisplay);

        BukkitTask meteorTask = new BukkitRunnable() {
            private int elapsedTicks;

            @Override
            public void run() {
                if (!active) {
                    removeMeteor(meteorDisplay);
                    cancel();
                    return;
                }

                elapsedTicks += 2;
                drawWarning(impactLocation);

                double progress = Math.min(1.0, (double) elapsedTicks / warningTicks);
                Location meteorLocation = impactLocation.clone().add(
                        0.0,
                        spawnHeight * (1.0 - progress),
                        0.0
                );
                meteorDisplay.teleport(meteorLocation);
                world.spawnParticle(Particle.FLAME, meteorLocation, 6, 0.25, 0.5, 0.25, 0.02);
                world.spawnParticle(Particle.LARGE_SMOKE, meteorLocation, 3, 0.2, 0.35, 0.2, 0.01);

                if (elapsedTicks < warningTicks) return;

                removeMeteor(meteorDisplay);
                impact(impactLocation);
                cancel();
            }
        }.runTaskTimer(plugin, 0L, 2L);
        tasks.add(meteorTask);
    }

    private void drawWarning(Location location) {
        World world = location.getWorld();
        Particle.DustOptions dust = new Particle.DustOptions(Color.ORANGE, 1.4F);
        int points = 20;
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2.0 * i / points;
            world.spawnParticle(
                    Particle.DUST,
                    location.clone().add(
                            Math.cos(angle) * impactRadius,
                            0.05,
                            Math.sin(angle) * impactRadius
                    ),
                    1,
                    0.0,
                    0.0,
                    0.0,
                    dust
            );
        }
    }

    private void impact(Location impactLocation) {
        World world = impactLocation.getWorld();
        world.spawnParticle(Particle.EXPLOSION_EMITTER, impactLocation, 1);
        world.spawnParticle(Particle.FLAME, impactLocation, 35, 1.0, 0.5, 1.0, 0.12);
        world.playSound(impactLocation, Sound.ENTITY_GENERIC_EXPLODE, 1.2F, 0.8F);

        for (Player player : getActivePlayers()) {
            if (!player.getWorld().equals(world)) continue;

            double distance = player.getLocation().distance(impactLocation);
            if (distance > impactRadius) continue;

            double impactFactor = 1.0 - distance / impactRadius;
            double damage = maxDamage * impactFactor;
            if (damage > 0.0) {
                player.damage(damage);
            }
            if (session.getActivePlayerIds().contains(player.getUniqueId())) {
                applyKnockback(player, impactLocation, impactFactor);
            }
        }
    }

    private void applyKnockback(Player player, Location impactLocation, double impactFactor) {
        Vector direction = player.getLocation().toVector().subtract(impactLocation.toVector());
        direction.setY(0.0);
        if (direction.lengthSquared() < 0.01) {
            direction = new Vector(
                    ThreadLocalRandom.current().nextDouble(-1.0, 1.0),
                    0.0,
                    ThreadLocalRandom.current().nextDouble(-1.0, 1.0)
            );
        }

        direction.normalize().multiply(knockbackStrength * (0.35 + impactFactor * 0.65));
        direction.setY(0.35 + impactFactor * 0.4);
        player.setVelocity(player.getVelocity().add(direction));
    }

    private void removeMeteor(BlockDisplay meteorDisplay) {
        meteorDisplays.remove(meteorDisplay);
        if (meteorDisplay.isValid()) {
            meteorDisplay.remove();
        }
    }
}
