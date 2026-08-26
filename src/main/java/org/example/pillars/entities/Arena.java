package org.example.pillars.entities;

import org.bukkit.Location;
import org.bukkit.Material;
import org.example.pillars.enums.FloorShape;
import org.example.pillars.enums.ArenaGameMode;
import org.example.pillars.enums.ItemDeliveryMode;

import java.util.List;

public class Arena {
    private String configKey;
    private String worldName;
    private String displayName;
    private int itemCooldownSeconds;
    private int borderShrinkSeconds;
    private int minPlayers;
    private boolean joiningOpen = true;
    private List<Location> spawnPoints;
    private boolean floorEnabled;
    private Material floorMaterial = Material.LAVA;
    private int floorRadius = 8;
    private int floorY = 75;
    private FloorShape floorShape = FloorShape.SQUARE;
    private ItemDeliveryMode itemDeliveryMode = ItemDeliveryMode.SINGLE;
    private ArenaGameMode gameMode = ArenaGameMode.STANDARD;

    public String getWorldName() {
        return worldName;
    }

    public String getConfigKey() {
        return configKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getItemCooldownSeconds() {
        return itemCooldownSeconds;
    }

    public int getBorderShrinkSeconds() {
        return borderShrinkSeconds;
    }

    public int getMinPlayers() {
        return minPlayers;
    }

    public List<Location> getSpawnPoints() {
        return spawnPoints;
    }

    public boolean isJoiningOpen() {
        return joiningOpen;
    }

    public boolean isFloorEnabled() {
        return floorEnabled;
    }

    public Material getFloorMaterial() {
        return floorMaterial;
    }

    public int getFloorRadius() {
        return floorRadius;
    }

    public int getFloorY() {
        return floorY;
    }

    public FloorShape getFloorShape() {
        return floorShape;
    }

    public ItemDeliveryMode getItemDeliveryMode() {
        return itemDeliveryMode;
    }

    public ArenaGameMode getGameMode() {
        return gameMode;
    }

    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

    public void setConfigKey(String configKey) {
        this.configKey = configKey;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void setItemCooldownSeconds(int seconds) {
        this.itemCooldownSeconds = seconds;
    }

    public void setBorderShrinkSeconds(int seconds) {
        this.borderShrinkSeconds = seconds;
    }

    public void setMinPlayers(int minPlayers) {
        this.minPlayers = minPlayers;
    }

    public void setJoiningOpen(boolean joiningOpen) {
        this.joiningOpen = joiningOpen;
    }

    public void setSpawnPoints(List<Location> spawnPoints) {
        this.spawnPoints = spawnPoints;
    }

    public void setFloorEnabled(boolean floorEnabled) {
        this.floorEnabled = floorEnabled;
    }

    public void setFloorMaterial(Material floorMaterial) {
        this.floorMaterial = floorMaterial;
    }

    public void setFloorRadius(int floorRadius) {
        this.floorRadius = floorRadius;
    }

    public void setFloorY(int floorY) {
        this.floorY = floorY;
    }

    public void setFloorShape(FloorShape floorShape) {
        this.floorShape = floorShape;
    }

    public void setItemDeliveryMode(ItemDeliveryMode itemDeliveryMode) {
        this.itemDeliveryMode = itemDeliveryMode;
    }

    public void setGameMode(ArenaGameMode gameMode) {
        this.gameMode = gameMode;
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
