package com.cookiebuild.pitchout.listeners;

import java.util.ArrayList;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import com.cookiebuild.pitchout.ui.PitchoutBedrockForms;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;

import com.cookiebuild.cookiedough.game.Game;
import com.cookiebuild.cookiedough.game.GameManager;
import com.cookiebuild.cookiedough.listener.BaseEventBlocker;
import com.cookiebuild.cookiedough.player.CookiePlayer;
import com.cookiebuild.cookiedough.player.PlayerManager;
import com.cookiebuild.cookiedough.player.PlayerState;
import com.cookiebuild.pitchout.Pitchout;
import com.cookiebuild.pitchout.game.PitchoutGame;
import net.kyori.adventure.text.Component;

public class InGamePlayerListener extends BaseEventBlocker {

    private static final long LAST_HIT_DURATION_MILLIS = 10_000L;
    private final CombatAttributionTracker combatAttribution =
            new CombatAttributionTracker(LAST_HIT_DURATION_MILLIS, System::currentTimeMillis);
    private final NamespacedKey projectileOwnerKey;
    private final NamespacedKey projectileGameKey;

    public InGamePlayerListener() {
        protectedWorlds = new ArrayList<>();
        projectileOwnerKey = new NamespacedKey(Pitchout.getInstance(), "projectile_owner");
        projectileGameKey = new NamespacedKey(Pitchout.getInstance(), "projectile_game");
    }

    public void addProtectedWorld(String worldName) {
        protectedWorlds.add(worldName);
    }

    public void removeProtectedWorld(String worldName) {
        protectedWorlds.remove(worldName);
    }

    private boolean isPlayerInGame(Player player) {
        CookiePlayer cookiePlayer = PlayerManager.getPlayer(player);
        if (cookiePlayer == null || cookiePlayer.getState() != PlayerState.IN_GAME) {
            return false;
        }
        Game game = GameManager.getGameOfPlayer(cookiePlayer);
        return game instanceof PitchoutGame;
    }

    private boolean isGameRunning(Player player) {
        PitchoutGame game = getPlayersGame(player);
        return game != null && game.hasStarted();
    }

    private PitchoutGame getPlayersGame(Player player) {
        CookiePlayer cookiePlayer = PlayerManager.getPlayer(player);
        if (cookiePlayer == null) {
            return null;
        }
        Game game = GameManager.getGameOfPlayer(cookiePlayer);
        return game instanceof PitchoutGame pitchoutGame ? pitchoutGame : null;
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
            PitchoutGame game = getPlayersGame(player);
            Player damager = resolveDamager(damageByEntityEvent.getDamager(), game);
            if (game != null && damager != null && damager.getGameMode() != GameMode.SPECTATOR) {
                CookiePlayer attacker = PlayerManager.getPlayer(damager);
                CookiePlayer victim = PlayerManager.getPlayer(player);
                combatAttribution.record(
                        player.getUniqueId(), damager.getUniqueId(), game.getGameId());
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_HURT, 1, 1);
                game.recordPlayerKnockback(attacker, victim);
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
        PitchoutGame game = getPlayersGame(player);
        if (game == null || !isPlayerInGame(player)) {
            return;
        }
        if (!isGameRunning(player)) {
            if (player.getLocation().getY() < game.getTemplate().getWaitingAreaMinY()) {
                player.teleport(game.getTemplate().getSpawnLocation(player.getWorld()));
            }
            return;
        }

        if (player.getGameMode() != GameMode.SPECTATOR
                && player.getLocation().getY() < game.getTemplate().getKillY()) {
            handlePlayerFall(player);
        }
    }

