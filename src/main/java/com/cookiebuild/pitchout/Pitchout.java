package com.cookiebuild.pitchout;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import com.cookiebuild.cookiedough.game.Game;
import com.cookiebuild.cookiedough.game.GameManager;
import com.cookiebuild.pitchout.game.PitchoutGame;
import com.cookiebuild.pitchout.listeners.InGamePlayerListener;
import com.cookiebuild.pitchout.map.MapManager;

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
        instance = this;

        // Initialize maps
        this.getLogger().info("Pitchout plugin enabled!");

        saveDefaultConfig();

        MapManager.loadMapTemplates();
        MapManager.inGamePlayerListener = new InGamePlayerListener();
        registerNewGame();

        Bukkit.getPluginManager().registerEvents(MapManager.inGamePlayerListener, this);
    }

    @Override
    public void onDisable() {
        for (Game game : new java.util.ArrayList<>(GameManager.getGames())) {
            if (game instanceof PitchoutGame pitchoutGame) {
                pitchoutGame.shutdown();
            }
        }
        if (!MapManager.unloadAllMaps()) {
            this.getLogger().warning("Some Pitchout map directories could not be cleaned up during shutdown");
        }
        this.getLogger().info("Pitchout plugin disabled!");
    }
}
