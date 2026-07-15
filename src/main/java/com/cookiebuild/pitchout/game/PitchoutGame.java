package com.cookiebuild.pitchout.game;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import com.cookiebuild.cookiedough.CookieDough;
import com.cookiebuild.cookiedough.game.Game;
import com.cookiebuild.cookiedough.game.GameManager;
import com.cookiebuild.cookiedough.game.GameState;
import com.cookiebuild.cookiedough.lobby.LobbyManager;
import com.cookiebuild.cookiedough.lobby.LobbyScoreboard;
import com.cookiebuild.cookiedough.model.CoinTransaction;
import com.cookiebuild.cookiedough.model.Match;
import com.cookiebuild.cookiedough.model.MinigameProgression;
import com.cookiebuild.cookiedough.model.MinigameProgressionId;
import com.cookiebuild.cookiedough.model.PlayerData;
import com.cookiebuild.cookiedough.model.PlayerMatchPerformance;
import com.cookiebuild.cookiedough.player.CookiePlayer;
import com.cookiebuild.cookiedough.player.PlayerState;
import com.cookiebuild.cookiedough.service.MatchService;
import com.cookiebuild.cookiedough.service.MinigameProgressionService;
import com.cookiebuild.cookiedough.utils.HibernateUtil;
import com.cookiebuild.cookiedough.utils.LocaleManager;
import com.cookiebuild.pitchout.Pitchout;
import com.cookiebuild.pitchout.map.GameMap;
import com.cookiebuild.pitchout.map.MapManager;
import com.cookiebuild.pitchout.map.MapTemplate;
import com.google.gson.JsonObject;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;

public class PitchoutGame extends Game {
    private static final int MAX_LIVES = 5;
    private GameMap map;
    private final HashMap<CookiePlayer, Integer> playerLives = new HashMap<>();
    private final Map<UUID, Location> initialSpawns = new HashMap<>();
    private final PitchoutScoreboard scoreboard = new PitchoutScoreboard();
    private final PitchoutStats stats = new PitchoutStats();
    private BukkitTask endSequenceTask;
    private boolean cleanupStarted;
    private boolean outcomePersisted;
    private boolean outcomePersistenceRequested;
    private CookiePlayer pendingWinner;

    // --- New Stats and Match Tracking Fields ---
    private EntityManager gameEntityManager;
    private MatchService matchService;
    private Match currentMatchInstance;
    private final HashMap<UUID, PlayerData> participantPlayerData = new HashMap<>();
    // --- End New Stats and Match Tracking Fields ---

    public PitchoutGame() {
        super("Pitchout");

        // Initialize EntityManager and services
        this.gameEntityManager = HibernateUtil.createEntityManager();
        this.matchService = new MatchService(this.gameEntityManager);

        Bukkit.getScheduler().runTask(Pitchout.getInstance(), () -> {
            try {
                MapManager.MapSelection mapSelection = MapManager.selectMapForNextGame();
                map = MapManager.loadMapForGame(this, mapSelection.mapName());
                if (mapSelection.selectedByVote()) {
                    Pitchout.announceMapVoteWinner(mapSelection.mapName());
                }
            } catch (IOException | RuntimeException e) {
                Pitchout.getInstance().getLogger().severe(
                        "Could not prepare Pitchout game " + getGameId() + ": " + e.getMessage());
                setState(GameState.FINISHED);
                cleanupGameResources();
            }
        });
    }

    @Override
    public void registerANewGame() {
        GameManager.addGame(new PitchoutGame());
    }