    private void handlePlayerFall(Player player) {

        CookiePlayer cookiePlayer = PlayerManager.getPlayer(player);
        Game game = GameManager.getGameOfPlayer(cookiePlayer);

        if (game instanceof PitchoutGame pitchoutGame) {
            int lives = pitchoutGame.getPlayerLives(cookiePlayer);
            if (lives <= 0) {
                return;
            }

            UUID attackerId = combatAttribution.consume(player.getUniqueId(), pitchoutGame.getGameId());
            Player lastHitter = attackerId == null ? null : Bukkit.getPlayer(attackerId);
            if (lastHitter != null && getPlayersGame(lastHitter) != pitchoutGame) {
                lastHitter = null;
            }
            if (lastHitter == null) {
                pitchoutGame.recordSelfFall(cookiePlayer);
            }

            if (lives > 1) {
                lives--;
                pitchoutGame.setPlayerLives(cookiePlayer, lives);
                player.sendMessage(PitchoutGame.getLocalizedMessage(player, "pitchout.life_lost", lives));
                if (lastHitter != null) {
                    lastHitter.sendMessage(
                            PitchoutGame.getLocalizedMessage(lastHitter, "pitchout.player_knocked", player.getName()));
                    lastHitter.playSound(lastHitter.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1);

                }
                player.displayName(Component.text(player.getName(), PitchoutGame.getColorForLives(lives)));

            } else {

                player.sendMessage(PitchoutGame.getLocalizedMessage(player, "pitchout.player_eliminated_game"));

                if (lastHitter != null) {
                    lastHitter.sendMessage(PitchoutGame.getLocalizedMessage(lastHitter, "pitchout.eliminated_player",
                            player.getName()));
                    lastHitter.playSound(lastHitter.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 2);

                    pitchoutGame.eliminatePlayer(cookiePlayer, PlayerManager.getPlayer(lastHitter));
                } else {
                    pitchoutGame.eliminatePlayer(cookiePlayer, null);
                }
                // firework sound
                player.getWorld().playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1, 1);
            }

            pitchoutGame.respawnPlayerAfterFall(cookiePlayer);
            // Add Green (villager) particles on respawn
            player.getWorld().spawnParticle(
                    Particle.HAPPY_VILLAGER, player.getLocation(), 100, 0.1, 0.1, 0.1, 0);
        }
    }

    @Override
    protected boolean shouldAllowPlayerInteract(PlayerInteractEvent event) {
        // only allow if game is running
        return isPlayerInGame(event.getPlayer()) && isGameRunning(event.getPlayer());
    }

    @Override
    protected boolean shouldAllowProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        ProjectileSource shooter = projectile.getShooter();
        if (shooter instanceof Player playerShooter) {
            PitchoutGame game = getPlayersGame(playerShooter);
            if (game != null && game.hasStarted() && isPlayerInGame(playerShooter)) {
                projectile.getPersistentDataContainer().set(
                        projectileOwnerKey, PersistentDataType.STRING, playerShooter.getUniqueId().toString());
                projectile.getPersistentDataContainer().set(
                        projectileGameKey, PersistentDataType.STRING, game.getGameId().toString());
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (projectile.getPersistentDataContainer().has(projectileGameKey, PersistentDataType.STRING)) {
            Bukkit.getScheduler().runTask(Pitchout.getInstance(), projectile::remove);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        PitchoutBedrockForms.invalidate(event.getPlayer());
        Player player = event.getPlayer();
        combatAttribution.removePlayer(player.getUniqueId());
        // CookieDough's quit listener may already have removed the PlayerManager entry.
        // Resolve from each game's own roster so spectators are never retained.
        for (Game game : GameManager.getGames()) {
            if (!(game instanceof PitchoutGame pitchoutGame)) {
                continue;
            }
            CookiePlayer trackedPlayer = pitchoutGame.getPlayers().stream()
                    .filter(candidate -> candidate.getPlayer().getUniqueId().equals(player.getUniqueId()))
                    .findFirst()
                    .orElse(null);
            if (trackedPlayer != null) {
                pitchoutGame.removePlayer(trackedPlayer, "disconnect");
                break;
            }
        }
    }

    @EventHandler
    public void onQuickPlayInteract(PlayerInteractEvent event) {
        if ((event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)
                || !Pitchout.isReplayHook(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        if (!Pitchout.openReplayForm(event.getPlayer())) Pitchout.joinOpenGame(event.getPlayer());
    }

    public void clearGameState(UUID gameId) {
        combatAttribution.clearGame(gameId);
    }

    private Player resolveDamager(Entity entity, PitchoutGame victimGame) {
        Player damager = null;
        UUID projectileGameId = null;
        if (entity instanceof Player player) {
            damager = player;
        } else if (entity instanceof Projectile projectile) {
            String ownerValue = projectile.getPersistentDataContainer().get(
                    projectileOwnerKey, PersistentDataType.STRING);
            String gameValue = projectile.getPersistentDataContainer().get(
                    projectileGameKey, PersistentDataType.STRING);
            try {
                if (ownerValue != null) {
                    damager = Bukkit.getPlayer(UUID.fromString(ownerValue));
                }
                if (gameValue != null) {
                    projectileGameId = UUID.fromString(gameValue);
                }
            } catch (IllegalArgumentException ignored) {
                return null;
            }
            if (damager == null && projectile.getShooter() instanceof Player player) {
                damager = player;
            }
        }

        if (damager == null || victimGame == null
                || (projectileGameId != null && !projectileGameId.equals(victimGame.getGameId()))
                || getPlayersGame(damager) != victimGame) {
            return null;
        }
        return damager;
    }
}
