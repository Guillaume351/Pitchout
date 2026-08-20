package com.cookiebuild.pitchout.map;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.List;

public class GameMap {
    private final MapTemplate template;
    private final World world;

    public GameMap(MapTemplate template, World world) {
        this.template = template;
        this.world = world;
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
