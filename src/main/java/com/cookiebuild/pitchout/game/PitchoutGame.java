package com.cookiebuild.pitchout.game;

import com.cookiebuild.cookiedough.game.Game;
import com.cookiebuild.cookiedough.game.GameManager;
import com.cookiebuild.cookiedough.game.GameState;
import com.cookiebuild.cookiedough.lobby.LobbyManager;
import com.cookiebuild.cookiedough.player.CookiePlayer;
import com.cookiebuild.cookiedough.ui.CustomScoreboardManager;
import com.cookiebuild.cookiedough.utils.LocaleManager;
import com.cookiebuild.pitchout.Pitchout;
import com.cookiebuild.pitchout.map.GameMap;
import com.cookiebuild.pitchout.map.MapManager;
import com.cookiebuild.pitchout.map.MapTemplate;
import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;


public class PitchoutGame extends Game {
    private static final int MAX_LIVES = 5;
    private GameMap map;
    private final HashMap<CookiePlayer, Integer> playerLives = new HashMap<>();
    private final CustomScoreboardManager scoreboardManager;

    public PitchoutGame() {
        super("Pitchout");
        this.scoreboardManager = new CustomScoreboardManager();

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
            playerLives.put(player, MAX_LIVES);
            teleportToGame(player);
        } else {
            return false;
        }
        return true;
    }

    @Override
    protected void teleportToGame(CookiePlayer player) {
        Location spawnLocation = map.getRandomSpawnLocation();
        World gameWorld = Bukkit.getWorld("game_maps/" + this.getGameId().toString());

        if (gameWorld == null) {
            player.getPlayer().sendMessage("Error: The game world is not loaded.");
            return;
        }

        if(getState() == GameState.OPEN) {
            spawnLocation = map.getWaitingLobbyLocation();
            spawnLocation.setWorld(gameWorld);
            player.getPlayer().teleport(spawnLocation);
            return;
        }

        // give knockback 10 shovels
        ItemStack shovel = new ItemStack(Material.WOODEN_SHOVEL);
        shovel.addUnsafeEnchantment(Enchantment.KNOCKBACK, 5);

        ItemStack bow = new ItemStack(Material.BOW);
        bow.addUnsafeEnchantment(Enchantment.PUNCH, 5);

        // give 64 arrows
        ItemStack arrow = new ItemStack(Material.ARROW, 64);


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
        if (isGameEnded()) return false;
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

            bukkitPlayer.sendActionBar(LocaleManager.getMessage(gameState, player.getPlayer().locale()) + " " + countdownInfo);

            scoreboardManager.createScoreboard(bukkitPlayer, "Pitchout");
            scoreboardManager.updateScore(bukkitPlayer, "Game State:", 15);
            scoreboardManager.updateScore(bukkitPlayer, LocaleManager.getMessage(gameState, player.getPlayer().locale()), 14);
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

        String winMessage = winner != null ? "game.win_player" : "game.draw";

        for (CookiePlayer player : getPlayers()) {
            player.getPlayer().sendMessage(LocaleManager.getMessage(winMessage, player.getPlayer().locale(), winner != null ? winner.getPlayer().getName() : ""));
            player.getPlayer().sendTitle(LocaleManager.getMessage(winMessage, player.getPlayer().locale(), winner != null ? winner.getPlayer().getName() : ""), null, 20, 40, 20);
        }

        // Start a countdown timer
        int teleportDelay = 10; // 10 seconds delay
        Bukkit.getScheduler().runTaskTimer(Pitchout.getInstance(), new Runnable() {
            int timeLeft = teleportDelay;

            @Override
            public void run() {
                if (timeLeft > 0) {
                    for (CookiePlayer player : getPlayers()) {
                        player.getPlayer().sendTitle("", LocaleManager.getMessage("game.teleport_countdown", player.getPlayer().locale(), String.valueOf(timeLeft)));
                    }
                    timeLeft--;
                } else {
                    for (CookiePlayer player : getPlayers()) {
                        LobbyManager.teleportPlayerToLobby(player);
                    }
                    GameManager.removeGame(PitchoutGame.this);
                    Bukkit.getScheduler().cancelTasks(Pitchout.getInstance());
                }
            }
        }, 0L, 20L); // Run every second
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

    public void eliminatePlayer(CookiePlayer player) {
        playerLives.put(player, 0);
        player.getPlayer().setGameMode(GameMode.SPECTATOR);
        player.getPlayer().sendMessage(LocaleManager.getMessage("game.player_eliminated", player.getPlayer().locale()));
        player.getPlayer().sendTitle(LocaleManager.getMessage("game.now_spectating", player.getPlayer().locale()), null, 20, 40, 20);
        checkForWinner();
    }

    public Location getRandomSpawnLocation() {
        return map.getRandomSpawnLocation();
    }

    public void handlePlayerFall(CookiePlayer player) {
        // TODO: use this function to handle fall
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