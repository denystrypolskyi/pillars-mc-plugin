package org.example.pillars.command;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.example.pillars.entities.Arena;
import org.example.pillars.gui.AdminHubMenu;
import org.example.pillars.gui.ArenaMenu;
import org.example.pillars.managers.ArenaManager;
import org.example.pillars.managers.GameSessionManager;
import org.example.pillars.managers.HudManager;
import org.example.pillars.managers.ItemManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class PillarsCommand implements CommandExecutor, TabCompleter {

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

        if (!(sender instanceof Player player)) {
            sender.sendMessage(hudManager.getTranslations().text("messages.player-only"));
            return true;
        }
        if (args.length < 1) {
            openArenaMenu(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "join" -> {
                if (args.length < 2) {
                    hudManager.sendJoinUsage(player);
                    return true;
                }

                String arenaName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
                Arena arena = arenaManager.findArena(arenaName);
                if (arena == null) {
                    hudManager.sendArenaNotFound(player, arenaName);
                    return true;
                }

                gameSessionManager.joinSession(player, arena);
            }

            case "quickjoin", "joinactive" -> {
                if (args.length != 1) {
                    hudManager.sendCommandSyntax(player, "/p quickjoin");
                    return true;
                }

                Arena arena = gameSessionManager.findQuickJoinArena();

                if (arena == null) {
                    hudManager.sendNoJoinableSession(player);
                    return true;
                }

                gameSessionManager.joinSession(player, arena);
            }

            case "leave" -> {
                if (args.length != 1) {
                    hudManager.sendCommandSyntax(player, "/p leave");
                    return true;
                }
                gameSessionManager.leaveSession(player);
            }

            case "forcestart" -> {
                if (args.length != 1) {
                    hudManager.sendCommandSyntax(player, "/p forcestart");
                    return true;
                }
                gameSessionManager.forceStartSession(player);
            }

            case "events" -> {
                if (args.length != 1) {
                    hudManager.sendCommandSyntax(player, "/p events");
                    return true;
                }
                hudManager.sendGameEventList(player);
            }

            case "event" -> {
                if (args.length < 2) {
                    hudManager.sendGameEventUsage(player);
                    return true;
                }

                if (args.length != 2) {
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
                if (args.length != 1) {
                    hudManager.sendCommandSyntax(player, "/p menu");
                    return true;
                }

                openArenaMenu(player);
            }

            case "admin" -> {
                if (args.length != 1) {
                    hudManager.sendCommandSyntax(player, "/p admin");
                    return true;
                }

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

                if (args.length < 2 || args.length > 3) {
                    hudManager.sendItemAddUsage(player);
                    return true;
                }

                String rarity = itemManager.normalizeRarity(args[1]);
                if (rarity == null) {
                    hudManager.sendUnknownRarity(player, args[1]);
                    return true;
                }

                int weight = itemManager.getDefaultWeight(rarity);

                if (args.length >= 3) {
                    try {
                        weight = Integer.parseInt(args[2]);
                    } catch (NumberFormatException ignored) {
                        hudManager.sendInvalidWeight(player, args[2]);
                        return true;
                    }

                    if (weight <= 0) {
                        hudManager.sendInvalidWeight(player, args[2]);
                        return true;
                    }
                }

                ItemStack heldItem = player.getInventory().getItemInMainHand();
                if (heldItem.getType() == Material.AIR) {
                    hudManager.sendHoldItemToConfigure(player);
                    return true;
                }

                UUID playerId = player.getUniqueId();
                Material material = heldItem.getType();
                int configuredWeight = weight;
                if (!itemManager.setCustomItemWeight(rarity, material, weight, saved -> {
                    Player currentPlayer = Bukkit.getPlayer(playerId);
                    if (currentPlayer == null) return;
                    if (saved) {
                        hudManager.sendItemConfigured(currentPlayer, material, rarity, configuredWeight);
                    } else {
                        hudManager.sendItemPoolsSaveFailed(currentPlayer);
                    }
                })) {
                    hudManager.sendItemAddUsage(player);
                }
            }

            case "itemremove" -> {
                if (!player.hasPermission("pillars.admin")) {
                    hudManager.sendNoPermission(player);
                    return true;
                }

                if (args.length < 2 || args.length > 3) {
                    hudManager.sendItemRemoveUsage(player);
                    return true;
                }

                String rarity = itemManager.normalizeRarity(args[1]);
                if (rarity == null) {
                    hudManager.sendUnknownRarity(player, args[1]);
                    return true;
                }

                Material material;
                if (args.length >= 3) {
                    material = Material.matchMaterial(args[2]);
                    if (material == null) {
                        hudManager.sendUnknownMaterial(player, args[2]);
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

                UUID playerId = player.getUniqueId();
                Material removedMaterial = material;
                if (!itemManager.removeItem(rarity, material, saved -> {
                    Player currentPlayer = Bukkit.getPlayer(playerId);
                    if (currentPlayer == null) return;
                    if (saved) {
                        hudManager.sendItemRemoved(currentPlayer, removedMaterial, rarity);
                    } else {
                        hudManager.sendItemPoolsSaveFailed(currentPlayer);
                    }
                })) {
                    hudManager.sendItemRemoveUsage(player);
                }
            }

            default -> hudManager.sendUnknownCommand(player, args[0]);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) return List.of();

        if (args.length == 1) {
            List<String> commands = new ArrayList<>(List.of(
                    "quickjoin", "join", "menu", "leave", "event", "events"
            ));
            if (player.hasPermission("pillars.forcestart")) commands.add("forcestart");
            if (player.hasPermission("pillars.admin")) {
                commands.add("admin");
                commands.add("itemadd");
                commands.add("itemremove");
            }
            return complete(args[0], commands);
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (subcommand.equals("join")) {
            Collection<String> arenas = arenaManager.getArenas().stream()
                    .map(arena -> arena.getDisplayName().replace(' ', '-'))
                    .toList();
            return args.length == 2 ? complete(args[1], arenas) : List.of();
        }

        if (subcommand.equals("event") && args.length == 2) {
            List<String> events = new ArrayList<>(List.of("next"));
            if (player.hasPermission("pillars.admin")) {
                events.addAll(List.of("smash", "cosmic", "meteor", "earthquake", "hunt", "potato", "lastbreath"));
            }
            return complete(args[1], events);
        }

        if ((subcommand.equals("itemadd") || subcommand.equals("itemremove"))
                && player.hasPermission("pillars.admin")) {
            if (args.length == 2) {
                return complete(args[1], List.of("common", "rare", "legendary"));
            }
            if (subcommand.equals("itemremove") && args.length == 3) {
                return complete(args[2], Arrays.stream(Material.values())
                        .map(material -> material.name().toLowerCase(Locale.ROOT))
                        .toList());
            }
        }

        return List.of();
    }

    private void openArenaMenu(Player player) {
        new ArenaMenu(player, arenaManager, gameSessionManager, hudManager).open();
    }

    private List<String> complete(String input, Collection<String> values) {
        String normalizedInput = input.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalizedInput))
                .sorted(Comparator.naturalOrder())
                .toList();
    }
}
