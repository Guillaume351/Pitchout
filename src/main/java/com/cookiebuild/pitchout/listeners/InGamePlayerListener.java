package com.cookiebuild.pitchout.listeners;

import com.cookiebuild.cookiedough.game.Game;
import com.cookiebuild.cookiedough.game.GameManager;
import com.cookiebuild.cookiedough.listener.BaseEventBlocker;
import com.cookiebuild.cookiedough.player.CookiePlayer;
import com.cookiebuild.cookiedough.player.PlayerManager;
import com.cookiebuild.cookiedough.player.PlayerState;
import com.cookiebuild.pitchout.game.PitchoutGame;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class InGamePlayerListener extends BaseEventBlocker {

    private final Map<Player, Player> lastHitBy = new HashMap<>();
    private static final int MAX_LIVES = 5;

    public InGamePlayerListener() {
        protectedWorlds = new ArrayList<>();
    }

    public void addProtectedWorld(String worldName) {
        protectedWorlds.add(worldName);
    }

    public void removeProtectedWorld(String worldName) {
        protectedWorlds.remove(worldName);
    }

    private boolean isPlayerInGame(Player player) {
        CookiePlayer cookiePlayer = PlayerManager.getPlayer(player);
        Game game = GameManager.getGameOfPlayer(cookiePlayer);
        return cookiePlayer != null && cookiePlayer.getState() == PlayerState.IN_GAME && game instanceof PitchoutGame;
    }

    private boolean isGameRunning(Player player) {
        CookiePlayer cookiePlayer = PlayerManager.getPlayer(player);
        Game game = GameManager.getGameOfPlayer(cookiePlayer);
        return game != null && game.hasStarted();
    }

    @Override
    protected boolean shouldAllowEntityDamage(EntityDamageEvent event) {

        if (protectedWorlds.contains(event.getEntity().getWorld().getName())) {
            event.setDamage(0.0);
        }

        if (!(event.getEntity() instanceof Player player)) {
            return true; // Allow damage to non-player entities
        }

        if (!isPlayerInGame(player) || !isGameRunning(player)) {
            return false; // Prevent damage if not in a running PitchoutGame
        }

        if (event instanceof EntityDamageByEntityEvent damageByEntityEvent) {
            if (damageByEntityEvent.getDamager() instanceof Player damager) {
                if (damager.getGameMode() != GameMode.SPECTATOR) {
                    lastHitBy.put(player, damager);
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_HURT, 1, 1);
                    return true; // Prevent actual damage
                }
            }
        }

        return false; // Prevent any other type of damage
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!protectedWorlds.contains(event.getPlayer().getWorld().getName())) {
            return;
        }

        Player player = event.getPlayer();
        if (!isPlayerInGame(player) || !isGameRunning(player)) {
            return;
        }

        if (player.getLocation().getY() < 50) { // Adjust this value based on your map
            handlePlayerFall(player);
        }
    }

    private void handlePlayerFall(Player player) {
        CookiePlayer cookiePlayer = PlayerManager.getPlayer(player);
        Game game = GameManager.getGameOfPlayer(cookiePlayer);

        if (game instanceof PitchoutGame pitchoutGame) {
            int lives = pitchoutGame.getPlayerLives(cookiePlayer);
            Player lastHitter = lastHitBy.get(player);

            if (lives > 1) {
                lives--;
                pitchoutGame.setPlayerLives(cookiePlayer, lives);
                player.sendMessage("§cYou lost a life! You have " + lives + " lives remaining.");
                if (lastHitter != null) {
                    lastHitter.sendMessage("§aYou knocked " + player.getName() + " off the platform!");
                    lastHitter.playSound(lastHitter.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1);
                }
                player.setDisplayName(PitchoutGame.getColorForLives(lives) + " " + player.getName());
            } else {
                pitchoutGame.eliminatePlayer(cookiePlayer);
                player.sendMessage("§cYou have been eliminated from the game!");
                if (lastHitter != null) {
                    lastHitter.sendMessage("§aYou eliminated " + player.getName() + " from the game!");
                    lastHitter.playSound(lastHitter.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 2);
                }
                // firework sound
                player.getWorld().playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1, 1);
            }

            // Respawn the player
            Location spawnLocation = pitchoutGame.getRandomSpawnLocation();
            player.teleport(spawnLocation);
            // Add Green (villager) particles on respawn
            player.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, spawnLocation, 100, 0.1, 0.1, 0.1, 0);


            player.setFallDistance(0);
        }
    }

    @Override
    protected boolean shouldAllowPlayerInteract(PlayerInteractEvent event) {
        // only allow if game is running
        return isPlayerInGame(event.getPlayer()) && isGameRunning(event.getPlayer());
    }

    @Override
    protected boolean shouldAllowProjectileLaunch(ProjectileLaunchEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof Player)) {
            return true; // Allow damage to non-player entities
        }

        // only allow if game is running
        return isPlayerInGame((Player) entity) && isGameRunning((Player) entity);
    }

    // Add other necessary event handlers and methods as needed
}