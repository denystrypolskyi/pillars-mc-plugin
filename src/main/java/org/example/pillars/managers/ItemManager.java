package org.example.pillars.managers;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.example.pillars.items.CommonItems;
import org.example.pillars.items.LegendaryItems;
import org.example.pillars.items.RareItems;

import java.util.Map;
import java.util.Random;

public class ItemManager {
    private final Random random = new Random();

    public ItemStack getRandomItem() {
        int roll = random.nextInt(100) + 1; // 1-100

        if (roll <= 5) { // 5% chance
            return getRandomFromMap(LegendaryItems.ITEMS);
        } else if (roll <= 20) { // next 15% chance
            return getRandomFromMap(RareItems.ITEMS);
        } else { // remaining 80% common
            return getRandomFromMap(CommonItems.ITEMS);
        }
    }

    private ItemStack getRandomFromMap(Map<Material, Integer> map) {
        int totalWeight = map.values().stream().mapToInt(i -> i).sum();
        int r = random.nextInt(totalWeight);
        int cumulative = 0;

        for (Map.Entry<Material, Integer> entry : map.entrySet()) {
            cumulative += entry.getValue();
            if (r < cumulative) {
                return new ItemStack(entry.getKey());
            }
        }
        return new ItemStack(Material.STONE);
    }

    public void giveRandomItem(Player player) {
        ItemStack item = getRandomItem();
        player.getInventory().addItem(item);
    }
}
