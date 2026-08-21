package org.example.pillars.command;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.example.pillars.entities.Arena;
import org.example.pillars.gui.AdminHubMenu;
import org.example.pillars.gui.ArenaMenu;
import org.example.pillars.managers.ArenaManager;
import org.example.pillars.managers.GameSessionManager;
import org.example.pillars.managers.HudManager;
import org.example.pillars.managers.ItemManager;

public class PillarsCommand implements CommandExecutor {

    private final ArenaManager arenaManager;
    private final GameSessionManager gameSessionManager;
    private final HudManager hudManager;
    private final ItemManager itemManager;

    public PillarsCommand(
            ArenaManager arenaManager,
            GameSessionManager gameSessionManager,
            HudManager hudManager,
            ItemManager itemManager
    ) {
        this.arenaManager = arenaManager;
        this.gameSessionManager = gameSessionManager;
        this.hudManager = hudManager;
        this.itemManager = itemManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        if (!(sender instanceof Player player)) return true;
        if (args.length < 1) {
            hudManager.sendCommandUsage(player);
            return true;
        }

        switch (args[0].toLowerCase()) {

            case "join" -> {
                if (args.length < 2) {
                    hudManager.sendJoinUsage(player);
                    return true;
                }

                Arena arena = arenaManager.getArena(args[1]);
                if (arena == null) {
                    hudManager.sendArenaNotFound(player);
                    return true;
                }

                gameSessionManager.joinSession(player, arena);
            }

            case "quickjoin", "joinactive" -> {
                Arena arena = gameSessionManager.findQuickJoinArena();

                if (arena == null) {
                    hudManager.sendNoJoinableSession(player);
                    return true;
                }

                gameSessionManager.joinSession(player, arena);
            }

            case "leave" -> gameSessionManager.leaveSession(player);

            case "forcestart", "forceststart" -> gameSessionManager.forceStartSession(player);

            case "event" -> {
                if (args.length < 2) {
                    hudManager.sendGameEventUsage(player);
                    return true;
                }

                if (args[1].equalsIgnoreCase("next")) {
                    gameSessionManager.showNextGameEvent(player);
                    return true;
                }

                gameSessionManager.forceStartGameEvent(player, args[1]);
            }

            case "menu" -> {
                new ArenaMenu(
                        player,
                        arenaManager,
                        gameSessionManager,
                        hudManager
                ).open();
            }

            case "admin" -> {
                if (!player.hasPermission("pillars.admin")) {
                    hudManager.sendNoPermission(player);
                    return true;
                }

                new AdminHubMenu(player, itemManager, hudManager, arenaManager, gameSessionManager).open();
            }

            case "itemadd" -> {
                if (!player.hasPermission("pillars.admin")) {
                    hudManager.sendNoPermission(player);
                    return true;
                }

                if (args.length < 2) {
                    hudManager.sendItemAddUsage(player);
                    return true;
                }

                ItemStack heldItem = player.getInventory().getItemInMainHand();
                if (heldItem.getType() == Material.AIR) {
                    hudManager.sendHoldItemToConfigure(player);
                    return true;
                }

                int weight = itemManager.getDefaultWeight(args[1]);
                if (weight <= 0) {
                    hudManager.sendItemAddUsage(player);
                    return true;
                }

                if (args.length >= 3) {
                    try {
                        weight = Integer.parseInt(args[2]);
                    } catch (NumberFormatException ignored) {
                        hudManager.sendItemAddUsage(player);
                        return true;
                    }
                }

                if (itemManager.setCustomItemWeight(args[1], heldItem.getType(), weight)) {
                    hudManager.sendItemConfigured(player, heldItem.getType(), args[1], weight);
                } else {
                    hudManager.sendItemAddUsage(player);
                }
            }

            case "itemremove" -> {
                if (!player.hasPermission("pillars.admin")) {
                    hudManager.sendNoPermission(player);
                    return true;
                }

                if (args.length < 2) {
                    hudManager.sendItemRemoveUsage(player);
                    return true;
                }

                Material material;
                if (args.length >= 3) {
                    material = Material.matchMaterial(args[2]);
                    if (material == null) {
                        hudManager.sendUnknownMaterial(player);
                        return true;
                    }
                } else {
                    ItemStack heldItem = player.getInventory().getItemInMainHand();
                    if (heldItem.getType() == Material.AIR) {
                        hudManager.sendHoldItemToConfigure(player);
                        return true;
                    }
                    material = heldItem.getType();
                }

                if (itemManager.removeItem(args[1], material)) {
                    hudManager.sendItemRemoved(player, material, args[1]);
                } else {
                    hudManager.sendItemRemoveUsage(player);
                }
            }

            default -> hudManager.sendCommandUsage(player);
        }

        return true;
    }
}
