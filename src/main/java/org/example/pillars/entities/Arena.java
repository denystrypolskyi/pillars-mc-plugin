package org.example.pillars.entities;

import org.bukkit.Location;

import java.util.List;

public class Arena {
    private String worldName;
    private String displayName;
    private int itemCooldownSeconds;
    private List<Location> spawnPoints;

    public String getWorldName() {
        return worldName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getItemCooldownSeconds() {
        return itemCooldownSeconds;
    }

    public List<Location> getSpawnPoints() {
        return spawnPoints;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void setItemCooldownSeconds(int seconds) {
        this.itemCooldownSeconds = seconds;
    }

    public void setSpawnPoints(List<Location> spawnPoints) {
        this.spawnPoints = spawnPoints;
    }

    public Location getCenter() {
        if (spawnPoints == null || spawnPoints.isEmpty()) {
            throw new IllegalStateException("Arena spawnPoints is empty");
        }

        double x = 0;
        double y = 0;
        double z = 0;

        for (Location loc : spawnPoints) {
            x += loc.getX();
            y += loc.getY();
            z += loc.getZ();
        }

        int count = spawnPoints.size();
        Location base = spawnPoints.get(0);

        return new Location(
                base.getWorld(),
                x / count,
                y / count,
                z / count
        );
    }

    public Location getSpectatorCenter() {
        Location center = getCenter().clone();
        center.add(0, 5, 0);
        center.setPitch(45);
        return center;
    }
}
