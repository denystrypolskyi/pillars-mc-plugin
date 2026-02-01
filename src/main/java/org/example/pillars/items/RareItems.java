package org.example.pillars.items;

import org.bukkit.Material;

import java.util.HashMap;
import java.util.Map;

public class RareItems {
    public static final Map<Material, Integer> ITEMS = new HashMap<>() {
        {
            put(Material.OBSIDIAN, 8);
            put(Material.SLIME_BLOCK, 5);
            put(Material.HONEY_BLOCK, 5);
            put(Material.MAGMA_BLOCK, 5);
            put(Material.SOUL_SAND, 5);
            put(Material.NETHERRACK, 10);
            put(Material.END_STONE, 8);

            put(Material.ANVIL, 3);
            put(Material.ENCHANTING_TABLE, 2);
            put(Material.BOOKSHELF, 5);
            put(Material.PISTON, 3);
            put(Material.STICKY_PISTON, 2);
            put(Material.DISPENSER, 3);
            put(Material.DROPPER, 3);
            put(Material.OBSERVER, 2);

            put(Material.SEA_LANTERN, 3);
            put(Material.GLOWSTONE, 4);
            put(Material.PRISMARINE, 5);
            put(Material.BELL, 1);

            put(Material.COBWEB, 5);
            put(Material.EXPERIENCE_BOTTLE, 5);
            put(Material.FIRE_CHARGE, 3);
            put(Material.SPLASH_POTION, 4);
            put(Material.LEAD, 2);
            put(Material.NAME_TAG, 2);

            put(Material.CROSSBOW, 3);
            put(Material.TIPPED_ARROW, 4);
            put(Material.TRIDENT, 2);
            put(Material.ENDER_PEARL, 3);

            put(Material.CHICKEN_SPAWN_EGG, 3);
            put(Material.COW_SPAWN_EGG, 3);
            put(Material.SHEEP_SPAWN_EGG, 3);
            put(Material.PIG_SPAWN_EGG, 3);
            put(Material.RABBIT_SPAWN_EGG, 2);
            put(Material.HORSE_SPAWN_EGG, 2);
            put(Material.MOOSHROOM_SPAWN_EGG, 1);
            put(Material.LLAMA_SPAWN_EGG, 1);
            put(Material.TURTLE_SPAWN_EGG, 1);
            put(Material.FOX_SPAWN_EGG, 1);
            put(Material.WOLF_SPAWN_EGG, 2);
            put(Material.CAT_SPAWN_EGG, 1);
        }
    };
}
