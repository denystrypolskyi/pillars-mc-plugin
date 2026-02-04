package org.example.pillars;

import org.bukkit.plugin.java.JavaPlugin;
import org.example.pillars.command.PillarsCommand;
import org.example.pillars.listeners.SessionPlayerListener;
import org.example.pillars.managers.*;

public final class PillarsPlugin extends JavaPlugin {
    // TODO add duels/VIP rooms
    // TODO add GUI to select arena

    private ArenaManager arenaManager;
    private GameSessionManager gameSessionManager;
    private HudManager hudManager;
    private TeleportManager teleportManager;
    private ItemManager itemManager;
    private StatsManager statsManager;
    private PlayerManager playerManager;
    private SoundManager soundManager;

    public ArenaManager getArenaManager() {
        return this.arenaManager;
    }

    public GameSessionManager getSessionManager() {
        return this.gameSessionManager;
    }

    public HudManager getHudManager() {
        return this.hudManager;
    }

    public ItemManager getItemManager() {
        return itemManager;
    }

    public TeleportManager getTeleportManager() {
        return teleportManager;
    }

    public StatsManager getStatsManager() {
        return statsManager;
    }

    public PlayerManager getPlayerManager() {
        return playerManager;
    }

    public SoundManager getSoundManager() {
        return soundManager;
    }

    @Override
    public void onEnable() {
        // init managers
        this.arenaManager = new ArenaManager(this);
        this.gameSessionManager = new GameSessionManager(this);
        this.hudManager = new HudManager();
        this.teleportManager = new TeleportManager();
        this.itemManager = new ItemManager();
        this.statsManager = new StatsManager(this);
        this.playerManager = new PlayerManager();
        this.soundManager = new SoundManager();


        // register listeners
        getServer().getPluginManager().registerEvents(
                new SessionPlayerListener(this),
                this
        );


        // register commands
        getCommand("p").setExecutor(
                new PillarsCommand(this.arenaManager, this.gameSessionManager, this.hudManager)
        );
    }
}