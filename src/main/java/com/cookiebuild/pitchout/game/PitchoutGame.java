package com.cookiebuild.pitchout.game;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
import com.cookiebuild.cookiedough.game.PlayerActivitySnapshot;
import com.cookiebuild.cookiedough.game.ReconnectableGame;
import com.cookiebuild.cookiedough.lobby.LobbyManager;
import com.cookiebuild.cookiedough.lobby.LobbyScoreboard;
import com.cookiebuild.cookiedough.model.Match;
import com.cookiebuild.cookiedough.player.CookiePlayer;
import com.cookiebuild.cookiedough.player.PlayerState;
import com.cookiebuild.cookiedough.service.MatchService;
import com.cookiebuild.cookiedough.service.MinigameProgressionService;
import com.cookiebuild.cookiedough.utils.LocaleManager;
import com.cookiebuild.pitchout.Pitchout;
import com.cookiebuild.pitchout.map.GameMap;
import com.cookiebuild.pitchout.map.MapManager;
import com.cookiebuild.pitchout.map.MapTemplate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;

public class PitchoutGame extends Game implements ReconnectableGame {
    private static final long RECONNECT_GRACE_MILLIS = Duration.ofSeconds(60).toMillis();
    private static final int MAX_LIVES = 5;
    private GameMap map;
    private final HashMap<CookiePlayer, Integer> playerLives = new HashMap<>();
    private final Map<UUID, Location> initialSpawns = new HashMap<>();
    private final Map<UUID, Long> disconnectedAt = new HashMap<>();
    private final Map<UUID, PlayerActivitySnapshot> reconnectSnapshots = new HashMap<>();
    private final PitchoutScoreboard scoreboard = new PitchoutScoreboard();
    private final PitchoutStats stats = new PitchoutStats();
    private BukkitTask endSequenceTask;
    private boolean cleanupStarted;
    private boolean outcomePersisted;
    private boolean timedOut;

    private final MatchService matchService = new MatchService(null);
    private final MinigameProgressionService progressionService = new MinigameProgressionService(null);
    private final Set<UUID> participantIds = new LinkedHashSet<>();
    private CompletableFuture<Match> matchFuture = CompletableFuture.completedFuture(null);
    private int runningSeconds;
    private final int maximumRunningSeconds;
    private boolean timeoutWarningSent;
    private boolean reconnectExpiryBatch;

    public PitchoutGame(UUID gameId, GameMap preparedMap) {
        super("Pitchout", gameId);

        this.maximumRunningSeconds = Math.max(60,
                Pitchout.getInstance().getConfig().getInt("game.maximum-running-seconds", 360));

        map = java.util.Objects.requireNonNull(preparedMap, "preparedMap");
    }

