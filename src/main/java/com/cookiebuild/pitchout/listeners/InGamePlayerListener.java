package com.cookiebuild.pitchout.listeners;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.projectiles.ProjectileSource;

import com.cookiebuild.cookiedough.game.Game;
import com.cookiebuild.cookiedough.game.GameManager;
import com.cookiebuild.cookiedough.listener.BaseEventBlocker;
import com.cookiebuild.cookiedough.player.CookiePlayer;
import com.cookiebuild.cookiedough.player.PlayerManager;
import com.cookiebuild.cookiedough.player.PlayerState;
import com.cookiebuild.pitchout.Pitchout;
import com.cookiebuild.pitchout.game.PitchoutGame;

public class InGamePlayerListener extends BaseEventBlocker {

    private final Map<Player, Player> lastHitBy = new HashMap<>();
    private final Map<Entity, Player> projectileOwners = new HashMap<>();
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

    private PitchoutGame getPlayersGame(Player player) {
        PitchoutGame game = (PitchoutGame) GameManager.getGameOfPlayer(PlayerManager.getPlayer(player));
        return game;
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
            Player damager = null;

            // Check if the damager is a player
            if (damageByEntityEvent.getDamager() instanceof Player playerDamager) {
                damager = playerDamager;
            }
            // Check if the damager is a projectile
            else if (damageByEntityEvent.getDamager() instanceof Entity projectile) {
                // See if we have a record of who launched this projectile
                damager = projectileOwners.get(projectile);
                // Clean up the map entry
                projectileOwners.remove(projectile);
            }

            if (damager != null && damager.getGameMode() != GameMode.SPECTATOR) {
                // Log the last player who hit this player
                Pitchout.getInstance().getLogger().info(
                        "Player " + damager.getName() + " hit player " + player.getName());
                lastHitBy.put(player, damager);
                player.playSound(damager.getLocation(), Sound.ENTITY_PLAYER_HURT, 1, 1);
                // Increment knockback count of damager
                PitchoutGame game = getPlayersGame(player);
                game.recordPlayerKnockback(PlayerManager.getPlayer(damager), PlayerManager.getPlayer(player));
            }
        }

        return true; // Prevent any other type of damage
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!protectedWorlds.contains(event.getPlayer().getWorld().getName())) {
            return;
        }

        Player player = event.getPlayer();
        if (!isPlayerInGame(player)) {

            return;
        } else if (!isGameRunning(player)) {
            if (player.getLocation().getY() < getPlayersGame(player).getTemplate().getWaitingAreaMinY()) {
                player.teleport(getPlayersGame(player).getTemplate().getSpawnLocation(player.getWorld()));
            }
        }

        if (player.getLocation().getY() < getPlayersGame(player).getTemplate().getKillY()) { // Adjust this value based
                                                                                             // on your map
            handlePlayerFall(player);
        }
    }

    private void handlePlayerFall(Player player) {

        CookiePlayer cookiePlayer = PlayerManager.getPlayer(player);
        Game game = GameManager.getGameOfPlayer(cookiePlayer);

        if (game instanceof PitchoutGame pitchoutGame) {
            int lives = pitchoutGame.getPlayerLives(cookiePlayer);
            Player lastHitter = lastHitBy.get(player);
            // if life < 0, already handled
            if (lives < 0)
                return;

            if (lives > 1) {
                lives--;
                pitchoutGame.setPlayerLives(cookiePlayer, lives);
                player.sendMessage(PitchoutGame.getLocalizedMessage(player, "pitchout.life_lost", lives));
                if (lastHitter != null) {
                    lastHitter.sendMessage(
                            PitchoutGame.getLocalizedMessage(lastHitter, "pitchout.player_knocked", player.getName()));
                    lastHitter.playSound(lastHitter.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1);

                    // Increment knockback count of last hitter
                    pitchoutGame.recordPlayerKnockback(PlayerManager.getPlayer(lastHitter), cookiePlayer);
                }
                player.setDisplayName(PitchoutGame.getColorForLives(lives) + " " + player.getName());

            } else {

                player.sendMessage(PitchoutGame.getLocalizedMessage(player, "pitchout.player_eliminated_game"));

                if (lastHitter != null) {
                    Pitchout.getInstance().getLogger().warning("Last hitter is " + lastHitter.getName());
                    lastHitter.sendMessage(PitchoutGame.getLocalizedMessage(lastHitter, "pitchout.eliminated_player",
                            player.getName()));
                    lastHitter.playSound(lastHitter.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 2);

                    pitchoutGame.eliminatePlayer(cookiePlayer, PlayerManager.getPlayer(lastHitter));
                } else {
                    Pitchout.getInstance().getLogger().warning("No last hitter is " + lives);
                    pitchoutGame.eliminatePlayer(cookiePlayer, null);
                }
                // firework sound
                player.getWorld().playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1, 1);
            }

            // Reset last hitter
            lastHitBy.put(player, null);
            // log the reset
            Pitchout.getInstance().getLogger().info("Reset last hitter for player " + player.getName());

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
        // Only process if the launched entity is a projectile
        if (entity instanceof Projectile) {
            Projectile projectile = (Projectile) entity;
            ProjectileSource shooter = projectile.getShooter();
            // Check if the shooter is a player in a running game
            if (shooter instanceof Player playerShooter) {
                if (isPlayerInGame(playerShooter) && isGameRunning(playerShooter)) {
                    // Track which player launched this projectile
                    projectileOwners.put(projectile, playerShooter);
                    return true;
                }
            }
            return false;
        }
        // Allow non-projectile entities by default
        return true;
    }

    // Add other necessary event handlers and methods as needed
}