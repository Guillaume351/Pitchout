package com.cookiebuild.pitchout.game;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
import com.cookiebuild.cookiedough.model.Match;
import com.cookiebuild.cookiedough.model.PlayerData;
import com.cookiebuild.cookiedough.player.CookiePlayer;
import com.cookiebuild.cookiedough.player.PlayerState;
import com.cookiebuild.cookiedough.service.MatchService;
import com.cookiebuild.cookiedough.ui.CustomScoreboardManager;
import com.cookiebuild.cookiedough.utils.LocaleManager;
import com.cookiebuild.pitchout.Pitchout;
import com.cookiebuild.pitchout.map.GameMap;
import com.cookiebuild.pitchout.map.MapManager;
import com.cookiebuild.pitchout.map.MapTemplate;

import jakarta.persistence.EntityManager;

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
    private final HashMap<UUID, Integer> playerEliminationsThisMatch = new HashMap<>(); // Kills by this player
    private final HashMap<UUID, Integer> playerDeathsThisMatch = new HashMap<>(); // Times this player was eliminated
    private final HashMap<UUID, Integer> playerKnockbacksThisMatch = new HashMap<>();
    private final HashMap<UUID, Integer> playerCurrentCombo = new HashMap<>();
    private final HashMap<UUID, Integer> playerMaxComboThisMatch = new HashMap<>();
    private final Map<UUID, PitchoutMatchPerformance> matchPerformances = new HashMap<>();
    // --- End New Stats and Match Tracking Fields ---

    public PitchoutGame() {
        super("Pitchout");
        this.scoreboardManager = new CustomScoreboardManager();

        // Initialize EntityManager and services
        this.gameEntityManager = CookieDough.sessionFactory.createEntityManager();
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
            player.getPlayer().sendMessage("Error: The game world is not loaded.");
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
        super.tick();
        updateGameInfo();

        if (getState() == GameState.RUNNING) {
            checkForWinner();
        }
    }

    @Override
    public void startGame() {
        super.startGame(); // Call the base game's startGame logic first

        // Game is starting, players are already added to participantPlayerData
        if (!participantPlayerData.isEmpty()) {
            this.currentMatchInstance = matchService.startMatch("Pitchout",
                    new ArrayList<>(participantPlayerData.values()));
            if (this.currentMatchInstance != null) {
                Pitchout.getInstance().getLogger().info("Pitchout match started: " + this.currentMatchInstance.getId());
            } else {
                Pitchout.getInstance().getLogger().severe("Failed to start Pitchout match instance.");
                // Potentially set game to an error state or cancel
            }
        } else {
            Pitchout.getInstance().getLogger()
                    .warning("Pitchout game starting with no participant PlayerData recorded. Match not started.");
        }

        // Initialize performance tracking for all players
        for (UUID playerId : participantPlayerData.keySet()) {
            PlayerData playerData = participantPlayerData.get(playerId);
            PitchoutMatchPerformance perf = new PitchoutMatchPerformance(currentMatchInstance, playerData);
            matchPerformances.put(playerId, perf);
            currentMatchInstance.addPerformance(perf);
        }
    }

    public static ChatColor getColorForLives(int lives) {
        switch (lives) {
            case 5:
                return ChatColor.AQUA;
            case 4:
                return ChatColor.GREEN;
            case 3:
                return ChatColor.YELLOW;
            case 2:
                return ChatColor.GOLD; // Orange
            case 1:
                return ChatColor.RED;
            default:
                return ChatColor.GRAY; // For spectators or unexpected values
        }
    }

    private void updateGameInfo() {
        String gameState;
        String countdownInfo = "";

        if (getState() == GameState.OPEN) {
            gameState = "game.waiting_for_players";
            if (getStartTimer() > 0) {
                gameState = "";
                countdownInfo = "Starting in " + (START_DELAY_SECONDS - getStartTimer()) + "s";
            }
        } else if (getState() == GameState.RUNNING) {
            gameState = "game.running";
        } else {
            gameState = "game.ended";
        }

        for (CookiePlayer player : getPlayers()) {
            Player bukkitPlayer = player.getPlayer();
            boolean isSpectator = bukkitPlayer.getGameMode() == GameMode.SPECTATOR;

            bukkitPlayer.sendActionBar(
                    LocaleManager.getMessage(gameState, player.getPlayer().locale()) + " " + countdownInfo);

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
                    ChatColor color = getColorForLives(lives);
                    String playerInfo = color + p.getPlayer().getName() + ChatColor.RESET + ": " + lives;
                    scoreboardManager.updateScore(bukkitPlayer, playerInfo, line--);
                }
            }
        }
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
        setState(GameState.FINISHED);

        List<PlayerData> winnerPlayerDataList = new ArrayList<>();
        if (winner != null) {
            PlayerData pd = participantPlayerData.get(winner.getPlayer().getUniqueId());
            if (pd != null) {
                winnerPlayerDataList.add(pd);
            }
        }

        if (this.currentMatchInstance != null) {
            matchService.endMatch(this.currentMatchInstance, winnerPlayerDataList);
            Pitchout.getInstance().getLogger().info("Pitchout match ended: " + this.currentMatchInstance.getId());

            for (Map.Entry<UUID, PlayerData> entry : participantPlayerData.entrySet()) {
                UUID playerId = entry.getKey();
                PlayerData playerData = entry.getValue();
                boolean won = (winner != null && winner.getPlayer().getUniqueId().equals(playerId));

                int eliminations = playerEliminationsThisMatch.getOrDefault(playerId, 0);
                int deaths = playerDeathsThisMatch.getOrDefault(playerId, 0);
                int knockbacks = playerKnockbacksThisMatch.getOrDefault(playerId, 0);
                int maxCombo = playerMaxComboThisMatch.getOrDefault(playerId, 0);

                Map<String, Object> specificMetrics = new HashMap<>();
                specificMetrics.put("knockbacks", knockbacks);
                specificMetrics.put("maxCombo", maxCombo);
                // Add other Pitchout specific metrics if any

                matchService.recordPlayerPerformance(this.currentMatchInstance, playerData, eliminations, deaths,
                        0 /* assists */, specificMetrics);

                // Update aggregate stats
                // For updateStatsAfterGame: player, won, kills, deaths, knockbacks,
                // eliminations
                // We use 'eliminations' from match for both 'kills' and 'eliminations' in
                // aggregate manager.
            }
        } else {
            Pitchout.getInstance().getLogger().warning("currentMatchInstance was null during endGame for Pitchout.");
        }

        String winMessage = winner != null ? "game.win_player" : "game.draw";

        for (CookiePlayer player : getPlayers()) {
            player.getPlayer().sendMessage(LocaleManager.getMessage(winMessage, player.getPlayer().locale(),
                    winner != null ? winner.getPlayer().getName() : ""));
            player.getPlayer().sendTitle(LocaleManager.getMessage(winMessage, player.getPlayer().locale(),
                    winner != null ? winner.getPlayer().getName() : ""), null, 20, 40, 20);
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
                            p.sendTitle("", LocaleManager.getMessage("game.teleport_countdown", p.locale(),
                                    String.valueOf(timeLeft)));
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
        PitchoutMatchPerformance victimPerf = matchPerformances.get(victimId);

        if (attacker != null) {
            UUID attackerId = attacker.getPlayer().getUniqueId();
            PitchoutMatchPerformance attackerPerf = matchPerformances.get(attackerId);

            attackerPerf.incrementEliminations();
            attackerPerf.incrementKnockbacks();

            // Update combo
            int currentCombo = playerCurrentCombo.getOrDefault(attackerId, 0) + 1;
            playerCurrentCombo.put(attackerId, currentCombo);
            if (currentCombo > attackerPerf.getMaxCombo()) {
                attackerPerf.setMaxCombo(currentCombo);
            }
        } else {
            victimPerf.incrementSelfFalls();
        }

        // Reset victim's combo
        playerCurrentCombo.put(victimId, 0);

        playerLives.put(victim, 0); // Actual elimination (loss of all lives)
        victim.getPlayer().setGameMode(GameMode.SPECTATOR);
        victim.getPlayer().sendMessage(LocaleManager.getMessage("game.player_eliminated", victim.getPlayer().locale()));
        victim.getPlayer().sendTitle(LocaleManager.getMessage("game.now_spectating", victim.getPlayer().locale()), null,
                20, 40, 20);
        victim.setState(PlayerState.SPECTATING);
        checkForWinner();
    }

    // Overload for self-elimination / environmental death
    public void eliminatePlayer(CookiePlayer victim) {
        eliminatePlayer(victim, null);
    }

    public Location getRandomSpawnLocation() {
        return map.getRandomSpawnLocation();
    }

    public void handlePlayerFall(CookiePlayer player) {
        // This method is called when a player falls into the void or takes lethal fall
        // damage.
        // It should result in a death and potentially a respawn or elimination.
        if (getState() != GameState.RUNNING)
            return;

        int currentLives = getPlayerLives(player);
        if (currentLives > 0) {
            setPlayerLives(player, currentLives - 1); // Lose a life
            playerDeathsThisMatch.put(player.getPlayer().getUniqueId(),
                    playerDeathsThisMatch.getOrDefault(player.getPlayer().getUniqueId(), 0) + 1); // Record death for
                                                                                                  // match stats
            playerCurrentCombo.put(player.getPlayer().getUniqueId(), 0); // Reset combo on fall/death

            if (getPlayerLives(player) <= 0) {
                eliminatePlayer(player); // No attacker, self-elimination (fall)
            } else {
                // Resend message about lives left, and respawn
                player.getPlayer()
                        .sendMessage(ChatColor.YELLOW + "You have " + getPlayerLives(player) + " lives remaining.");
                respawnPlayer(player);
            }
        }
    }

    // New method to specifically record knockbacks that don't necessarily result in
    // elimination
    public void recordPlayerKnockback(CookiePlayer attacker, CookiePlayer victim) {
        if (attacker == null || victim == null || attacker.equals(victim) || getState() != GameState.RUNNING) {
            return;
        }

        UUID attackerId = attacker.getPlayer().getUniqueId();
        UUID victimId = victim.getPlayer().getUniqueId();

        PitchoutMatchPerformance attackerPerf = matchPerformances.get(attackerId);
        attackerPerf.incrementKnockbacks();

        // Update combo
        int currentCombo = playerCurrentCombo.getOrDefault(attackerId, 0) + 1;
        playerCurrentCombo.put(attackerId, currentCombo);
        if (currentCombo > attackerPerf.getMaxCombo()) {
            attackerPerf.setMaxCombo(currentCombo);
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
}