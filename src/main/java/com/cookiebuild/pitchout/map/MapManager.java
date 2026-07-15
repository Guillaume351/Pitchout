package com.cookiebuild.pitchout.map;

import com.cookiebuild.cookiedough.CookieDough;
import com.cookiebuild.cookiedough.utils.FileUtils;
import com.cookiebuild.cookiedough.utils.ZipUtils;
import com.cookiebuild.pitchout.Pitchout;
import com.cookiebuild.pitchout.game.PitchoutGame;
import com.cookiebuild.pitchout.listeners.InGamePlayerListener;
import io.papermc.paper.math.Position;
import net.kyori.adventure.util.TriState;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.generator.ChunkGenerator;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class MapManager {
    public static InGamePlayerListener inGamePlayerListener;
    private static final Map<String, MapTemplate> mapTemplates = new HashMap<>();
    private static final Map<String, GameMap> loadedMaps = new HashMap<>();
    private static final NextMapVote nextMapVote = new NextMapVote();
    private static String lastSelectedMapName;


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
        if (!zippedMap.isFile()) {
            throw new IOException("Map archive does not exist: " + zippedMap.getAbsolutePath());
        }

        NamespacedKey worldKey = new NamespacedKey(Pitchout.getInstance(), game.getGameId().toString());
        if (Bukkit.getWorld(worldKey) != null) {
            throw new IOException("World is already loaded: " + worldKey);
        }

        File gameMapDir = getWorldDirectory(worldKey);
        if (gameMapDir.exists()) {
            FileUtils.deleteDirectory(gameMapDir);
        }
        try {
            ZipUtils.unzip(zippedMap, gameMapDir);
            removeTransientWorldFiles(gameMapDir.toPath());
        } catch (IOException exception) {
            if (gameMapDir.exists()) {
                FileUtils.deleteDirectory(gameMapDir);
            }
            throw exception;
        }

        if (!gameMapDir.exists()) {
            throw new IOException("Unzipped world folder does not exist: " + gameMapDir.getAbsolutePath());
        }

        World world;
        try {
            org.bukkit.Location forcedSpawn = template.getSpawnLocation(null);
            world = WorldCreator.ofKey(worldKey)
                    .environment(World.Environment.NORMAL)
                    .generateStructures(false)
                    // Legacy Pitchout archives do not consistently contain a
                    // usable SpawnX/Y/Z. Avoid Paper's synchronous safe-spawn scan.
                    .forcedSpawnPosition(Position.block(
                            forcedSpawn.getBlockX(), forcedSpawn.getBlockY(), forcedSpawn.getBlockZ()),
                            forcedSpawn.getYaw(), forcedSpawn.getPitch())
                    // Match spawn chunks are loaded explicitly before player teleports.
                    // Paper's generic spawn preparation otherwise blocks the server
                    // thread for several seconds for every arena world.
                    .keepSpawnLoaded(TriState.FALSE)
                    .generator(new VoidChunkGenerator())
                    .createWorld();
        } catch (RuntimeException exception) {
            if (Bukkit.getWorld(worldKey) == null) {
                FileUtils.deleteDirectory(gameMapDir);
            }
            throw new IOException("Failed to create world: " + worldKey, exception);
        }

        if (world == null) {
            FileUtils.deleteDirectory(gameMapDir);
            throw new IOException("Failed to create world: " + worldKey);
        }

        Path expectedWorldFolder = gameMapDir.toPath().toAbsolutePath().normalize();
        Path actualWorldFolder = world.getWorldFolder().toPath().toAbsolutePath().normalize();
        if (!actualWorldFolder.equals(expectedWorldFolder)) {
            if (Bukkit.unloadWorld(world, false)) {
                FileUtils.deleteDirectory(actualWorldFolder.toFile());
            }
            FileUtils.deleteDirectory(expectedWorldFolder.toFile());
            throw new IOException("Paper resolved world " + worldKey + " to " + actualWorldFolder
                    + " instead of the prepared template directory " + expectedWorldFolder);
        }

        CookieDough.getInstance().getLogger().info("Created world " + worldKey + " based on map " + mapName);
        world.setAutoSave(false);
        world.setThundering(false);
        world.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);

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

    public static boolean unloadMap(String gameUUID) {
        GameMap gameMap = loadedMaps.get(gameUUID);
        if (gameMap == null) {
            return true;
        }

        World world = gameMap.getWorld();
        File worldFolder = world.getWorldFolder();
        World loadedWorld = Bukkit.getWorld(world.getKey());
        if (loadedWorld != null) {
            if (!loadedWorld.getPlayers().isEmpty()) {
                Pitchout.getInstance().getLogger().warning("Cannot unload " + world.getKey()
                        + ": " + loadedWorld.getPlayers().size() + " player(s) are still inside");
                return false;
            }
            if (!Bukkit.unloadWorld(loadedWorld, false)) {
                Pitchout.getInstance().getLogger().warning("Paper refused to unload world " + world.getKey());
                return false;
            }
        }

        if (inGamePlayerListener != null) {
            inGamePlayerListener.removeProtectedWorld(world.getName());
        }

        try {
            FileUtils.deleteDirectory(worldFolder);
        } catch (IOException e) {
            Pitchout.getInstance().getLogger().severe("Failed to delete world directory "
                    + worldFolder.getAbsolutePath() + ": " + e.getMessage());
            return false;
        }

        loadedMaps.remove(gameUUID, gameMap);
        return true;
    }

    public static boolean unloadAllMaps() {
        boolean success = true;
        for (String gameUUID : new ArrayList<>(loadedMaps.keySet())) {
            success &= unloadMap(gameUUID);
        }
        return success;
    }

    public record MapSelection(String mapName, boolean selectedByVote) {
    }

    public static synchronized MapSelection selectMapForNextGame() {
        if (mapTemplates.isEmpty()) {
            throw new IllegalStateException("No Pitchout map templates are configured");
        }
        NextMapVote.Selection selection = nextMapVote.consume(
                new ArrayList<>(mapTemplates.keySet()), lastSelectedMapName);
        lastSelectedMapName = selection.mapName();
        return new MapSelection(selection.mapName(), selection.selectedByVote());
    }

    public static synchronized List<String> getEligibleNextMapNames() {
        return NextMapVote.eligibleMaps(new ArrayList<>(mapTemplates.keySet()), lastSelectedMapName);
    }

    public static synchronized String recordNextMapVote(UUID playerId, String requestedMap) {
        if (playerId == null || requestedMap == null) {
            return null;
        }
        return nextMapVote.vote(playerId, requestedMap, getEligibleNextMapNames());
    }

    private static File getWorldDirectory(NamespacedKey worldKey) throws IOException {
        World overworld = Bukkit.getWorld(NamespacedKey.minecraft("overworld"));
        if (overworld == null) {
            throw new IOException("The minecraft:overworld world must be loaded before Pitchout maps");
        }

        Path overworldFolder = overworld.getWorldFolder().toPath().toAbsolutePath().normalize();
        Path keyedOverworldSuffix = Path.of("dimensions", "minecraft", "overworld");
        Path levelFolder = overworldFolder;
        if (overworldFolder.endsWith(keyedOverworldSuffix)) {
            levelFolder = overworldFolder.getParent().getParent().getParent();
        }

        return levelFolder.resolve("dimensions")
                .resolve(worldKey.getNamespace())
                .resolve(worldKey.getKey())
                .toFile();
    }

    private static void removeTransientWorldFiles(Path worldDirectory) throws IOException {
        Files.deleteIfExists(worldDirectory.resolve("session.lock"));
        Files.deleteIfExists(worldDirectory.resolve("uid.dat"));
    }

    private static final class VoidChunkGenerator extends ChunkGenerator {
    }
}
