package com.cookiebuild.pitchout.map;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.List;
import java.util.stream.Collectors;

public class MapTemplate {
    private final String name;
    private final List<Double> waitingSpawn;
    private final List<List<Double>> spawnCoordinates;
    private final int knockbackStrength;


    public MapTemplate(String name, List<List<Double>> spawnCoordinates, List<Double> waitingSpawn, int knockbackStrength) {
        this.name = name;
        this.spawnCoordinates = spawnCoordinates;
        this.waitingSpawn = waitingSpawn;
        this.knockbackStrength = knockbackStrength;
    }

    public String getName() {
        return name;
    }

    public List<Location> getSpawnLocations(World world) {
        return spawnCoordinates.stream()
                .map(coords -> new Location(world, coords.get(0).doubleValue(), coords.get(1).doubleValue(), coords.get(2).doubleValue(), coords.get(3).floatValue(), 0))
                .collect(Collectors.toList());
    }

    public List<Double> getWaitingSpawn() {
        return waitingSpawn;
    }

    public Location getSpawnLocation(World world) {
        return new Location(world, waitingSpawn.get(0).doubleValue(), waitingSpawn.get(1).doubleValue(), waitingSpawn.get(2).doubleValue());
    }

    public int getSpawnCount() {
        return spawnCoordinates.size();
    }

    public int getKillY() {
        // killY is the Y coordinates of randomSpawns minus 2 blocks
        return (int) (spawnCoordinates.getFirst().get(1) - 2);
    }

    public int getWaitingAreaMinY() {
        // waitingAreaMinY is the Y coordinates of waitingSpawn minus 3 blocks (to avoid going to the map before the game starts)
        return (int) (waitingSpawn.get(1) - 3);
    }

    public int getKnockbackStrength() {
        return knockbackStrength;
    }
}