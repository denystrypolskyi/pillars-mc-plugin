package org.example.pillars.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.example.pillars.gui.AdminArenaListMenu;
import org.example.pillars.gui.AdminArenaFloorMenu;
import org.example.pillars.gui.AdminFloorMaterialMenu;
import org.example.pillars.gui.AdminArenaSettingsMenu;
import org.example.pillars.gui.AdminConfigMenu;
import org.example.pillars.gui.AdminHubMenu;
import org.example.pillars.gui.AdminItemPoolMenu;
import org.example.pillars.gui.ArenaMenu;

public class GuiListener implements Listener {

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (event.getClickedInventory() == null) return;

        if (ArenaMenu.isArenaMenu(event.getClickedInventory())) {
            ArenaMenu menu = (ArenaMenu) event.getClickedInventory().getHolder();
            menu.handleClick(event);
        } else if (AdminHubMenu.isAdminHubMenu(event.getClickedInventory())) {
            AdminHubMenu menu = (AdminHubMenu) event.getClickedInventory().getHolder();
            menu.handleClick(event);
        } else if (AdminArenaListMenu.isAdminArenaListMenu(event.getClickedInventory())) {
            AdminArenaListMenu menu = (AdminArenaListMenu) event.getClickedInventory().getHolder();
            menu.handleClick(event);
        } else if (AdminArenaSettingsMenu.isAdminArenaSettingsMenu(event.getClickedInventory())) {
            AdminArenaSettingsMenu menu = (AdminArenaSettingsMenu) event.getClickedInventory().getHolder();
            menu.handleClick(event);
        } else if (AdminArenaFloorMenu.isAdminArenaFloorMenu(event.getClickedInventory())) {
            AdminArenaFloorMenu menu = (AdminArenaFloorMenu) event.getClickedInventory().getHolder();
            menu.handleClick(event);
        } else if (AdminFloorMaterialMenu.isAdminFloorMaterialMenu(event.getClickedInventory())) {
            AdminFloorMaterialMenu menu = (AdminFloorMaterialMenu) event.getClickedInventory().getHolder();
            menu.handleClick(event);
        } else if (AdminConfigMenu.isAdminConfigMenu(event.getClickedInventory())) {
            AdminConfigMenu menu = (AdminConfigMenu) event.getClickedInventory().getHolder();
            menu.handleClick(event);
        } else if (AdminItemPoolMenu.isAdminItemPoolMenu(event.getClickedInventory())) {
            AdminItemPoolMenu menu = (AdminItemPoolMenu) event.getClickedInventory().getHolder();
            menu.handleClick(event);
        }
    }
}
