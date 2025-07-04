package com.cookiebuild.pitchout.game;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import com.cookiebuild.cookiedough.CookieDough;
import com.cookiebuild.cookiedough.dao.GenericDAOImpl;
import com.cookiebuild.cookiedough.game.Game;
import com.cookiebuild.cookiedough.game.GameManager;
import com.cookiebuild.cookiedough.game.GameState;
import com.cookiebuild.cookiedough.lobby.LobbyManager;
import com.cookiebuild.cookiedough.lobby.LobbyScoreboard;
import com.cookiebuild.cookiedough.model.Match;
import com.cookiebuild.cookiedough.model.PlayerData;
import com.cookiebuild.cookiedough.model.PlayerMatchPerformance;
import com.cookiebuild.cookiedough.player.CookiePlayer;
import com.cookiebuild.cookiedough.player.PlayerState;
import com.cookiebuild.cookiedough.service.MatchService;
import com.cookiebuild.cookiedough.service.MinigameProgressionService;
import com.cookiebuild.cookiedough.ui.CustomScoreboardManager;
import com.cookiebuild.cookiedough.utils.HibernateUtil;
import com.cookiebuild.cookiedough.utils.LocaleManager;
import com.cookiebuild.pitchout.Pitchout;
import com.cookiebuild.pitchout.map.GameMap;
import com.cookiebuild.pitchout.map.MapManager;
import com.cookiebuild.pitchout.map.MapTemplate;
import com.google.gson.JsonObject;

import jakarta.persistence.EntityManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

public class PitchoutGame extends Game {
    private static final int MAX_LIVES = 5;
    private GameMap map;
    private final HashMap<CookiePlayer, Integer> playerLives = new HashMap<>();
    private final CustomScoreboardManager scoreboardManager;

    // --- New Stats and Match Tracking Fields ---
    private EntityManager gameEntityManager;
    private MatchService matchService;
    private Match currentMatchInstance;
    private final HashMap<UUID, PlayerData> participantPlayerData = new HashMap<>();
    private final Map<UUID, PlayerMatchPerformance> matchPerformances = new HashMap<>();
    private final HashMap<UUID, Integer> playerEliminationsThisMatch = new HashMap<>(); // Kills by this player
    private final HashMap<UUID, Integer> playerDeathsThisMatch = new HashMap<>(); // Times this player was eliminated
    private final HashMap<UUID, Integer> playerKnockbacksGivenThisMatch = new HashMap<>();
    private final HashMap<UUID, Integer> playerKnockbacksReceivedThisMatch = new HashMap<>();
    private final HashMap<UUID, Integer> playerCurrentCombo = new HashMap<>();
    private final HashMap<UUID, Integer> playerMaxComboThisMatch = new HashMap<>();
    private final HashMap<UUID, Integer> playerSelfFallsThisMatch = new HashMap<>();
    // --- End New Stats and Match Tracking Fields ---

    // Quick start transition tracking
    private boolean wasInQuickStart = false;
    private int remainingTimeAtQuickStart = 0;
    private int ticksSinceQuickStart = 0;