    @Override
    public boolean addPlayer(CookiePlayer player) {
        if (player == null || map == null || Bukkit.getWorld(map.getWorld().getKey()) == null) {
            if (player != null && player.getPlayer().isOnline()) {
                player.getPlayer().sendMessage(
                        LocaleManager.getMessage("pitchout.world_not_loaded", player.getPlayer().locale()));
            }
            return false;
        }

        UUID playerId = player.getPlayer().getUniqueId();
        PlayerData playerData;
        try {
            // Core admission already gates on PlayerWrapperListener readiness. A managed
            // reference avoids a synchronous SELECT on the interaction thread.
            playerData = gameEntityManager.getReference(PlayerData.class, playerId);
        } catch (RuntimeException exception) {
            Pitchout.getInstance().getLogger().warning(
                    "Could not load PlayerData for Pitchout admission: " + exception.getMessage());
            return false;
        }
        if (!super.addPlayer(player)) {
            return false;
        }

        try {
            participantPlayerData.put(playerId, playerData);
            playerLives.put(player, MAX_LIVES);
            teleportToGame(player);
            Pitchout.sendMapVotePrompt(player.getPlayer());
            return true;
        } catch (RuntimeException exception) {
            participantPlayerData.remove(playerId);
            playerLives.remove(player);
            super.removePlayer(player);
            player.setState(PlayerState.LOBBY);
            if (player.getPlayer().isOnline()) {
                LobbyManager.teleportPlayerToLobby(player);
            }
            Pitchout.getInstance().getLogger().severe(
                    "Rolled back failed Pitchout admission for " + player.getPlayer().getName() + ": "
                            + exception.getMessage());
            return false;
        }
    }

    @Override
    protected void teleportToGame(CookiePlayer player) {
        if (map == null) {
            throw new IllegalStateException("Pitchout map is not ready");
        }

        World gameWorld = map.getWorld();
        if (Bukkit.getWorld(gameWorld.getKey()) == null) {
            throw new IllegalStateException("Pitchout world is not loaded: " + gameWorld.getKey());
        }

        player.resetPlayer();
        if (getState() == GameState.OPEN) {
            player.getPlayer().teleport(map.getWaitingLobbyLocation());
            return;
        }

        // give knockback 10 shovels
        ItemStack shovel = new ItemStack(Material.WOODEN_SHOVEL);
        shovel.addUnsafeEnchantment(Enchantment.KNOCKBACK, map.getTemplate().getKnockbackStrength());

        ItemStack bow = new ItemStack(Material.BOW);
        bow.addEnchantment(Enchantment.INFINITY, 1);
        bow.addUnsafeEnchantment(Enchantment.PUNCH, map.getTemplate().getKnockbackStrength());

        // give 1 arrows (hide in inventory)
        ItemStack arrow = new ItemStack(Material.ARROW, 1);

        player.getPlayer().getInventory().setItem(0, shovel);
        player.getPlayer().getInventory().setItem(1, bow);
        player.getPlayer().getInventory().setItem(8, arrow);
        Location spawnLocation = initialSpawns.getOrDefault(
                player.getPlayer().getUniqueId(), map.getRandomSpawnLocation());
        player.getPlayer().teleport(spawnLocation);
        player.getPlayer().setFallDistance(0);
        player.setState(PlayerState.IN_GAME);
    }

    @Override
    public boolean isGameEnded() {
        return this.getState() == GameState.FINISHED;
    }

    @Override
    public int getPlayerCount() {
        return getPlayers().size();
    }

    @Override
    public boolean addPlayerToAvailableTeam(CookiePlayer player) {
        return !isGameEnded() && addPlayer(player);
    }

    @Override
    public void tick() {
        super.tick();
        updateGameInfo();

        if (getState() == GameState.RUNNING) {
            checkForWinner();
        }
    }

    @Override
    public void startGame() {
        if (map == null || Bukkit.getWorld(map.getWorld().getKey()) == null) {
            Pitchout.getInstance().getLogger().severe("Refusing to start Pitchout without a loaded map");
            return;
        }

        prepareInitialSpawns();
        super.startGame();

        if (!participantPlayerData.isEmpty()) {
            try {
                this.currentMatchInstance = matchService.startMatch("Pitchout",
                        new ArrayList<>(participantPlayerData.values()));
                if (this.currentMatchInstance != null) {
                    Pitchout.getInstance().getLogger()
                            .info("Pitchout match started: " + this.currentMatchInstance.getId());
                }
            } catch (RuntimeException exception) {
                this.currentMatchInstance = null;
                Pitchout.getInstance().getLogger().severe(
                        "Pitchout will continue without match telemetry: " + exception.getMessage());
            }
        } else {
            Pitchout.getInstance().getLogger()
                    .warning("Pitchout game starting with no participant PlayerData recorded. Match not started.");
        }

    }