    @Override
    public void registerANewGame() {
        Pitchout.activateNextGame();
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
        if (!super.addPlayer(player)) {
            return false;
        }

        try {
            participantIds.add(playerId);
            playerLives.put(player, MAX_LIVES);
            teleportToGame(player);
            Pitchout.sendMapVotePrompt(player.getPlayer());
            return true;
        } catch (RuntimeException exception) {
            participantIds.remove(playerId);
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
            teleportPlayerSafely(player.getPlayer(), map.getWaitingLobbyLocation());
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
        teleportPlayerSafely(player.getPlayer(), spawnLocation);
        player.getPlayer().setFallDistance(0);
        player.setState(PlayerState.IN_GAME);
    }

    @Override
    public boolean supportsSpectating() {
        return true;
    }

    @Override
    protected Location spectatorDestination(CookiePlayer cookiePlayer) {
        return map == null || map.getWorld() == null
                ? null : map.getWaitingLobbyLocation().clone().add(0.0, 8.0, 0.0);
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
            expireReconnectReservations();
            runningSeconds++;
            int warningSeconds = Math.min(60, maximumRunningSeconds / 3);
            if (!timeoutWarningSent && runningSeconds >= maximumRunningSeconds - warningSeconds) {
                timeoutWarningSent = true;
                for (CookiePlayer player : getPlayers()) {
                    player.getPlayer().sendMessage(Component.text(
                            Pitchout.message(player.getPlayer(), "pitchout.timeout.warning", warningSeconds),
                            NamedTextColor.YELLOW));
                }
            }
            if (runningSeconds >= maximumRunningSeconds) {
                timedOut = true;
                endGame(timeoutWinner());
                return;
            }
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
        runningSeconds = 0;
        timeoutWarningSent = false;
        startMatchPersistence();
    }

    private void startMatchPersistence() {
        Set<UUID> snapshot = Set.copyOf(participantIds);
        matchFuture = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(Pitchout.getInstance(), () -> {
            try {
                Match match = matchService.startMatchByPlayerIds("Pitchout", snapshot);
                matchFuture.complete(match);
                Pitchout.getInstance().getLogger().info("Pitchout match started: " + match.getId());
            } catch (RuntimeException exception) {
                Pitchout.getInstance().getLogger().severe(
                        "Pitchout will continue without match telemetry: " + exception.getMessage());
                matchFuture.complete(null);
            }
        });
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
            gameState = "pitchout.status.running";
            countdownSeconds = Math.max(0, maximumRunningSeconds - runningSeconds);
        } else {
            gameState = "game.ended";
        }

        for (CookiePlayer player : getPlayers()) {
            Player bukkitPlayer = player.getPlayer();
            boolean isSpectator = bukkitPlayer.getGameMode() == GameMode.SPECTATOR;
            String localizedState = countdownSeconds == null
                    ? LocaleManager.getMessage(gameState, bukkitPlayer.locale())
                    : gameState.startsWith("pitchout.")
                            ? Pitchout.message(bukkitPlayer, gameState, formatSeconds(countdownSeconds))
                            : LocaleManager.getMessage(gameState, bukkitPlayer.locale(), countdownSeconds);

            bukkitPlayer.sendActionBar(Component.text(localizedState, NamedTextColor.YELLOW));

            List<String> lines = new ArrayList<>();
            lines.add("§6" + Pitchout.message(bukkitPlayer, "pitchout.scoreboard.state"));
            lines.add("§f" + localizedState);
            lines.add("§6" + Pitchout.message(bukkitPlayer, "pitchout.scoreboard.map",
                    MapManager.getDisplayName(
                            map.getTemplate().getName(), bukkitPlayer.locale())));
            lines.add(" ");
            lines.add(isSpectator
                    ? "§7" + Pitchout.message(bukkitPlayer, "pitchout.scoreboard.spectating")
                    : "§6" + Pitchout.message(bukkitPlayer, "pitchout.scoreboard.players"));
            for (CookiePlayer p : getPlayers()) {
                int lives = getPlayerLives(p);
                String color = getColorForScoreboard(lives);
                // Numeric hearts keep the state readable without relying on color alone.
                lines.add("§7[§f" + lives + "♥§7] " + color + p.getPlayer().getName());
            }
            scoreboard.update(bukkitPlayer, lines);
        }
    }

