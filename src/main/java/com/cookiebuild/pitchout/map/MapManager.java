package com.cookiebuild.pitchout.map;

import com.cookiebuild.cookiedough.CookieDough;
import com.cookiebuild.cookiedough.utils.FileUtils;
import com.cookiebuild.cookiedough.utils.ZipUtils;
import com.cookiebuild.pitchout.Pitchout;
import com.cookiebuild.pitchout.game.PitchoutGame;
import com.cookiebuild.pitchout.listeners.InGamePlayerListener;
import io.papermc.paper.math.Position;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public class MapManager {
    private static final Logger LOGGER = Logger.getLogger(MapManager.class.getName());

    public record PreparedMap(UUID gameId, String mapName, MapTemplate template,
            NamespacedKey worldKey, File archive, File destination, boolean selectedByVote) { }
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

        String displayName = Pitchout.getInstance().getConfig().getString(
                "maps." + mapName + ".display-name", MapDisplayNames.fallback(mapName));
        return new MapTemplate(mapName, displayName, spawnCoordinates, waitingSpawn, knockbackStrength);
    }

    public static GameMap loadMapForGame(PitchoutGame game, String mapName) throws IOException {
        PreparedMap prepared = prepareIo(plan(game.getGameId(), mapName, false));
        try {
            return loadPrepared(prepared);
        } catch (IOException error) {
            discardPrepared(prepared);
            throw error;
        }
    }

    public static PreparedMap plan(UUID gameId, MapSelection selection) throws IOException {
        return plan(gameId, selection.mapName(), selection.selectedByVote());
    }

    private static PreparedMap plan(UUID gameId, String mapName, boolean selectedByVote) throws IOException {
        requirePrimaryThread("planned");
        MapTemplate template = mapTemplates.get(mapName);
        if (template == null) {
            throw new IllegalArgumentException("Map template " + mapName + " does not exist.");
        }

        File zippedMap = new File("pitchout_maps", mapName + ".zip");
        if (!zippedMap.isFile()) {
            throw new IOException("Map archive does not exist: " + zippedMap.getAbsolutePath());
        }

        NamespacedKey worldKey = new NamespacedKey(Pitchout.getInstance(), gameId.toString());
        if (Bukkit.getWorld(worldKey) != null) {
            throw new IOException("World is already loaded: " + worldKey);
        }

        File gameMapDir = getWorldDirectory(worldKey);
        return new PreparedMap(gameId, mapName, template, worldKey, zippedMap, gameMapDir, selectedByVote);
    }

    public static PreparedMap prepareIo(PreparedMap prepared) throws IOException {
        File gameMapDir = prepared.destination();
        if (gameMapDir.exists()) {
            FileUtils.deleteDirectory(gameMapDir);
        }
        try {
            ZipUtils.unzip(prepared.archive(), gameMapDir);
            removeTransientWorldFiles(gameMapDir.toPath());
            if (!gameMapDir.isDirectory()) {
                throw new IOException("Unzipped world folder does not exist: " + gameMapDir.getAbsolutePath());
            }
            return prepared;
        } catch (IOException | RuntimeException error) {
            discardPrepared(prepared);
            if (error instanceof IOException ioError) throw ioError;
            throw new IOException("Could not prepare Pitchout world", error);
        }
    }

    public static GameMap loadPrepared(PreparedMap prepared) throws IOException {
        requirePrimaryThread("loaded");
        MapTemplate template = prepared.template();
        NamespacedKey worldKey = prepared.worldKey();
        File gameMapDir = prepared.destination();
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
                    .generator(new VoidChunkGenerator())
                    .createWorld();
        } catch (RuntimeException exception) {
            World partial = Bukkit.getWorld(worldKey);
            if (partial != null && partial.getPlayers().isEmpty()) Bukkit.unloadWorld(partial, false);
            throw new IOException("Failed to create world: " + worldKey, exception);
        }

        if (world == null) {
            throw new IOException("Failed to create world: " + worldKey);
        }

        Path expectedWorldFolder = gameMapDir.toPath().toAbsolutePath().normalize();
        Path actualWorldFolder = world.getWorldFolder().toPath().toAbsolutePath().normalize();
        if (!actualWorldFolder.equals(expectedWorldFolder)) {
            Bukkit.unloadWorld(world, false);
            throw new IOException("Paper resolved world " + worldKey + " to " + actualWorldFolder
                    + " instead of the prepared template directory " + expectedWorldFolder);
        }

        try {
            CookieDough.getInstance().getLogger().info(
                    "Created world " + worldKey + " based on map " + prepared.mapName());
            world.setAutoSave(false);
            world.setThundering(false);
            world.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);

            GameMap gameMap = new GameMap(template, world);
            loadedMaps.put(prepared.gameId().toString(), gameMap);
            if (inGamePlayerListener != null) {
                inGamePlayerListener.addProtectedWorld(world.getName());
            }
            return gameMap;
        } catch (RuntimeException error) {
            loadedMaps.remove(prepared.gameId().toString());
            if (inGamePlayerListener != null) {
                inGamePlayerListener.removeProtectedWorld(world.getName());
            }
            if (world.getPlayers().isEmpty()) Bukkit.unloadWorld(world, false);
            throw new IOException("Failed to initialize world: " + worldKey, error);
        }
    }

    public static void discardPrepared(PreparedMap prepared) {
        try {
            if (prepared.destination().exists()) FileUtils.deleteDirectory(prepared.destination());
        } catch (IOException error) {
            LOGGER.warning("Could not delete prepared Pitchout files: " + error.getMessage());
        }
    }

    /** Unload a world registered by loadPrepared when game construction fails; filesystem cleanup stays async. */
    public static boolean discardLoadedWorld(UUID gameId) {
        GameMap map = loadedMaps.get(gameId.toString());
        if (map == null) return true;
        World world = Bukkit.getWorld(map.getWorld().getKey());
        if (world != null && (!world.getPlayers().isEmpty() || !Bukkit.unloadWorld(world, false))) return false;
        loadedMaps.remove(gameId.toString(), map);
        if (inGamePlayerListener != null) {
            inGamePlayerListener.removeProtectedWorld(map.getWorld().getName());
        }
        return true;
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

    private static void requirePrimaryThread(String action) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Pitchout worlds must be " + action + " on the server thread");
        }
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

    public static synchronized String getDisplayName(String mapName) {
        MapTemplate template = mapTemplates.get(mapName);
        return template == null ? MapDisplayNames.fallback(mapName) : template.getDisplayName();
    }

    public static synchronized String getDisplayName(String mapName, Locale locale) {
        return MapDisplayNames.localized(mapName, getDisplayName(mapName), locale);
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
