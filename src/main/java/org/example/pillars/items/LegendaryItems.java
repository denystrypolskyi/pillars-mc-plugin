package org.example.pillars.items;

import org.bukkit.Material;

import java.util.HashMap;
import java.util.Map;

public class LegendaryItems {
    public static final Map<Material, Integer> ITEMS = new HashMap<>() {
        {
            put(Material.SKELETON_SPAWN_EGG, 2);
            put(Material.CREEPER_SPAWN_EGG, 2);
            put(Material.SPIDER_SPAWN_EGG, 2);
            put(Material.ENDERMAN_SPAWN_EGG, 1);
            put(Material.BLAZE_SPAWN_EGG, 1);
            put(Material.WITCH_SPAWN_EGG, 1);
            put(Material.PILLAGER_SPAWN_EGG, 1);
            put(Material.EVOKER_SPAWN_EGG, 1);
            put(Material.VINDICATOR_SPAWN_EGG, 1);
            put(Material.RAVAGER_SPAWN_EGG, 1);
            put(Material.WARDEN_SPAWN_EGG, 1);
            put(Material.GHAST_SPAWN_EGG, 1);
            put(Material.WITHER_SKELETON_SPAWN_EGG, 1);
            put(Material.SHULKER_SPAWN_EGG, 1);

            put(Material.DIAMOND_BLOCK, 1);
            put(Material.EMERALD_BLOCK, 1);
            put(Material.NETHERITE_BLOCK, 1);
            put(Material.GOLD_BLOCK, 2);
            put(Material.IRON_BLOCK, 2);
            put(Material.LAPIS_BLOCK, 2);
            put(Material.REDSTONE_BLOCK, 2);

            put(Material.BEACON, 1);
            put(Material.DRAGON_EGG, 1);
            put(Material.SPAWNER, 1);
            put(Material.END_PORTAL_FRAME, 1);
            put(Material.CONDUIT, 1);

            put(Material.ANCIENT_DEBRIS, 1);
            put(Material.RESPAWN_ANCHOR, 1);

            put(Material.ZOMBIE_SPAWN_EGG, 2);
            put(Material.ELYTRA, 1);
            put(Material.NETHERITE_SWORD, 1);
            put(Material.BOW, 1);
            put(Material.TOTEM_OF_UNDYING, 1);

            put(Material.ENCHANTED_GOLDEN_APPLE, 1);
            put(Material.WITHER_SKELETON_SKULL, 1);
            put(Material.DRAGON_HEAD, 1);
            put(Material.LIGHTNING_ROD, 1);
            put(Material.FIREWORK_ROCKET, 1);
        }
    };
}