    private void prepareInitialSpawns() {
        initialSpawns.clear();
        List<Location> spawns = new ArrayList<>(map.getSpawnLocations());
        Collections.shuffle(spawns);
        List<CookiePlayer> players = getPlayers();
        for (int index = 0; index < players.size(); index++) {
            initialSpawns.put(players.get(index).getPlayer().getUniqueId(),
                    spawns.get(index % spawns.size()).clone());
        }
    }

    public static net.kyori.adventure.text.format.TextColor getColorForLives(int lives) {
        return switch (lives) {
            case 5 -> net.kyori.adventure.text.format.NamedTextColor.AQUA;
            case 4 -> net.kyori.adventure.text.format.NamedTextColor.GREEN;
            case 3 -> net.kyori.adventure.text.format.NamedTextColor.YELLOW;
            case 2 -> net.kyori.adventure.text.format.NamedTextColor.GOLD; // Orange
            case 1 -> net.kyori.adventure.text.format.NamedTextColor.RED;
            default -> net.kyori.adventure.text.format.NamedTextColor.GRAY; // For spectators or unexpected values
        };
    }

    private void updateGameInfo() {
        String gameState;
        Integer countdownSeconds = null;

        if (getState() == GameState.OPEN) {
            gameState = "game.waiting_for_players";
            if (getStartTimer() > 0) {
                int delay = inQuickStart ? QUICK_START_DELAY_SECONDS : START_DELAY_SECONDS;
                countdownSeconds = Math.max(1, delay - getStartTimer());
                gameState = "game.starting_in";
            }
        } else if (getState() == GameState.RUNNING) {
            gameState = "game.running";
        } else {
            gameState = "game.ended";
        }

        for (CookiePlayer player : getPlayers()) {
            Player bukkitPlayer = player.getPlayer();
            boolean isSpectator = bukkitPlayer.getGameMode() == GameMode.SPECTATOR;
            String localizedState = countdownSeconds == null
                    ? LocaleManager.getMessage(gameState, bukkitPlayer.locale())
                    : LocaleManager.getMessage(gameState, bukkitPlayer.locale(), countdownSeconds);

            bukkitPlayer.sendActionBar(Component.text(localizedState, NamedTextColor.YELLOW));

            List<String> lines = new ArrayList<>();
            lines.add("§6Game State:");
            lines.add("§f" + localizedState);
            lines.add("§6Map: §f" + map.getTemplate().getName());
            lines.add(" ");
            lines.add(isSpectator ? "§7Spectating" : "§6Players:");
            for (CookiePlayer p : getPlayers()) {
                int lives = getPlayerLives(p);
                String color = getColorForScoreboard(lives);
                // Numeric hearts keep the state readable without relying on color alone.
                lines.add("§7[§f" + lives + "♥§7] " + color + p.getPlayer().getName());
            }
            scoreboard.update(bukkitPlayer, lines);
        }
    }

    private String getColorForScoreboard(int lives) {
        return switch (lives) {
            case 5 -> "§b"; // AQUA
            case 4 -> "§a"; // GREEN
            case 3 -> "§e"; // YELLOW
            case 2 -> "§6"; // GOLD
            case 1 -> "§c"; // RED
            default -> "§7"; // GRAY
        };
    }

    private void checkForWinner() {
        List<CookiePlayer> alivePlayers = getPlayers().stream()
                .filter(player -> getPlayerLives(player) > 0)
                .collect(Collectors.toList());

        if (alivePlayers.size() == 1) {
            endGame(alivePlayers.get(0));
        } else if (alivePlayers.isEmpty()) {
            endGame(null); // Draw
        }
    }

    private void endGame(CookiePlayer winner) {
        if (getState() == GameState.FINISHED) {
            return;
        }

        this.setState(GameState.FINISHED);
        this.pendingWinner = winner;
        this.outcomePersistenceRequested = true;
        Pitchout.getInstance().getLogger()
                .info("Game ended. Winner: " + (winner != null ? winner.getPlayer().getName() : "None"));

        showOutcomeTitles(winner);
        offerReplay();
        tryPersistOutcomeAndRewards(winner, true);
        scheduleEndSequence();
    }

