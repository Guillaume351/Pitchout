package com.cookiebuild.pitchout.map;

import com.cookiebuild.cookiedough.CookieDough;
import com.cookiebuild.cookiedough.utils.FileUtils;
import com.cookiebuild.cookiedough.utils.ZipUtils;
import com.cookiebuild.pitchout.Pitchout;
import com.cookiebuild.pitchout.game.PitchoutGame;
import com.cookiebuild.pitchout.listeners.InGamePlayerListener;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.generator.ChunkGenerator;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class MapManager {
    public static InGamePlayerListener inGamePlayerListener;
    private static final Map<String, MapTemplate> mapTemplates = new HashMap<>();
    private static final Map<String, GameMap> loadedMaps = new HashMap<>();


    public static void loadMapTemplates() {
        Pitchout.getInstance().getLogger().info("Loading map templates...");

        ConfigurationSection mapsSection = Pitchout.getInstance().getConfig().getConfigurationSection("maps");
        if (mapsSection == null) {
            Pitchout.getInstance().getLogger().warning("No maps found in configuration!");
            return;
        }

        for (String mapName : mapsSection.getKeys(false)) {
            MapTemplate template = initializeMapTemplate(mapName);
            if (template != null) {
                mapTemplates.put(mapName, template);
                Pitchout.getInstance().getLogger().info("Loaded template for map " + mapName + " with " + template.getSpawnCount() + " spawn points");
            }
        }
    }

    private static MapTemplate initializeMapTemplate(String mapName) {
        List<?> rawSpawnCoordinates = Pitchout.getInstance().getConfig().getList("maps." + mapName + ".spawns");
        if (rawSpawnCoordinates == null || rawSpawnCoordinates.isEmpty()) {
            Pitchout.getInstance().getLogger().warning("Failed to load spawn coordinates for map " + mapName);
            return null;
        }

        List<List<Double>> spawnCoordinates = new ArrayList<>();
        for (Object rawCoord : rawSpawnCoordinates) {
            if (rawCoord instanceof List<?> coordList) {
                List<Double> doubleCoordList = new ArrayList<>();
                for (Object coord : coordList) {
                    if (coord instanceof Number) {
                        doubleCoordList.add(((Number) coord).doubleValue());
                    } else {
                        Pitchout.getInstance().getLogger().warning("Invalid coordinate value for map " + mapName);
                        return null;
                    }
                }
                spawnCoordinates.add(doubleCoordList);
            } else {
                Pitchout.getInstance().getLogger().warning("Invalid spawn coordinate format for map " + mapName);
                return null;
            }
        }

        List<?> rawWaitingSpawn = Pitchout.getInstance().getConfig().getList("maps." + mapName + ".waiting-spawn");
        if (rawWaitingSpawn == null || rawWaitingSpawn.isEmpty()) {
            Pitchout.getInstance().getLogger().warning("Failed to load waiting spawn coordinates for map " + mapName);
            return null;
        }

        List<Double> waitingSpawn = new ArrayList<>();
        for (Object coord : rawWaitingSpawn) {
            if (coord instanceof Number) {
                waitingSpawn.add(((Number) coord).doubleValue());
            } else {
                Pitchout.getInstance().getLogger().warning("Invalid waiting spawn coordinate value for map " + mapName);
                return null;
            }
        }

        int knockbackStrength = Pitchout.getInstance().getConfig().getInt("maps." + mapName + ".knockback-strength");

        return new MapTemplate(mapName, spawnCoordinates, waitingSpawn, knockbackStrength);
    }

    public static GameMap loadMapForGame(PitchoutGame game, String mapName) throws IOException {
        MapTemplate template = mapTemplates.get(mapName);
        if (template == null) {
            throw new IllegalArgumentException("Map template " + mapName + " does not exist.");
        }

        File zippedMap = new File("pitchout_maps", mapName + ".zip");
        File gameMapDir = new File("game_maps", game.getGameId().toString());
        ZipUtils.unzip(zippedMap, gameMapDir);

        if (!gameMapDir.exists()) {
            throw new IOException("Unzipped world folder does not exist: " + gameMapDir.getAbsolutePath());
        }

        String worldName = game.getGameId().toString();
        World world = new WorldCreator(gameMapDir.getPath())
                .environment(World.Environment.NORMAL)
                .generateStructures(false)
                .generator(new VoidChunkGenerator())
                .createWorld();

        if (world == null) {
            throw new IOException("Failed to create world: " + worldName);
        }

        CookieDough.getInstance().getLogger().info("Created world " + world.getName() + " based on map " + mapName);
        world.setAutoSave(false);
        world.setThundering(false);
        world.setGameRuleValue("announceAdvancements", "false");

        // Copy map data to the newly created world
        File worldFolder = world.getWorldFolder();
        FileUtils.copyDirectory(gameMapDir, worldFolder);

        // Create the game map with the world loaded
        GameMap gameMap = new GameMap(game,template, world);
        loadedMaps.put(game.getGameId().toString(), gameMap);

        if (inGamePlayerListener != null) {
            inGamePlayerListener.addProtectedWorld(world.getName());
        }

        return gameMap;
    }

    public static GameMap getLoadedMap(String gameUUID) {
        return loadedMaps.get(gameUUID);
    }

    public static void unloadMap(String gameUUID) {
        GameMap gameMap = loadedMaps.remove(gameUUID);
        if (gameMap != null) {
            World world = gameMap.getWorld();
            if (world != null) {
                Bukkit.unloadWorld(world, false);
            }
            File worldFolder = new File("game_maps", gameUUID);
            try {
                FileUtils.deleteDirectory(worldFolder);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            if (inGamePlayerListener != null) {
                inGamePlayerListener.removeProtectedWorld(world.getName());
            }
        }
    }

    public static String getRandomMapName() {
        return mapTemplates.keySet().toArray(new String[0])[new Random().nextInt(mapTemplates.size())];
    }

    private static class VoidChunkGenerator extends ChunkGenerator {
        @Override
        public ChunkData generateChunkData(World world, Random random, int x, int z, BiomeGrid biome) {
            return createChunkData(world);
        }
    }
}