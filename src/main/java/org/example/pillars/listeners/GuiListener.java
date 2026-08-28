package org.example.pillars.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.example.pillars.gui.AdminArenaListMenu;
import org.example.pillars.gui.AdminArenaFloorMenu;
import org.example.pillars.gui.AdminFloorMaterialMenu;
import org.example.pillars.gui.AdminArenaSettingsMenu;
import org.example.pillars.gui.AdminConfigMenu;
import org.example.pillars.gui.AdminHubMenu;
import org.example.pillars.gui.AdminItemPoolMenu;
import org.example.pillars.gui.AdminItemSettingsMenu;
import org.example.pillars.gui.AdminLuckyBlockMenu;
import org.example.pillars.gui.ArenaMenu;

public class GuiListener implements Listener {

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Inventory menuInventory = event.getView().getTopInventory();
        if (!isPluginMenu(menuInventory)) return;

        event.setCancelled(true);
        if (event.getClickedInventory() != menuInventory) return;

        if (ArenaMenu.isArenaMenu(menuInventory)) {
            ArenaMenu menu = (ArenaMenu) menuInventory.getHolder();
            menu.handleClick(event);
        } else if (AdminHubMenu.isAdminHubMenu(menuInventory)) {
            AdminHubMenu menu = (AdminHubMenu) menuInventory.getHolder();
            menu.handleClick(event);
        } else if (AdminArenaListMenu.isAdminArenaListMenu(menuInventory)) {
            AdminArenaListMenu menu = (AdminArenaListMenu) menuInventory.getHolder();
            menu.handleClick(event);
        } else if (AdminArenaSettingsMenu.isAdminArenaSettingsMenu(menuInventory)) {
            AdminArenaSettingsMenu menu = (AdminArenaSettingsMenu) menuInventory.getHolder();
            menu.handleClick(event);
        } else if (AdminArenaFloorMenu.isAdminArenaFloorMenu(menuInventory)) {
            AdminArenaFloorMenu menu = (AdminArenaFloorMenu) menuInventory.getHolder();
            menu.handleClick(event);
        } else if (AdminFloorMaterialMenu.isAdminFloorMaterialMenu(menuInventory)) {
            AdminFloorMaterialMenu menu = (AdminFloorMaterialMenu) menuInventory.getHolder();
            menu.handleClick(event);
        } else if (AdminConfigMenu.isAdminConfigMenu(menuInventory)) {
            AdminConfigMenu menu = (AdminConfigMenu) menuInventory.getHolder();
            menu.handleClick(event);
        } else if (AdminItemSettingsMenu.isAdminItemSettingsMenu(menuInventory)) {
            AdminItemSettingsMenu menu = (AdminItemSettingsMenu) menuInventory.getHolder();
            menu.handleClick(event);
        } else if (AdminItemPoolMenu.isAdminItemPoolMenu(menuInventory)) {
            AdminItemPoolMenu menu = (AdminItemPoolMenu) menuInventory.getHolder();
            menu.handleClick(event);
        } else if (AdminLuckyBlockMenu.isAdminLuckyBlockMenu(menuInventory)) {
            AdminLuckyBlockMenu menu = (AdminLuckyBlockMenu) menuInventory.getHolder();
            menu.handleClick(event);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Inventory menuInventory = event.getView().getTopInventory();
        if (!isPluginMenu(menuInventory)) return;

        int topSize = menuInventory.getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
            event.setCancelled(true);
        }
    }

    private boolean isPluginMenu(Inventory inventory) {
        return ArenaMenu.isArenaMenu(inventory)
                || AdminHubMenu.isAdminHubMenu(inventory)
                || AdminArenaListMenu.isAdminArenaListMenu(inventory)
                || AdminArenaSettingsMenu.isAdminArenaSettingsMenu(inventory)
                || AdminArenaFloorMenu.isAdminArenaFloorMenu(inventory)
                || AdminFloorMaterialMenu.isAdminFloorMaterialMenu(inventory)
                || AdminConfigMenu.isAdminConfigMenu(inventory)
                || AdminItemSettingsMenu.isAdminItemSettingsMenu(inventory)
                || AdminItemPoolMenu.isAdminItemPoolMenu(inventory)
                || AdminLuckyBlockMenu.isAdminLuckyBlockMenu(inventory);
    }
}
