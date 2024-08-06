package com.cookiebuild.pitchout;

import com.cookiebuild.cookiedough.game.GameManager;
import com.cookiebuild.pitchout.game.PitchoutGame;
import com.cookiebuild.pitchout.listeners.InGamePlayerListener;
import com.cookiebuild.pitchout.map.MapManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class Pitchout extends JavaPlugin {
    private static Pitchout instance;

    public static Pitchout getInstance() {
        return instance;
    }

    @Override
    public void onLoad() {
        instance = this;
    }

    public static void registerNewGame() {
        GameManager.addGame(new PitchoutGame());
    }

    @Override
    public void onEnable() {
        // Plugin startup logic
        this.getLogger().info("Pitchout plugin enabled!");


        saveResource("config.yml", false);

        MapManager.loadMapTemplates();
        MapManager.inGamePlayerListener = new InGamePlayerListener();
        registerNewGame();

        Bukkit.getPluginManager().registerEvents(MapManager.inGamePlayerListener, this);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
        this.getLogger().info("Pitchout plugin disabled!");
    }
}