    private void showOutcomeTitles(CookiePlayer winner) {
        for (CookiePlayer cookiePlayer : getPlayers()) {
            Player player = cookiePlayer.getPlayer();
            if (winner == null) {
                sendGameTitle(player,
                        Component.text("DRAW", NamedTextColor.RED, TextDecoration.BOLD),
                        Component.text("The game ended in a draw.", NamedTextColor.GRAY));
            } else if (player.getUniqueId().equals(winner.getPlayer().getUniqueId())) {
                sendGameTitle(player,
                        Component.text("VICTORY!", NamedTextColor.GOLD, TextDecoration.BOLD),
                        Component.text("You are the last one standing!", NamedTextColor.GRAY));
            } else {
                sendGameTitle(player,
                        Component.text("GAME OVER", NamedTextColor.RED, TextDecoration.BOLD),
                        Component.text(winner.getPlayer().getName() + " won the game.", NamedTextColor.GRAY));
            }
        }
    }

    private void tryPersistOutcomeAndRewards(CookiePlayer winner, boolean notifyPlayers) {
        if (outcomePersisted || gameEntityManager == null || !gameEntityManager.isOpen()) {
            return;
        }

        try {
            Map<UUID, RewardResult> rewards = persistOutcomeAndRewards(winner);
            outcomePersisted = true;
            rewards.keySet().forEach(LobbyScoreboard::invalidatePlayerCache);
            recordRetentionGoals(rewards);
            if (notifyPlayers) {
                sendRewardMessages(rewards);
            }
        } catch (RuntimeException exception) {
            Pitchout.getInstance().getLogger().severe(
                    "Pitchout outcome persistence failed; gameplay cleanup will continue and retry once: "
                            + exception.getMessage());
        }
    }

