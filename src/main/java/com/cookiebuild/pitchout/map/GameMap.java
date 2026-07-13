package com.cookiebuild.pitchout.map;

import com.cookiebuild.pitchout.game.PitchoutGame;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.List;

public class GameMap {
    private final PitchoutGame game;
    private final MapTemplate template;
    private final World world;

    public GameMap(PitchoutGame game, MapTemplate template, World world) {
        this.game = game;
        this.template = template;
        this.world = world;
    }

    public PitchoutGame getGame() {
        return game;
    }

    public MapTemplate getTemplate() {
        return template;
    }

    public World getWorld() {
        return world;
    }

    public Location getWaitingLobbyLocation() {
        return template.getSpawnLocation(world);
    }

    public Location getRandomSpawnLocation() {
        List<Location> spawnLocations = template.getSpawnLocations(world);
        return spawnLocations.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(spawnLocations.size()));
    }

    public List<Location> getSpawnLocations() {
        return template.getSpawnLocations(world);
    }
}