    private static String formatSeconds(int seconds) {
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
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

    private CookiePlayer timeoutWinner() {
        List<PitchoutTimeoutPolicy.Standing> standings = getPlayers().stream().map(player -> {
            PitchoutStats.Snapshot snapshot = stats.snapshot(player.getPlayer().getUniqueId());
            return new PitchoutTimeoutPolicy.Standing(player.getPlayer().getUniqueId(), getPlayerLives(player),
                    snapshot.eliminations(), snapshot.knockbacksGiven());
        }).toList();
        UUID winnerId = PitchoutTimeoutPolicy.winner(standings).orElse(null);
        return winnerId == null ? null : getPlayers().stream()
                .filter(player -> player.getPlayer().getUniqueId().equals(winnerId))
                .findFirst().orElse(null);
    }

    private void endGame(CookiePlayer winner) {
        if (getState() == GameState.FINISHED) {
            return;
        }

        this.setState(GameState.FINISHED);
        Pitchout.getInstance().getLogger()
                .info("Game ended. Winner: " + (winner != null ? winner.getPlayer().getName() : "None"));

        showOutcomeTitles(winner);
        if (timedOut) {
            for (CookiePlayer player : getPlayers()) {
                player.getPlayer().sendMessage(Component.text(
                        Pitchout.message(player.getPlayer(), "pitchout.timeout.result"), NamedTextColor.YELLOW));
            }
        }
        offerReplay();
        tryPersistOutcomeAndRewards(winner, true);
        scheduleEndSequence();
    }

    private void showOutcomeTitles(CookiePlayer winner) {
        for (CookiePlayer cookiePlayer : getPlayers()) {
            Player player = cookiePlayer.getPlayer();
            if (winner == null) {
                sendGameTitle(player,
                        Component.text(Pitchout.message(player, "pitchout.outcome.draw"),
                                NamedTextColor.YELLOW, TextDecoration.BOLD),
                        Component.text(Pitchout.message(player, "pitchout.outcome.draw.subtitle"),
                                NamedTextColor.GRAY));
            } else if (player.getUniqueId().equals(winner.getPlayer().getUniqueId())) {
                sendGameTitle(player,
                        Component.text(Pitchout.message(player, "pitchout.outcome.victory"),
                                NamedTextColor.GOLD, TextDecoration.BOLD),
                        Component.text(Pitchout.message(player, "pitchout.outcome.victory.subtitle"),
                                NamedTextColor.GRAY));
            } else {
                sendGameTitle(player,
                        Component.text(Pitchout.message(player, "pitchout.outcome.defeat"),
                                NamedTextColor.RED, TextDecoration.BOLD),
                        Component.text(Pitchout.message(player, "pitchout.outcome.defeat.subtitle",
                                winner.getPlayer().getName()), NamedTextColor.GRAY));
            }
        }
    }

    private void tryPersistOutcomeAndRewards(CookiePlayer winner, boolean notifyPlayers) {
        if (outcomePersisted) return;
        outcomePersisted = true;
        UUID winnerId = winner == null ? null : winner.getPlayer().getUniqueId();
        Set<UUID> players = Set.copyOf(participantIds);
        Map<UUID, RewardPlan> plans = new LinkedHashMap<>();
        List<MatchService.Performance> performances = players.stream().map(playerId -> {
            boolean won = playerId.equals(winnerId);
            RewardPlan plan = new RewardPlan(won ? 25 : 5, won ? 100 : 10, won,
                    stats.snapshot(playerId).eliminations());
            plans.put(playerId, plan);
            return createPerformance(playerId, plan.coins(), plan.xp(), false);
        }).toList();
        CompletableFuture<Match> pendingMatch = matchFuture;
        Bukkit.getScheduler().runTaskAsynchronously(Pitchout.getInstance(), () -> {
            Match durableMatch = awaitMatch(pendingMatch);
            if (durableMatch != null) {
                try {
                    matchService.completeMatchByWinnerIds(durableMatch,
                            winnerId == null ? Set.of() : Set.of(winnerId), performances);
                } catch (RuntimeException exception) {
                    Pitchout.getInstance().getLogger().severe(
                            "Could not persist Pitchout result: " + exception.getMessage());
                }
            }
            Map<UUID, RewardResult> rewards = new LinkedHashMap<>();
            for (Map.Entry<UUID, RewardPlan> entry : plans.entrySet()) {
                UUID playerId = entry.getKey();
                RewardPlan plan = entry.getValue();
                try {
                    var progression = progressionService.applyReward(playerId,
                            MinigameProgressionService.PITCHOUT, plan.xp(), plan.coins(),
                            "game:" + getGameId() + ":pitchout-reward");
                    rewards.put(playerId, new RewardResult(plan.coins(), plan.xp(), progression.getLevel(),
                            progression.getExperience(), progression.getExperienceForNextLevel(), plan.winner(), true));
                    CookieDough.getInstance().getGoalTracker().recordMatch(
                            playerId, "Pitchout", plan.winner(), plan.eliminations());
                } catch (RuntimeException exception) {
                    Pitchout.getInstance().getLogger().warning(
                            "Could not reward Pitchout player " + playerId + ": " + exception.getMessage());
                }
            }
            Bukkit.getScheduler().runTask(Pitchout.getInstance(), () -> {
                rewards.keySet().forEach(LobbyScoreboard::invalidatePlayerCache);
                if (notifyPlayers) sendRewardMessages(rewards);
            });
        });
    }

    private Match awaitMatch(CompletableFuture<Match> pendingMatch) {
        try {
            return pendingMatch.get(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            logWarning("Pitchout match start did not complete: " + exception.getMessage());
            return null;
        }
    }

    private MatchService.Performance createPerformance(
            UUID playerId, int coinsGained, int xpGained, boolean interrupted) {
        PitchoutStats.Snapshot snapshot = stats.snapshot(playerId);
        return new MatchService.Performance(playerId, snapshot.eliminations(), snapshot.deaths(), 0,
                Map.ofEntries(
                        Map.entry("eliminations", snapshot.eliminations()),
                        Map.entry("knockbacksGiven", snapshot.knockbacksGiven()),
                        Map.entry("knockbacksReceived", snapshot.knockbacksReceived()),
                        Map.entry("maxCombo", snapshot.maxCombo()),
                        Map.entry("selfFalls", snapshot.selfFalls()),
                        Map.entry("livesRemaining", participantLives(playerId)),
                        Map.entry("durationSeconds", runningSeconds),
                        Map.entry("timeout", timedOut),
                        Map.entry("coinsAwarded", coinsGained),
                        Map.entry("experienceAwarded", xpGained),
                        Map.entry("interrupted", interrupted)));
    }

    private int participantLives(UUID playerId) {
        return playerLives.entrySet().stream()
                .filter(entry -> entry.getKey().getPlayer().getUniqueId().equals(playerId))
                .mapToInt(Map.Entry::getValue).findFirst().orElse(0);
    }

    private record RewardPlan(int coins, int xp, boolean winner, int eliminations) {
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

    private void scheduleEndSequence() {
        int teleportDelay = 10; // 10 seconds delay
        endSequenceTask = new BukkitRunnable() {
            int timeLeft = teleportDelay;

            @Override
            public void run() {
                if (timeLeft > 0) {
                    for (UUID playerId : participantIds) {
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
        if (getState() == GameState.RUNNING) {
            persistInterruptedOutcome();
        }
        setState(GameState.FINISHED);
        cleanupGameResources();
    }

    private void persistInterruptedOutcome() {
        if (outcomePersisted) return;
        outcomePersisted = true;
        List<MatchService.Performance> performances = Set.copyOf(participantIds).stream()
                .map(playerId -> createPerformance(playerId, 0, 0, true)).toList();
        CompletableFuture<Match> pendingMatch = matchFuture;
        boolean flushed = BoundedAsyncFlush.runAndAwait(() -> {
            Match durableMatch = awaitMatch(pendingMatch);
            if (durableMatch == null) return;
            try {
                new MatchService(null).completeMatchByWinnerIds(durableMatch, Set.of(), performances);
            } catch (RuntimeException exception) {
                logWarning("Could not persist interrupted Pitchout match: " + exception.getMessage());
            }
        }, Duration.ofSeconds(2));
        if (!flushed) {
            logWarning("Interrupted Pitchout persistence exceeded the 2 second shutdown budget; shutdown continues");
        }
    }

    private void logWarning(String message) {
        Pitchout plugin = Pitchout.getInstance();
        if (plugin != null) plugin.getLogger().warning(message);
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

        if (MapManager.inGamePlayerListener != null) {
            MapManager.inGamePlayerListener.clearGameState(getGameId());
        }

        try {
            drainPlayersFromWorld();
        } catch (RuntimeException exception) {
            Pitchout.getInstance().getLogger().severe(
                    "Failed while draining Pitchout players; map cleanup will continue: " + exception.getMessage());
        }

        ejectSpectatorsToLobby();
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

    @Override
    public void removePlayer(CookiePlayer player) {
        removePlayer(player, "left_game");
    }

    @Override
    public synchronized void removePlayer(CookiePlayer player, String reason) {
        if (player == null || player.getPlayer() == null) return;
        UUID playerId = player.getPlayer().getUniqueId();
        if (getSpectators().stream().anyMatch(viewer ->
                viewer.getPlayer().getUniqueId().equals(playerId))) {
            super.removePlayer(player, reason);
            scoreboard.remove(player.getPlayer());
            return;
        }
        boolean wasInGame = getPlayers().contains(player);
        if (wasInGame && getState() == GameState.RUNNING && "disconnect".equalsIgnoreCase(reason)
                && getPlayerLives(player) > 0) {
            if (!disconnectedAt.containsKey(playerId)) {
                disconnectedAt.put(playerId, System.currentTimeMillis());
                reconnectSnapshots.put(playerId, PlayerActivitySnapshot.capture(player.getPlayer()));
            }
            scoreboard.remove(player.getPlayer());
            return;
        }
        super.removePlayer(player, reason);
        if (!wasInGame) {
            return;
        }
        playerLives.remove(player);
        initialSpawns.remove(playerId);
        disconnectedAt.remove(playerId);
        reconnectSnapshots.remove(playerId);

        scoreboard.remove(player.getPlayer());
        if (getState() == GameState.RUNNING && !reconnectExpiryBatch) {
            checkForWinner();
        } else if (getState() == GameState.OPEN) {
            participantIds.remove(player.getPlayer().getUniqueId());
        }
    }

    @Override
    public boolean hasReconnectReservation(UUID playerId) {
        Long disconnected = disconnectedAt.get(playerId);
        return getState() == GameState.RUNNING && disconnected != null
                && System.currentTimeMillis() - disconnected <= RECONNECT_GRACE_MILLIS
                && playerLives.entrySet().stream().anyMatch(entry ->
                        entry.getKey().getPlayer().getUniqueId().equals(playerId) && entry.getValue() > 0);
    }

    @Override
    public synchronized boolean reconnect(CookiePlayer cookiePlayer) {
        UUID playerId = cookiePlayer.getPlayer().getUniqueId();
        CookiePlayer previous = playerLives.keySet().stream()
                .filter(player -> player.getPlayer().getUniqueId().equals(playerId)).findFirst().orElse(null);
        PlayerActivitySnapshot snapshot = reconnectSnapshots.get(playerId);
        if (previous == null || snapshot == null || !hasReconnectReservation(playerId)
                || !snapshot.relocate(cookiePlayer.getPlayer(), initialSpawns.get(playerId))
                || !restorePlayerAfterReconnect(cookiePlayer)) {
            return false;
        }
        snapshot.applyState(cookiePlayer.getPlayer());
        int lives = playerLives.remove(previous);
        playerLives.put(cookiePlayer, lives);
        disconnectedAt.remove(playerId);
        reconnectSnapshots.remove(playerId);
        cookiePlayer.setState(PlayerState.IN_GAME);
        return true;
    }

    private void expireReconnectReservations() {
        long now = System.currentTimeMillis();
        List<UUID> expired = ReconnectExpiryPolicy.expired(disconnectedAt, now, RECONNECT_GRACE_MILLIS);
        if (expired.isEmpty()) return;
        reconnectExpiryBatch = true;
        try {
            for (UUID playerId : expired) {
                CookiePlayer previous = playerLives.keySet().stream()
                        .filter(player -> player.getPlayer().getUniqueId().equals(playerId)).findFirst().orElse(null);
                if (previous != null) removePlayer(previous, "reconnect_expired");
            }
        } finally {
            reconnectExpiryBatch = false;
        }
        if (getState() == GameState.RUNNING) checkForWinner();
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