    private Map<UUID, RewardResult> persistOutcomeAndRewards(CookiePlayer winner) {
        EntityTransaction transaction = gameEntityManager.getTransaction();
        Map<UUID, RewardResult> rewards = new HashMap<>();
        UUID winnerId = winner == null ? null : winner.getPlayer().getUniqueId();

        try {
            transaction.begin();
            Match managedMatch = null;
            List<UUID> participantIds = participantPlayerData.keySet().stream().sorted().toList();
            Map<UUID, PlayerData> managedPlayers = new HashMap<>();
            if (currentMatchInstance != null) {
                managedMatch = gameEntityManager.find(Match.class, currentMatchInstance.getId());
                if (managedMatch == null) {
                    throw new IllegalStateException("Pitchout match row no longer exists");
                }
                managedMatch.setEndTime(new Date());
            }

            // Deterministic pessimistic locking prevents concurrent purchases or rewards
            // from losing a player's coin update.
            for (UUID playerId : participantIds) {
                PlayerData playerData = gameEntityManager.find(
                        PlayerData.class, playerId, LockModeType.PESSIMISTIC_WRITE);
                if (playerData == null) {
                    throw new IllegalStateException("Missing PlayerData for participant " + playerId);
                }
                managedPlayers.put(playerId, playerData);
            }

            for (UUID playerId : participantIds) {
                boolean rewardAlreadyApplied = false;
                PlayerData playerData = managedPlayers.get(playerId);
                String rewardSource = managedMatch == null
                        ? "game:" + getGameId() + ":pitchout-reward"
                        : "match:" + managedMatch.getId() + ":pitchout-reward";

                boolean winnerForPlayer = playerId.equals(winnerId);
                int coinsGained = winnerForPlayer ? 25 : 5;
                int xpGained = winnerForPlayer ? 100 : 10;
                if (managedMatch != null) {
                    PlayerMatchPerformance performance = findPerformance(managedMatch.getId(), playerId);
                    rewardAlreadyApplied = performance != null;
                    if (performance == null) {
                        performance = createPerformance(
                                managedMatch, playerData, playerId, coinsGained, xpGained, false);
                        managedMatch.addPerformance(performance);
                        gameEntityManager.persist(performance);
                    }
                }
                rewardAlreadyApplied |= findCoinTransaction(playerId, rewardSource) != null;

                MinigameProgressionId progressionId = new MinigameProgressionId(
                        playerId, MinigameProgressionService.PITCHOUT);
                MinigameProgression progression = gameEntityManager.find(MinigameProgression.class, progressionId);
                if (progression == null) {
                    progression = new MinigameProgression(playerId, MinigameProgressionService.PITCHOUT);
                    gameEntityManager.persist(progression);
                }

                // The unique match/player performance row is the durable idempotency key.
                // When match tracking was unavailable, the in-memory outcome guard is the
                // strongest guarantee the current schema permits.
                if (!rewardAlreadyApplied) {
                    progression.addExperience(xpGained);
                    playerData.addCoins(coinsGained);
                    gameEntityManager.persist(new CoinTransaction(
                            playerData, coinsGained, rewardSource, new Date()));
                }
                rewards.put(playerId, new RewardResult(
                        coinsGained,
                        xpGained,
                        progression.getLevel(),
                        progression.getExperience(),
                        progression.getExperienceForNextLevel(),
                        winnerForPlayer,
                        !rewardAlreadyApplied));
            }

            if (managedMatch != null && winnerId != null) {
                PlayerData managedWinner = managedPlayers.get(winnerId);
                if (managedWinner != null) {
                    managedMatch.getWinners().add(managedWinner);
                }
            }

            transaction.commit();
            return rewards;
        } catch (RuntimeException exception) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            gameEntityManager.clear();
            throw exception;
        }
    }

    private PlayerMatchPerformance findPerformance(UUID matchId, UUID playerId) {
        TypedQuery<PlayerMatchPerformance> query = gameEntityManager.createQuery(
                "SELECT performance FROM PlayerMatchPerformance performance "
                        + "WHERE performance.match.id = :matchId AND performance.player.id = :playerId",
                PlayerMatchPerformance.class);
        query.setParameter("matchId", matchId);
        query.setParameter("playerId", playerId);
        query.setMaxResults(1);
        return query.getResultStream().findFirst().orElse(null);
    }

    private CoinTransaction findCoinTransaction(UUID playerId, String source) {
        return gameEntityManager.createQuery(
                "SELECT coinTransaction FROM CoinTransaction coinTransaction "
                        + "WHERE coinTransaction.player.id = :playerId AND coinTransaction.source = :source",
                CoinTransaction.class)
                .setParameter("playerId", playerId)
                .setParameter("source", source)
                .setMaxResults(1)
                .getResultStream()
                .findFirst()
                .orElse(null);
    }

    private PlayerMatchPerformance createPerformance(
            Match match, PlayerData playerData, UUID playerId,
            int coinsGained, int xpGained, boolean interrupted) {
        PitchoutStats.Snapshot snapshot = stats.snapshot(playerId);
        PlayerMatchPerformance performance = new PlayerMatchPerformance(match, playerData);
        performance.setKillsInMatch(snapshot.eliminations());
        performance.setDeathsInMatch(snapshot.deaths());
        performance.setAssistsInMatch(0);

        JsonObject metrics = new JsonObject();
        metrics.addProperty("eliminations", snapshot.eliminations());
        metrics.addProperty("deaths", snapshot.deaths());
        metrics.addProperty("knockbacksGiven", snapshot.knockbacksGiven());
        metrics.addProperty("knockbacksReceived", snapshot.knockbacksReceived());
        metrics.addProperty("maxCombo", snapshot.maxCombo());
        metrics.addProperty("selfFalls", snapshot.selfFalls());
        metrics.addProperty("coinsAwarded", coinsGained);
        metrics.addProperty("experienceAwarded", xpGained);
        metrics.addProperty("interrupted", interrupted);
        performance.setGameSpecificMetrics(metrics.toString());
        return performance;
    }

    private void sendRewardMessages(Map<UUID, RewardResult> rewards) {
        for (Map.Entry<UUID, RewardResult> entry : rewards.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            RewardResult reward = entry.getValue();
            if (player == null || !player.isOnline() || !reward.newlyApplied()) {
                continue;
            }
            String key = reward.winner() ? "reward.victory" : "reward.defeat";
            player.sendMessage(LocaleManager.getMessage(
                    key, player.locale(), reward.coinsGained(), reward.xpGained()));
            player.sendMessage(Component.text(Pitchout.message(
                    player,
                    "pitchout.progress",
                    reward.level(),
                    reward.totalExperience(),
                    reward.nextLevelExperience()), NamedTextColor.AQUA));
            PitchoutStats.Snapshot contribution = stats.snapshot(entry.getKey());
            player.sendMessage(Component.text(Pitchout.message(
                    player,
                    "pitchout.contribution",
                    contribution.eliminations(),
                    contribution.knockbacksGiven(),
                    contribution.maxCombo()), NamedTextColor.GOLD));
        }
    }

    private void recordRetentionGoals(Map<UUID, RewardResult> rewards) {
        for (Map.Entry<UUID, RewardResult> entry : rewards.entrySet()) {
            RewardResult reward = entry.getValue();
            if (!reward.newlyApplied()) {
                continue;
            }
            try {
                CookieDough.getInstance().getGoalTracker().recordMatch(
                        entry.getKey(),
                        "Pitchout",
                        reward.winner(),
                        stats.snapshot(entry.getKey()).eliminations());
            } catch (RuntimeException exception) {
                Pitchout.getInstance().getLogger().warning(
                        "Could not update retention goals for " + entry.getKey() + ": "
                                + exception.getMessage());
            }
        }
    }

    private void scheduleEndSequence() {
        int teleportDelay = 10; // 10 seconds delay
        endSequenceTask = new BukkitRunnable() {
            int timeLeft = teleportDelay;

            @Override
            public void run() {
                if (timeLeft > 0) {
                    for (UUID playerId : participantPlayerData.keySet()) {
                        Player p = Bukkit.getPlayer(playerId);
                        if (p != null && p.isOnline()) {
                            Title countdownTitle = Title.title(
                                    Component.empty(),
                                    Component.text(LocaleManager.getMessage("game.teleport_countdown", p.locale(),
                                            timeLeft)),
                                    Title.Times.times(
                                            Duration.ofMillis(250),
                                            Duration.ofMillis(500),
                                            Duration.ofMillis(250)));
                            p.showTitle(countdownTitle);
                        }
                    }
                    timeLeft--;
                } else {
                    cleanupGameResources();
                }
            }
        }.runTaskTimer(Pitchout.getInstance(), 0L, 20L); // Run every second
    }

    private record RewardResult(
            int coinsGained,
            int xpGained,
            int level,
            int totalExperience,
            int nextLevelExperience,
            boolean winner,
            boolean newlyApplied) {
    }

    public void shutdown() {
        if (getState() == GameState.RUNNING && currentMatchInstance != null) {
            persistInterruptedOutcome();
        }
        setState(GameState.FINISHED);
        cleanupGameResources();
    }

    private void persistInterruptedOutcome() {
        EntityTransaction transaction = gameEntityManager.getTransaction();
        try {
            transaction.begin();
            Match managedMatch = gameEntityManager.find(Match.class, currentMatchInstance.getId());
            if (managedMatch == null) {
                throw new IllegalStateException("Pitchout match row no longer exists");
            }
            managedMatch.setEndTime(new Date());
            for (UUID playerId : participantPlayerData.keySet()) {
                if (findPerformance(managedMatch.getId(), playerId) != null) {
                    continue;
                }
                PlayerData playerData = gameEntityManager.find(PlayerData.class, playerId);
                if (playerData != null) {
                    PlayerMatchPerformance performance = createPerformance(
                            managedMatch, playerData, playerId, 0, 0, true);
                    managedMatch.addPerformance(performance);
                    gameEntityManager.persist(performance);
                }
            }
            transaction.commit();
        } catch (RuntimeException exception) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            Pitchout.getInstance().getLogger().warning(
                    "Could not persist interrupted Pitchout match: " + exception.getMessage());
        }
    }

    private void cleanupGameResources() {
        if (cleanupStarted) {
            return;
        }
        cleanupStarted = true;

        if (endSequenceTask != null) {
            endSequenceTask.cancel();
            endSequenceTask = null;
        }

        if (outcomePersistenceRequested && !outcomePersisted) {
            tryPersistOutcomeAndRewards(pendingWinner, true);
        }

        if (MapManager.inGamePlayerListener != null) {
            MapManager.inGamePlayerListener.clearGameState(getGameId());
        }

        try {
            drainPlayersFromWorld();
        } catch (RuntimeException exception) {
            Pitchout.getInstance().getLogger().severe(
                    "Failed while draining Pitchout players; map cleanup will continue: " + exception.getMessage());
        }

        try {
            if (map != null && !MapManager.unloadMap(getGameId().toString())) {
                Pitchout.getInstance().getLogger().warning(
                        "Pitchout map cleanup remains pending for game " + getGameId());
            }
        } catch (RuntimeException exception) {
            Pitchout.getInstance().getLogger().severe(
                    "Pitchout map cleanup failed for " + getGameId() + ": " + exception.getMessage());
        } finally {
            scoreboard.clear();
            stats.clear();
            GameManager.removeGame(this);
            closeEntityManager();
        }
    }

    private void drainPlayersFromWorld() {
        World fallbackWorld = Bukkit.getWorld(org.bukkit.NamespacedKey.minecraft("overworld"));
        for (CookiePlayer cookiePlayer : getPlayers()) {
            try {
                Player player = cookiePlayer.getPlayer();
                if (player.isOnline()) {
                    // LobbyManager only removes IN_GAME players. Remove spectators first so
                    // Pitchout cannot overwrite the lobby scoreboard after it is created.
                    if (cookiePlayer.getState() == PlayerState.SPECTATING && getPlayers().contains(cookiePlayer)) {
                        removePlayer(cookiePlayer);
                    }
                    try {
                        LobbyManager.teleportPlayerToLobby(cookiePlayer);
                    } catch (RuntimeException exception) {
                        Pitchout.getInstance().getLogger().warning(
                                "Lobby teleport failed for " + player.getName() + ": " + exception.getMessage());
                    }
                    if (map != null && player.getWorld().equals(map.getWorld()) && fallbackWorld != null) {
                        cookiePlayer.resetPlayer();
                        cookiePlayer.setState(PlayerState.LOBBY);
                        player.teleport(fallbackWorld.getSpawnLocation());
                    }
                    if (cookiePlayer.getState() == PlayerState.LOBBY) {
                        Pitchout.giveReplayHook(player);
                    }
                }
            } catch (RuntimeException exception) {
                Pitchout.getInstance().getLogger().warning(
                        "Failed to drain Pitchout player " + cookiePlayer.getPlayer().getName()
                                + ": " + exception.getMessage());
            } finally {
                if (getPlayers().contains(cookiePlayer)) {
                    removePlayer(cookiePlayer);
                }
            }
        }
    }

    private void closeEntityManager() {
        if (gameEntityManager != null && gameEntityManager.isOpen()) {
            EntityTransaction transaction = gameEntityManager.getTransaction();
            if (transaction.isActive()) {
                transaction.rollback();
            }
            gameEntityManager.close();
            Pitchout.getInstance().getLogger()
                    .info("GameEntityManager closed for Pitchout game: " + getGameId());
        }
    }

    @Override
    public void removePlayer(CookiePlayer player) {
        boolean wasInGame = getPlayers().contains(player);
        super.removePlayer(player);
        if (!wasInGame) {
            return;
        }
        playerLives.remove(player);
        initialSpawns.remove(player.getPlayer().getUniqueId());

        scoreboard.remove(player.getPlayer());
        if (getState() == GameState.RUNNING) {
            checkForWinner();
        } else if (getState() == GameState.OPEN) {
            participantPlayerData.remove(player.getPlayer().getUniqueId());
        }
    }

    public int getPlayerLives(CookiePlayer player) {
        return playerLives.getOrDefault(player, 0);
    }

    public void setPlayerLives(CookiePlayer player, int lives) {
        playerLives.put(player, lives);
    }

    public void eliminatePlayer(CookiePlayer victim, CookiePlayer attacker) {
        UUID victimId = victim.getPlayer().getUniqueId();
        if (getPlayerLives(victim) <= 0 || victim.getState() == PlayerState.SPECTATING) {
            return;
        }

        UUID attackerId = attacker == null ? null : attacker.getPlayer().getUniqueId();
        stats.recordElimination(victimId, attackerId);

        playerLives.put(victim, 0);
        victim.getPlayer().setGameMode(GameMode.SPECTATOR);
        victim.getPlayer().sendMessage(LocaleManager.getMessage("game.player_eliminated", victim.getPlayer().locale()));
        sendGameTitle(
                victim.getPlayer(),
                Component.text(LocaleManager.getMessage("game.now_spectating", victim.getPlayer().locale()),
                        NamedTextColor.YELLOW),
                Component.empty());
        victim.setState(PlayerState.SPECTATING);
        checkForWinner();
    }

    public Location getRandomSpawnLocation() {
        return map.getRandomSpawnLocation();
    }

    // New method to specifically record knockbacks that don't necessarily result in
    // elimination
    public void recordPlayerKnockback(CookiePlayer attacker, CookiePlayer victim) {
        if (attacker == null || victim == null || attacker.equals(victim)
                || getState() != GameState.RUNNING
                || !getPlayers().contains(attacker)
                || !getPlayers().contains(victim)
                || getPlayerLives(attacker) <= 0
                || getPlayerLives(victim) <= 0) {
            return;
        }
        stats.recordKnockback(
                attacker.getPlayer().getUniqueId(), victim.getPlayer().getUniqueId());
    }

    public void recordSelfFall(CookiePlayer player) {
        if (player != null && getState() == GameState.RUNNING && getPlayers().contains(player)) {
            stats.recordSelfFall(player.getPlayer().getUniqueId());
        }
    }

    public void respawnPlayerAfterFall(CookiePlayer cookiePlayer) {
        Player player = cookiePlayer.getPlayer();
        Location spawnLocation = getPlayerLives(cookiePlayer) > 0
                ? chooseSafestSpawn(cookiePlayer)
                : map.getRandomSpawnLocation().clone().add(0, 4, 0);
        player.teleport(spawnLocation);
        player.setVelocity(new org.bukkit.util.Vector());
        player.setFallDistance(0);
        player.setFireTicks(0);
        player.setFoodLevel(20);
        player.setSaturation(20);
        if (!player.isDead()) {
            AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
            player.setHealth(maxHealth == null ? 20.0 : maxHealth.getValue());
        }
    }

    private Location chooseSafestSpawn(CookiePlayer respawningPlayer) {
        List<Location> candidates = new ArrayList<>(map.getSpawnLocations());
        Collections.shuffle(candidates, ThreadLocalRandom.current());
        return candidates.stream()
                .max(java.util.Comparator.comparingDouble(candidate -> getPlayers().stream()
                        .filter(other -> !other.equals(respawningPlayer))
                        .filter(other -> getPlayerLives(other) > 0)
                        .map(CookiePlayer::getPlayer)
                        .filter(other -> other.getWorld().equals(candidate.getWorld()))
                        .mapToDouble(other -> other.getLocation().distanceSquared(candidate))
                        .min()
                        .orElse(Double.MAX_VALUE)))
                .orElseGet(map::getRandomSpawnLocation)
                .clone();
    }

    public MapTemplate getTemplate() {
        return map.getTemplate();
    }

    private void sendGameTitle(Player player, Component title, Component subtitle) {
        Title.Times times = Title.Times.times(
                Duration.ofMillis(500), // fade in
                Duration.ofSeconds(2), // stay
                Duration.ofMillis(500) // fade out
        );

        Title gameTitle = Title.title(
                title,
                subtitle,
                times);

        player.showTitle(gameTitle);
    }

    /**
     * Helper method to get localized messages for Pitchout
     */
    public static String getLocalizedMessage(Player player, String key, Object... args) {
        return LocaleManager.getMessage(key, player.locale(), args);
    }
}
