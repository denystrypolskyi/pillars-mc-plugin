package org.example.pillars;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.PluginCommand;
import org.bukkit.permissions.Permission;
import org.example.pillars.command.PillarsCommand;
import org.example.pillars.listeners.GameSessionPlayerListener;
import org.example.pillars.listeners.GuiListener;
import org.example.pillars.listeners.LobbyListener;
import org.example.pillars.listeners.LuckyBlockListener;
import org.example.pillars.managers.*;

public final class PillarsPlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        saveDefaultConfig();

        TranslationManager translationManager = new TranslationManager(this);
        TeleportManager teleportManager = new TeleportManager();
        ItemManager itemManager = new ItemManager(this, translationManager);
        SoundManager soundManager = new SoundManager();
        HudManager hudManager = new HudManager(translationManager);
        SpawnManager spawnManager = new SpawnManager();

        ArenaManager arenaManager = new ArenaManager(this, translationManager);
        StatsManager statsManager = new StatsManager(this, translationManager);

        PlayerManager playerManager = new PlayerManager(this, teleportManager, hudManager);

        GameSessionManager gameSessionManager = new GameSessionManager(
                this,
                hudManager,
                playerManager,
                statsManager,
                spawnManager,
                soundManager,
                teleportManager,
                itemManager,
                arenaManager
        );

        getServer().getPluginManager().registerEvents(
                new GameSessionPlayerListener(gameSessionManager),
                this
        );

        getServer().getPluginManager().registerEvents(new GuiListener(), this);
        getServer().getPluginManager().registerEvents(
                new LuckyBlockListener(this, gameSessionManager, itemManager),
                this
        );
        getServer().getPluginManager().registerEvents(
                new LobbyListener(this, arenaManager, gameSessionManager, hudManager, itemManager, playerManager),
                this
        );

        PluginCommand pillarsCommand = getCommand("pillars");
        if (pillarsCommand == null) {
            getLogger().severe(translationManager.text("logs.command-not-defined"));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        pillarsCommand.setDescription(translationManager.text("plugin.command-description"));
        pillarsCommand.setUsage(translationManager.text("plugin.command-usage"));
        setPermissionDescription(
                "pillars.forcestart",
                translationManager.text("plugin.force-start-permission-description")
        );
        setPermissionDescription(
                "pillars.admin",
                translationManager.text("plugin.admin-permission-description")
        );
        PillarsCommand commandExecutor = new PillarsCommand(arenaManager, gameSessionManager, hudManager, itemManager);
        pillarsCommand.setExecutor(commandExecutor);
        pillarsCommand.setTabCompleter(commandExecutor);
    }

    private void setPermissionDescription(String permissionName, String description) {
        Permission permission = getServer().getPluginManager().getPermission(permissionName);
        if (permission != null) {
            permission.setDescription(description);
        }
    }
}
