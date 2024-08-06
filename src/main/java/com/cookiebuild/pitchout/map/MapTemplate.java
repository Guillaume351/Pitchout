package com.cookiebuild.pitchout.map;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class MapTemplate {
    private String name;
    private List<Double> waitingSpawn;
    private List<List<Double>> spawnCoordinates;

    public MapTemplate(String name, List<List<Double>> spawnCoordinates, List<Double> waitingSpawn) {
        this.name = name;
        this.spawnCoordinates = spawnCoordinates;
        this.waitingSpawn = waitingSpawn;
    }

    public String getName() {
        return name;
    }

    public List<Location> getSpawnLocations(World world) {
        return spawnCoordinates.stream()
                .map(coords -> new Location(world, coords.get(0).doubleValue(), coords.get(1).doubleValue(), coords.get(2).doubleValue()))
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
}