    public PitchoutGame() {
        super("Pitchout");
        this.scoreboardManager = new CustomScoreboardManager();

        // Initialize EntityManager and services
        this.gameEntityManager = HibernateUtil.createEntityManager();
        this.matchService = new MatchService(this.gameEntityManager);

        Bukkit.getScheduler().runTask(Pitchout.getInstance(), () -> {
            try {
                String randomMapName = MapManager.getRandomMapName();
                map = MapManager.loadMapForGame(this, randomMapName);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public void registerANewGame() {
        GameManager.addGame(new PitchoutGame());
    }

    @Override
    public boolean addPlayer(CookiePlayer player) {
        if (super.addPlayer(player)) {
            // Fetch and store PlayerData
            GenericDAOImpl<PlayerData> playerDataDAO = new GenericDAOImpl<>(PlayerData.class);
            PlayerData pd = playerDataDAO.findById(player.getPlayer().getUniqueId());
            if (pd != null) {
                participantPlayerData.put(player.getPlayer().getUniqueId(), pd);
            } else {
                Pitchout.getInstance().getLogger().warning(
                        "Could not find PlayerData for " + player.getPlayer().getName()
                                + " when adding to Pitchout game.");
                super.removePlayer(player); // Rollback adding player
                return false;
            }

            playerLives.put(player, MAX_LIVES);
            teleportToGame(player);
            return true;
        } else {
            return false;
        }
    }

    @Override
    protected void teleportToGame(CookiePlayer player) {
        Location spawnLocation = map.getRandomSpawnLocation();
        World gameWorld = Bukkit.getWorld("game_maps/" + this.getGameId().toString());

        if (gameWorld == null) {
            player.getPlayer()
                    .sendMessage(LocaleManager.getMessage("pitchout.world_not_loaded", player.getPlayer().locale()));
            return;
        }

        if (getState() == GameState.OPEN) {
            spawnLocation = map.getWaitingLobbyLocation();
            spawnLocation.setWorld(gameWorld);
            player.getPlayer().teleport(spawnLocation);
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

        player.getPlayer().getInventory().addItem(bow);
        player.getPlayer().getInventory().addItem(shovel);
        player.getPlayer().getInventory().addItem(arrow);
        spawnLocation.setWorld(gameWorld);
        player.getPlayer().teleport(spawnLocation);

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
        if (isGameEnded())
            return false;
        this.addPlayer(player);
        return true;
    }

    @Override
    public void tick() {
        // Track quick start transitions
        if (inQuickStart && !wasInQuickStart) {
            // Just transitioned to quick start - set timer to continue smoothly
            int normalRemainingTime = START_DELAY_SECONDS - getStartTimer();
            remainingTimeAtQuickStart = Math.min(normalRemainingTime, QUICK_START_DELAY_SECONDS);
            wasInQuickStart = true;
            ticksSinceQuickStart = 0;
        } else if (!inQuickStart && wasInQuickStart) {
            // Transitioned out of quick start
            wasInQuickStart = false;
            remainingTimeAtQuickStart = 0;
            ticksSinceQuickStart = 0;
        } else if (inQuickStart && wasInQuickStart) {
            // Continue counting ticks since quick start
            ticksSinceQuickStart++;
        }

        super.tick();
        updateGameInfo();

        if (getState() == GameState.RUNNING) {
            checkForWinner();
        }
    }

    @Override
    public void startGame() {
        super.startGame();

        // Reset quick start tracking when game starts
        wasInQuickStart = false;
        remainingTimeAtQuickStart = 0;
        ticksSinceQuickStart = 0;

        if (!participantPlayerData.isEmpty()) {
            this.currentMatchInstance = matchService.startMatch("Pitchout",
                    new ArrayList<>(participantPlayerData.values()));
            if (this.currentMatchInstance != null) {
                Pitchout.getInstance().getLogger().info("Pitchout match started: " + this.currentMatchInstance.getId());
            } else {
                Pitchout.getInstance().getLogger().severe("Failed to start Pitchout match instance.");
            }
        } else {
            Pitchout.getInstance().getLogger()
                    .warning("Pitchout game starting with no participant PlayerData recorded. Match not started.");
        }

        // Initialize performance tracking for all players
        for (UUID playerId : participantPlayerData.keySet()) {
            PlayerData playerData = participantPlayerData.get(playerId);
            PlayerMatchPerformance perf = new PlayerMatchPerformance(currentMatchInstance, playerData);
            // Initialize game-specific metrics with empty values
            JsonObject metrics = new JsonObject();
            metrics.addProperty("eliminations", 0);
            metrics.addProperty("knockbacks", 0);
            metrics.addProperty("maxCombo", 0);
            metrics.addProperty("selfFalls", 0);
            perf.setGameSpecificMetrics(metrics.toString());
            matchPerformances.put(playerId, perf);
            currentMatchInstance.addPerformance(perf);
        }
    }

    @Override
    public void resetGame() {
        super.resetGame();
        // Reset quick start tracking
        wasInQuickStart = false;
        remainingTimeAtQuickStart = 0;
        ticksSinceQuickStart = 0;
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
        String countdownInfo = "";

        if (getState() == GameState.OPEN) {
            gameState = "game.waiting_for_players";
            if (getStartTimer() > 0) {
                gameState = "";
                int remainingTime;
                if (inQuickStart && wasInQuickStart) {
                    // Use smooth transition: countdown from where we left off
                    remainingTime = remainingTimeAtQuickStart - ticksSinceQuickStart;
                } else if (inQuickStart) {
                    // Normal quick start calculation (first tick of quick start)
                    remainingTime = QUICK_START_DELAY_SECONDS - getStartTimer();
                } else {
                    // Normal countdown
                    remainingTime = START_DELAY_SECONDS - getStartTimer();
                }
                // Ensure remaining time is never negative or zero in display
                remainingTime = Math.max(1, remainingTime);
                countdownInfo = "Starting in " + remainingTime + "s";
            }
        } else if (getState() == GameState.RUNNING) {
            gameState = "game.running";
        } else {
            gameState = "game.ended";
        }

        for (CookiePlayer player : getPlayers()) {
            Player bukkitPlayer = player.getPlayer();
            boolean isSpectator = bukkitPlayer.getGameMode() == GameMode.SPECTATOR;

            // Adventure API components for action bar
            bukkitPlayer.sendActionBar(Component.text(
                    LocaleManager.getMessage(gameState, player.getPlayer().locale()) + " " + countdownInfo));

            scoreboardManager.createScoreboard(bukkitPlayer, "Pitchout");
            scoreboardManager.updateScore(bukkitPlayer, "Game State:", 15);
            scoreboardManager.updateScore(bukkitPlayer,
                    LocaleManager.getMessage(gameState, player.getPlayer().locale()), 14);
            scoreboardManager.updateScore(bukkitPlayer, "", 13);
            scoreboardManager.updateScore(bukkitPlayer, isSpectator ? "Spectating" : "Players:", 12);

            int line = 11;
            for (CookiePlayer p : getPlayers()) {
                if (!isSpectator || p != player) {
                    int lives = playerLives.get(p);
                    String color = getColorForScoreboard(lives);
                    String playerInfo = color + p.getPlayer().getName() + ": " + lives;
                    scoreboardManager.updateScore(bukkitPlayer, playerInfo, line--);
                }
            }
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
                .filter(player -> playerLives.get(player) > 0)
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
        Pitchout.getInstance().getLogger()
                .info("Game ended. Winner: " + (winner != null ? winner.getPlayer().getName() : "None"));

        // Finalize match data
        if (this.currentMatchInstance != null) {
            List<PlayerData> winners = new ArrayList<>();
            if (winner != null) {
                PlayerData winnerData = participantPlayerData.get(winner.getPlayer().getUniqueId());
                if (winnerData != null) {
                    winners.add(winnerData);
                }
            }
            matchService.endMatch(this.currentMatchInstance, winners);
            Pitchout.getInstance().getLogger().info("Pitchout match finalized: " + this.currentMatchInstance.getId());
        }

        // Display titles and messages to all players
        for (CookiePlayer player : getPlayers()) {
            Player bukkitPlayer = player.getPlayer();
            if (winner != null) {
                if (bukkitPlayer.equals(winner.getPlayer())) {
                    sendGameTitle(bukkitPlayer, "§6§lVICTORY!", "§7You are the last one standing!");
                } else {
                    sendGameTitle(bukkitPlayer, "§c§lGAME OVER",
                            "§7" + winner.getPlayer().getName() + " won the game.");
                }
            } else {
                sendGameTitle(bukkitPlayer, "§c§lDRAW", "§7The game ended in a draw.");
            }
        }

        // Reward players
        MinigameProgressionService statsService = CookieDough.createMinigameProgressionService();
        for (UUID playerId : participantPlayerData.keySet()) {
            boolean isWinner = winner != null && winner.getPlayer().getUniqueId().equals(playerId);
            com.cookiebuild.cookiedough.model.MinigameProgression stats = statsService.getOrCreateStats(playerId,
                    MinigameProgressionService.PITCHOUT);

            int xpGained = isWinner ? 100 : 10;
            int coinsGained = isWinner ? 25 : 5;

            // Add experience to MinigameStats
            stats.addExperience(xpGained);
            statsService.saveStats(stats);

            // Add coins to PlayerData
            com.cookiebuild.cookiedough.model.PlayerData playerData = participantPlayerData.get(playerId);
            if (playerData != null) {
                playerData.addCoins(coinsGained);

                // Save PlayerData with coins update
                try {
                    gameEntityManager.getTransaction().begin();
                    gameEntityManager.merge(playerData);
                    gameEntityManager.getTransaction().commit();

                    // Invalidate lobby scoreboard cache for this player
                    LobbyScoreboard.invalidatePlayerCache(playerId);
                } catch (Exception e) {
                    if (gameEntityManager.getTransaction().isActive()) {
                        gameEntityManager.getTransaction().rollback();
                    }
                    Pitchout.getInstance().getLogger()
                            .severe("Failed to save player data for " + playerId + ": " + e.getMessage());
                }
            }

            // Send reward message to player
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                String rewardMessage;
                if (isWinner) {
                    rewardMessage = LocaleManager.getMessage("reward.victory", player.locale(), coinsGained, xpGained);
                } else {
                    rewardMessage = LocaleManager.getMessage("reward.defeat", player.locale(), coinsGained, xpGained);
                }
                player.sendMessage(rewardMessage);
            }
        }

        // Start a countdown timer
        int teleportDelay = 10; // 10 seconds delay
        new BukkitRunnable() {
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
                                            new Object[] { timeLeft })),
                                    Title.Times.times(
                                            Duration.ofMillis(250),
                                            Duration.ofMillis(500),
                                            Duration.ofMillis(250)));
                            p.showTitle(countdownTitle);
                        }
                    }
                    timeLeft--;
                } else {
                    for (UUID playerId : participantPlayerData.keySet()) {
                        Player p = Bukkit.getPlayer(playerId);
                        if (p != null && p.isOnline()) {
                            CookiePlayer cp = com.cookiebuild.cookiedough.player.PlayerManager.getPlayer(p);
                            if (cp != null)
                                LobbyManager.teleportPlayerToLobby(cp);
                        }
                    }
                    // remove all players from the game - this is already handled by
                    // super.removePlayer on disconnect/leave
                    // and players are cleared from `getPlayers()` list by `GameManager.removeGame`
                    GameManager.removeGame(PitchoutGame.this);
                    if (gameEntityManager != null && gameEntityManager.isOpen()) {
                        gameEntityManager.close();
                        Pitchout.getInstance().getLogger()
                                .info("GameEntityManager closed for Pitchout game: " + getGameId());
                    }
                    this.cancel(); // Cancel the BukkitRunnable
                }
            }
        }.runTaskTimer(Pitchout.getInstance(), 0L, 20L); // Run every second
    }

    @Override
    public void removePlayer(CookiePlayer player) {
        super.removePlayer(player);
        playerLives.remove(player);

        scoreboardManager.removeScoreboard(player.getPlayer());
        if (getState() == GameState.RUNNING) {
            checkForWinner();
        } else {
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

        if (attacker != null) {
            UUID attackerId = attacker.getPlayer().getUniqueId();

            // Increment eliminations and knockbacks
            int elims = playerEliminationsThisMatch.getOrDefault(attackerId, 0) + 1;
            playerEliminationsThisMatch.put(attackerId, elims);

            int knockbacks = playerKnockbacksGivenThisMatch.getOrDefault(attackerId, 0) + 1;
            playerKnockbacksGivenThisMatch.put(attackerId, knockbacks);
            int knockbacksReceived = playerKnockbacksReceivedThisMatch.getOrDefault(victimId, 0) + 1;
            playerKnockbacksReceivedThisMatch.put(victimId, knockbacksReceived);

            // Update combo
            int currentCombo = playerCurrentCombo.getOrDefault(attackerId, 0) + 1;
            playerCurrentCombo.put(attackerId, currentCombo);

            int maxCombo = playerMaxComboThisMatch.getOrDefault(attackerId, 0);
            if (currentCombo > maxCombo) {
                playerMaxComboThisMatch.put(attackerId, currentCombo);
            }
        } else {
            // Self fall
            int selfFalls = playerSelfFallsThisMatch.getOrDefault(victimId, 0) + 1;
            playerSelfFallsThisMatch.put(victimId, selfFalls);
        }

        // Reset victim's combo
        playerCurrentCombo.put(victimId, 0);

        // Handle actual elimination
        playerLives.put(victim, 0);
        victim.getPlayer().setGameMode(GameMode.SPECTATOR);
        victim.getPlayer().sendMessage(LocaleManager.getMessage("game.player_eliminated", victim.getPlayer().locale()));
        sendGameTitle(
                victim.getPlayer(),
                LocaleManager.getMessage("game.now_spectating", victim.getPlayer().locale()),
                null);
        victim.setState(PlayerState.SPECTATING);
        checkForWinner();
    }

    public Location getRandomSpawnLocation() {
        return map.getRandomSpawnLocation();
    }

    // New method to specifically record knockbacks that don't necessarily result in
    // elimination
    public void recordPlayerKnockback(CookiePlayer attacker, CookiePlayer victim) {
        if (attacker == null || victim == null || attacker.equals(victim) || getState() != GameState.RUNNING) {
            return;
        }

        UUID attackerId = attacker.getPlayer().getUniqueId();
        UUID victimId = victim.getPlayer().getUniqueId();

        // Increment knockbacks count
        int knockbacks = playerKnockbacksGivenThisMatch.getOrDefault(attackerId, 0) + 1;
        playerKnockbacksGivenThisMatch.put(attackerId, knockbacks);
        int knockbacksReceived = playerKnockbacksReceivedThisMatch.getOrDefault(victimId, 0) + 1;
        playerKnockbacksReceivedThisMatch.put(victimId, knockbacksReceived);

        // Update combo
        int currentCombo = playerCurrentCombo.getOrDefault(attackerId, 0) + 1;
        playerCurrentCombo.put(attackerId, currentCombo);

        int maxCombo = playerMaxComboThisMatch.getOrDefault(attackerId, 0);
        if (currentCombo > maxCombo) {
            playerMaxComboThisMatch.put(attackerId, currentCombo);
        }

        // Reset victim's combo
        playerCurrentCombo.put(victimId, 0);
    }

    private void respawnPlayer(CookiePlayer player) {
        Location spawnLocation = getRandomSpawnLocation();
        player.getPlayer().teleport(spawnLocation);
        player.getPlayer().setFallDistance(0);
    }

    public MapTemplate getTemplate() {
        return map.getTemplate();
    }

    private void sendGameTitle(Player player, String title, String subtitle) {
        Title.Times times = Title.Times.times(
                Duration.ofMillis(500), // fade in
                Duration.ofSeconds(2), // stay
                Duration.ofMillis(500) // fade out
        );

        Title gameTitle = Title.title(
                Component.text(title),
                subtitle != null ? Component.text(subtitle) : Component.empty(),
                times);

        player.showTitle(gameTitle);
    }

    private void sendLivesMessage(Player player, int lives) {
        player.sendMessage(LocaleManager.getMessage("pitchout.lives_remaining", player.locale(), lives));
    }

    /**
     * Helper method to get localized messages for Pitchout
     */
    public static String getLocalizedMessage(Player player, String key, Object... args) {
        return LocaleManager.getMessage(key, player.locale(), args);
    }
}