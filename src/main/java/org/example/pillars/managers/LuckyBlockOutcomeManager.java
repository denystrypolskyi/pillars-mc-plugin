package org.example.pillars.managers;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.entity.Ravager;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Wolf;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.example.pillars.GameSession;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class LuckyBlockOutcomeManager implements Listener {
    private final JavaPlugin plugin;
    private final LuckyBlockEffectService effects;

    public LuckyBlockOutcomeManager(JavaPlugin plugin, ItemManager itemManager, TranslationManager translations) {
        this.plugin = plugin;
        this.effects = new LuckyBlockEffectService(
                plugin,
                itemManager,
                translations,
                new LuckyBlockOutcomeSelector(plugin)
        );
    }

    public void trigger(Player player, Location blockLocation, GameSession session) {
        effects.trigger(player, blockLocation, session);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTemporaryFluidFlow(BlockFromToEvent event) {
        effects.onTemporaryFluidFlow(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLuckyTntExplode(EntityExplodeEvent event) {
        effects.onLuckyTntExplode(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTrackedFallingBlockLand(EntityChangeBlockEvent event) {
        effects.onTrackedFallingBlockLand(event);
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (event.getPlugin() == plugin) effects.cleanupAll();
    }
}

final class LuckyBlockEffectService {
    private static final List<EntityType> PASSIVE_MOBS = List.of(
            EntityType.CHICKEN, EntityType.PIG, EntityType.SHEEP, EntityType.COW, EntityType.RABBIT
    );
    private static final List<EntityType> HOSTILE_MOBS = List.of(
            EntityType.ZOMBIE, EntityType.SKELETON, EntityType.SPIDER, EntityType.SLIME, EntityType.HUSK
    );

    private final JavaPlugin plugin;
    private final ItemManager itemManager;
    private final TranslationManager translations;
    private final LuckyBlockOutcomeSelector selector;
    private final Map<GameSession, Set<UUID>> spawnedEntities = new HashMap<>();
    private final Set<UUID> luckyExplosives = new HashSet<>();
    private final Map<Location, TemporaryFluid> fluidBlocks = new HashMap<>();
    private final Set<TemporaryFluid> activeFluids = new HashSet<>();
    private final Set<TemporaryBlocks> activeTemporaryBlocks = new HashSet<>();

    LuckyBlockEffectService(
            JavaPlugin plugin,
            ItemManager itemManager,
            TranslationManager translations,
            LuckyBlockOutcomeSelector selector
    ) {
        this.plugin = plugin;
        this.itemManager = itemManager;
        this.translations = translations;
        this.selector = selector;
        Bukkit.getScheduler().runTaskTimer(plugin, this::cleanupEndedSessions, 20L, 20L);
    }

    public void trigger(Player player, Location blockLocation, GameSession session) {
        LuckyOutcome outcome = selector.select(player, session);
        execute(outcome, player, blockLocation, session);
        selector.remember(player, session, outcome);
    }

    public void onTemporaryFluidFlow(BlockFromToEvent event) {
        TemporaryFluid fluid = fluidBlocks.get(event.getBlock().getLocation());
        if (fluid == null) return;

        Location destination = event.getToBlock().getLocation();
        fluid.locations().add(destination);
        fluidBlocks.put(destination, fluid);
    }

    public void onLuckyTntExplode(EntityExplodeEvent event) {
        if (!luckyExplosives.remove(event.getEntity().getUniqueId())) return;
        if (!plugin.getConfig().getBoolean("settings.luckyBlocks.tntBlockDamage", false)) {
            event.blockList().clear();
        }
    }

    public void onTrackedFallingBlockLand(EntityChangeBlockEvent event) {
        UUID entityId = event.getEntity().getUniqueId();
        boolean tracked = spawnedEntities.values().stream().anyMatch(entities -> entities.contains(entityId));
        if (!tracked || event.getEntityType() != EntityType.FALLING_BLOCK) return;

        event.setCancelled(true);
        event.getEntity().remove();
    }

    private void execute(LuckyOutcome outcome, Player player, Location location, GameSession session) {
        switch (outcome) {
            case RANDOM_ITEM -> itemManager.giveRandomItem(player);
            case DIAMONDS -> give(player, new ItemStack(Material.DIAMOND, randomInt(1, 4)));
            case GOLDEN_APPLE -> give(player, new ItemStack(Material.GOLDEN_APPLE));
            case FULL_HEAL -> {
                player.setHealth(player.getMaxHealth());
                player.setFoodLevel(20);
                player.setFireTicks(0);
            }
            case REGENERATION -> effect(player, PotionEffectType.REGENERATION, 160, 1);
            case SPEED -> effect(player, PotionEffectType.SPEED, 300, 1);
            case STRENGTH -> effect(player, PotionEffectType.STRENGTH, 200, 0);
            case EXPERIENCE -> player.giveExp(randomInt(8, 21));
            case FRIENDLY_WOLF -> spawnFriendlyWolf(player, location, session);
            case ITEM_RAIN -> spawnItemRain(location);
            case JACKPOT -> giveJackpot(player);
            case WATER -> spawnTemporaryFluid(location, Material.WATER, session);
            case PASSIVE_MOB -> spawnTracked(randomType(PASSIVE_MOBS), above(location), session);
            case EXTRA_LUCKY_BLOCKS -> {
                itemManager.giveLuckyBlock(player);
                itemManager.giveLuckyBlock(player);
            }
            case SWAP_PLAYERS -> swapWithRandomPlayer(player, session);
            case FIREWORK -> spawnFirework(location);
            case LAVA -> spawnTemporaryFluid(location, Material.LAVA, session);
            case PRIMED_TNT -> spawnTnt(player, location, session, 0);
            case EXPLOSION -> location.getWorld().createExplosion(
                    center(location),
                    explosionPower(),
                    false,
                    plugin.getConfig().getBoolean("settings.luckyBlocks.tntBlockDamage", false),
                    player
            );
            case LIGHTNING -> {
                location.getWorld().strikeLightningEffect(center(location));
                player.damage(5.0);
            }
            case FALLING_ANVIL -> spawnFallingAnvil(player, session);
            case POISON -> effect(player, PotionEffectType.POISON, 120, 0);
            case BLINDNESS -> effect(player, PotionEffectType.BLINDNESS, 120, 0);
            case LAUNCH -> player.setVelocity(new Vector(0.0, 1.45, 0.0));
            case HOSTILE_MOB -> spawnTracked(randomType(HOSTILE_MOBS), above(location), session);
            case MOB_HORDE -> spawnMobHorde(location, session);
            case WEB_TRAP -> spawnWebTrap(player, session);
            case CREEPER -> spawnCreeper(location, session);
            case TNT_RAIN -> spawnTntRain(player, location, session);
            case MINI_BOSS -> spawnMiniBoss(location, session);
        }
    }

    private void give(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
        for (ItemStack leftover : leftovers.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), leftover);
        }
    }

    private void effect(Player player, PotionEffectType type, int ticks, int amplifier) {
        player.addPotionEffect(new PotionEffect(type, ticks, amplifier));
    }

    private void spawnFriendlyWolf(Player player, Location location, GameSession session) {
        Wolf wolf = (Wolf) spawnTracked(EntityType.WOLF, above(location), session);
        wolf.setTamed(true);
        wolf.setOwner(player);
        wolf.setCustomName(translations.text("messages.lucky-wolf-name"));
    }

    private void spawnItemRain(Location location) {
        Location dropLocation = center(location).add(0.0, 4.0, 0.0);
        for (int i = 0; i < 7; i++) {
            ItemStack item = itemManager.getRandomItem();
            location.getWorld().dropItemNaturally(dropLocation, item);
        }
    }

    private void giveJackpot(Player player) {
        give(player, new ItemStack(Material.DIAMOND, 5));
        give(player, new ItemStack(Material.GOLD_INGOT, 12));
        give(player, new ItemStack(Material.GOLDEN_APPLE, 2));
        player.giveExp(30);
    }

    private void swapWithRandomPlayer(Player player, GameSession session) {
        List<Player> candidates = new ArrayList<>();
        for (UUID playerId : session.getActivePlayerIds()) {
            Player candidate = Bukkit.getPlayer(playerId);
            if (candidate != null && candidate.isOnline() && !candidate.equals(player)) candidates.add(candidate);
        }
        if (candidates.isEmpty()) {
            itemManager.giveRandomItem(player);
            return;
        }

        Player other = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        Location first = player.getLocation().clone();
        Location second = other.getLocation().clone();
        player.teleport(second);
        other.teleport(first);
    }

    private void spawnFirework(Location location) {
        Firework firework = location.getWorld().spawn(above(location), Firework.class);
        FireworkMeta meta = firework.getFireworkMeta();
        meta.setPower(1);
        meta.addEffect(FireworkEffect.builder()
                .with(FireworkEffect.Type.BALL_LARGE)
                .withColor(Color.YELLOW, Color.ORANGE)
                .withFade(Color.WHITE)
                .trail(true)
                .flicker(true)
                .build());
        firework.setFireworkMeta(meta);
    }

    private void spawnFallingAnvil(Player player, GameSession session) {
        Location spawn = player.getLocation().clone().add(0.0, 8.0, 0.0);
        BlockData anvilData = Material.ANVIL.createBlockData();
        FallingBlock anvil = player.getWorld().spawnFallingBlock(spawn, anvilData);
        anvil.setDropItem(false);
        anvil.setHurtEntities(true);
        track(anvil, session);
    }

    private void spawnMobHorde(Location location, GameSession session) {
        for (int i = 0; i < 3; i++) {
            Location spawn = above(location).add(randomInt(-2, 3), 0.0, randomInt(-2, 3));
            spawnTracked(randomType(HOSTILE_MOBS), spawn, session);
        }
    }

    private void spawnWebTrap(Player player, GameSession session) {
        Set<Location> locations = new HashSet<>();
        Location base = player.getLocation().getBlock().getLocation();
        int[][] offsets = {{0, 0, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};
        for (int[] offset : offsets) {
            Block block = base.clone().add(offset[0], offset[1], offset[2]).getBlock();
            if (!block.getType().isAir()) continue;
            block.setType(Material.COBWEB, false);
            locations.add(block.getLocation());
        }

        TemporaryBlocks temporary = new TemporaryBlocks(session, Material.COBWEB, locations);
        activeTemporaryBlocks.add(temporary);
        Bukkit.getScheduler().runTaskLater(plugin, () -> cleanupTemporaryBlocks(temporary), 100L);
    }

    private void spawnCreeper(Location location, GameSession session) {
        Creeper creeper = (Creeper) spawnTracked(EntityType.CREEPER, above(location), session);
        creeper.setPowered(ThreadLocalRandom.current().nextBoolean());
        creeper.setFuseTicks(40);
    }

    private void spawnTntRain(Player player, Location location, GameSession session) {
        for (int i = 0; i < 4; i++) {
            spawnTnt(player, location, session, i * 8);
        }
    }

    private void spawnMiniBoss(Location location, GameSession session) {
        Ravager ravager = (Ravager) spawnTracked(EntityType.RAVAGER, above(location), session);
        ravager.setCustomName(translations.text("messages.lucky-boss-name"));
        ravager.setCustomNameVisible(true);
    }

    private Entity spawnTracked(EntityType type, Location location, GameSession session) {
        Entity entity = location.getWorld().spawnEntity(location, type);
        entity.setPersistent(false);
        track(entity, session);
        return entity;
    }

    private void track(Entity entity, GameSession session) {
        spawnedEntities.computeIfAbsent(session, ignored -> new HashSet<>()).add(entity.getUniqueId());
        long lifetime = Math.max(20L, plugin.getConfig().getLong("settings.luckyBlocks.mobDurationTicks", 400L));
        Bukkit.getScheduler().runTaskLater(plugin, () -> removeTracked(entity, session), lifetime);
    }

    private void removeTracked(Entity entity, GameSession session) {
        Set<UUID> entities = spawnedEntities.get(session);
        if (entities != null) entities.remove(entity.getUniqueId());
        luckyExplosives.remove(entity.getUniqueId());
        if (entity.isValid()) entity.remove();
    }

    private void spawnTnt(Player player, Location location, GameSession session, int extraFuseTicks) {
        Location spawn = center(location).add(randomInt(-2, 3) * 0.45, 3.0 + randomInt(0, 4), randomInt(-2, 3) * 0.45);
        TNTPrimed tnt = location.getWorld().spawn(spawn, TNTPrimed.class);
        tnt.setFuseTicks(Math.max(1, plugin.getConfig().getInt("settings.luckyBlocks.tntFuseTicks", 60) + extraFuseTicks));
        tnt.setYield(explosionPower());
        tnt.setSource(player);
        luckyExplosives.add(tnt.getUniqueId());
        track(tnt, session);
    }

    private void spawnTemporaryFluid(Location location, Material material, GameSession session) {
        Location source = location.getBlock().getLocation();
        TemporaryFluid fluid = new TemporaryFluid(session, material, new HashSet<>());
        fluid.locations().add(source);
        activeFluids.add(fluid);
        fluidBlocks.put(source, fluid);
        source.getBlock().setType(material, true);

        long duration = Math.max(20L, plugin.getConfig().getLong("settings.luckyBlocks.fluidDurationTicks", 100L));
        Bukkit.getScheduler().runTaskLater(plugin, () -> cleanupFluid(fluid), duration);
    }

    private void cleanupFluid(TemporaryFluid fluid) {
        if (!activeFluids.remove(fluid)) return;
        for (Location location : new HashSet<>(fluid.locations())) {
            fluidBlocks.remove(location, fluid);
            if (location.getBlock().getType() == fluid.material()) {
                location.getBlock().setType(Material.AIR, false);
            }
        }
    }

    private void cleanupTemporaryBlocks(TemporaryBlocks temporary) {
        if (!activeTemporaryBlocks.remove(temporary)) return;
        for (Location location : temporary.locations()) {
            if (location.getBlock().getType() == temporary.material()) {
                location.getBlock().setType(Material.AIR, false);
            }
        }
    }

    private void cleanupEndedSessions() {
        for (GameSession session : new HashSet<>(spawnedEntities.keySet())) {
            if (session.isLuckyBlocksModeActive()) continue;
            for (UUID entityId : spawnedEntities.getOrDefault(session, Set.of())) {
                Entity entity = Bukkit.getEntity(entityId);
                if (entity != null) entity.remove();
                luckyExplosives.remove(entityId);
            }
            spawnedEntities.remove(session);
            selector.forget(session);
        }
        selector.forgetEndedSessions();
        for (TemporaryFluid fluid : new HashSet<>(activeFluids)) {
            if (!fluid.session().isLuckyBlocksModeActive()) cleanupFluid(fluid);
        }
        for (TemporaryBlocks blocks : new HashSet<>(activeTemporaryBlocks)) {
            if (!blocks.session().isLuckyBlocksModeActive()) cleanupTemporaryBlocks(blocks);
        }
    }

    void cleanupAll() {
        for (Set<UUID> entityIds : spawnedEntities.values()) {
            for (UUID entityId : entityIds) {
                Entity entity = Bukkit.getEntity(entityId);
                if (entity != null) entity.remove();
            }
        }
        for (TemporaryFluid fluid : new HashSet<>(activeFluids)) cleanupFluid(fluid);
        for (TemporaryBlocks blocks : new HashSet<>(activeTemporaryBlocks)) cleanupTemporaryBlocks(blocks);
        spawnedEntities.clear();
        selector.clear();
        luckyExplosives.clear();
    }

    private float explosionPower() {
        return (float) Math.max(0.0, plugin.getConfig().getDouble("settings.luckyBlocks.explosionPower", 2.0));
    }

    private EntityType randomType(List<EntityType> types) {
        return types.get(ThreadLocalRandom.current().nextInt(types.size()));
    }

    private int randomInt(int origin, int bound) {
        return ThreadLocalRandom.current().nextInt(origin, bound);
    }

    private Location center(Location location) {
        return location.getBlock().getLocation().add(0.5, 0.5, 0.5);
    }

    private Location above(Location location) {
        return location.getBlock().getLocation().add(0.5, 1.0, 0.5);
    }

    enum LuckyCategory {
        GOOD,
        NEUTRAL,
        BAD
    }

    enum LuckyOutcome {
        RANDOM_ITEM(LuckyCategory.GOOD, "random-item", 10),
        DIAMONDS(LuckyCategory.GOOD, "diamonds", 5),
        GOLDEN_APPLE(LuckyCategory.GOOD, "golden-apple", 6),
        FULL_HEAL(LuckyCategory.GOOD, "full-heal", 7),
        REGENERATION(LuckyCategory.GOOD, "regeneration", 8),
        SPEED(LuckyCategory.GOOD, "speed", 8),
        STRENGTH(LuckyCategory.GOOD, "strength", 7),
        EXPERIENCE(LuckyCategory.GOOD, "experience", 8),
        FRIENDLY_WOLF(LuckyCategory.GOOD, "friendly-wolf", 4),
        ITEM_RAIN(LuckyCategory.GOOD, "item-rain", 3),
        JACKPOT(LuckyCategory.GOOD, "jackpot", 1),

        WATER(LuckyCategory.NEUTRAL, "water", 10),
        PASSIVE_MOB(LuckyCategory.NEUTRAL, "passive-mob", 8),
        EXTRA_LUCKY_BLOCKS(LuckyCategory.NEUTRAL, "extra-lucky-blocks", 6),
        SWAP_PLAYERS(LuckyCategory.NEUTRAL, "swap-players", 3),
        FIREWORK(LuckyCategory.NEUTRAL, "firework", 8),

        LAVA(LuckyCategory.BAD, "lava", 8),
        PRIMED_TNT(LuckyCategory.BAD, "primed-tnt", 8),
        EXPLOSION(LuckyCategory.BAD, "explosion", 7),
        LIGHTNING(LuckyCategory.BAD, "lightning", 6),
        FALLING_ANVIL(LuckyCategory.BAD, "falling-anvil", 5),
        POISON(LuckyCategory.BAD, "poison", 7),
        BLINDNESS(LuckyCategory.BAD, "blindness", 7),
        LAUNCH(LuckyCategory.BAD, "launch", 7),
        HOSTILE_MOB(LuckyCategory.BAD, "hostile-mob", 9),
        MOB_HORDE(LuckyCategory.BAD, "mob-horde", 5),
        WEB_TRAP(LuckyCategory.BAD, "web-trap", 6),
        CREEPER(LuckyCategory.BAD, "creeper", 6),
        TNT_RAIN(LuckyCategory.BAD, "tnt-rain", 1),
        MINI_BOSS(LuckyCategory.BAD, "mini-boss", 1);

        final LuckyCategory category;
        private final String id;
        final int weight;

        LuckyOutcome(LuckyCategory category, String id, int weight) {
            this.category = category;
            this.id = id;
            this.weight = weight;
        }
    }

    private static final class TemporaryFluid {
        private final GameSession session;
        private final Material material;
        private final Set<Location> locations;

        private TemporaryFluid(GameSession session, Material material, Set<Location> locations) {
            this.session = session;
            this.material = material;
            this.locations = locations;
        }

        private GameSession session() { return session; }
        private Material material() { return material; }
        private Set<Location> locations() { return locations; }
    }

    private static final class TemporaryBlocks {
        private final GameSession session;
        private final Material material;
        private final Set<Location> locations;

        private TemporaryBlocks(GameSession session, Material material, Set<Location> locations) {
            this.session = session;
            this.material = material;
            this.locations = locations;
        }

        private GameSession session() { return session; }
        private Material material() { return material; }
        private Set<Location> locations() { return locations; }
    }
}

final class LuckyBlockOutcomeSelector {
    private final JavaPlugin plugin;
    private final Map<GameSession, Map<UUID, Deque<LuckyBlockEffectService.LuckyOutcome>>> recentOutcomes =
            new HashMap<>();

    LuckyBlockOutcomeSelector(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    LuckyBlockEffectService.LuckyOutcome select(Player player, GameSession session) {
        int itemChance = boundedPercent("settings.luckyBlocks.itemChancePercent", 85);
        int good = boundedPercent("settings.luckyBlocks.categories.goodPercent", 7);
        int neutral = boundedPercent("settings.luckyBlocks.categories.neutralPercent", 3);
        int bad = boundedPercent("settings.luckyBlocks.categories.badPercent", 5);
        int total = itemChance + good + neutral + bad;
        if (total <= 0) return LuckyBlockEffectService.LuckyOutcome.RANDOM_ITEM;

        int roll = ThreadLocalRandom.current().nextInt(total);
        if (roll < itemChance) return LuckyBlockEffectService.LuckyOutcome.RANDOM_ITEM;

        LuckyBlockEffectService.LuckyCategory category = roll < itemChance + good
                ? LuckyBlockEffectService.LuckyCategory.GOOD
                : roll < itemChance + good + neutral
                        ? LuckyBlockEffectService.LuckyCategory.NEUTRAL
                        : LuckyBlockEffectService.LuckyCategory.BAD;
        Deque<LuckyBlockEffectService.LuckyOutcome> recent = recentOutcomes
                .computeIfAbsent(session, ignored -> new HashMap<>())
                .computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>());
        List<LuckyBlockEffectService.LuckyOutcome> candidates = candidates(category, recent);
        if (candidates.isEmpty()) candidates = candidates(category, new ArrayDeque<>());
        return weighted(candidates);
    }

    void remember(Player player, GameSession session, LuckyBlockEffectService.LuckyOutcome outcome) {
        int historySize = Math.max(0, Math.min(10,
                plugin.getConfig().getInt("settings.luckyBlocks.antiRepeatHistorySize", 4)));
        if (historySize == 0) return;
        Deque<LuckyBlockEffectService.LuckyOutcome> recent = recentOutcomes
                .computeIfAbsent(session, ignored -> new HashMap<>())
                .computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>());
        recent.addLast(outcome);
        while (recent.size() > historySize) recent.removeFirst();
    }

    void forget(GameSession session) {
        recentOutcomes.remove(session);
    }

    void forgetEndedSessions() {
        recentOutcomes.keySet().removeIf(session -> !session.isLuckyBlocksModeActive());
    }

    void clear() {
        recentOutcomes.clear();
    }

    private List<LuckyBlockEffectService.LuckyOutcome> candidates(
            LuckyBlockEffectService.LuckyCategory category,
            Deque<LuckyBlockEffectService.LuckyOutcome> excluded
    ) {
        List<LuckyBlockEffectService.LuckyOutcome> candidates = new ArrayList<>();
        for (LuckyBlockEffectService.LuckyOutcome outcome : LuckyBlockEffectService.LuckyOutcome.values()) {
            if (outcome != LuckyBlockEffectService.LuckyOutcome.RANDOM_ITEM
                    && outcome.category == category
                    && !excluded.contains(outcome)) {
                candidates.add(outcome);
            }
        }
        return candidates;
    }

    private LuckyBlockEffectService.LuckyOutcome weighted(List<LuckyBlockEffectService.LuckyOutcome> candidates) {
        int total = candidates.stream().mapToInt(outcome -> outcome.weight).sum();
        int roll = ThreadLocalRandom.current().nextInt(Math.max(1, total));
        for (LuckyBlockEffectService.LuckyOutcome outcome : candidates) {
            roll -= outcome.weight;
            if (roll < 0) return outcome;
        }
        return candidates.getFirst();
    }

    private int boundedPercent(String path, int fallback) {
        return Math.max(0, Math.min(100, plugin.getConfig().getInt(path, fallback)));
    }
}
