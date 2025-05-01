package com.cookiebuild.pitchout.game;

import com.cookiebuild.cookiedough.model.PlayerData;
import com.cookiebuild.cookiedough.service.PlayerStatsService;

import jakarta.persistence.EntityManager;

/**
 * Utility class for managing Pitchout player statistics
 */
public class PitchoutStatsManager {

    private final PlayerStatsService statsService;

    public PitchoutStatsManager(EntityManager entityManager) {
        this.statsService = new PlayerStatsService(entityManager);
    }

    /**
     * Get or create Pitchout stats for a player
     * 
     * @param player The player
     * @return The player's Pitchout stats
     */
    public PitchoutStats getOrCreateStats(PlayerData player) {
        return statsService.getOrCreatePlayerStats(player, "Pitchout", PitchoutStats.class);
    }

    /**
     * Update player stats after a Pitchout game
     * 
     * @param player             The player
     * @param won                Whether the player won
     * @param kills              Number of kills (knockbacks that resulted in
     *                           elimination)
     * @param deaths             Number of deaths (times eliminated)
     * @param knockbacks         Number of knockbacks
     * @param fallsWithoutDamage Number of falls without taking damage
     * @param jumps              Number of jumps performed
     * @return The updated stats
     */
    public PitchoutStats updateStatsAfterGame(
            PlayerData player,
            boolean won,
            int kills,
            int deaths,
            int knockbacks,
            int fallsWithoutDamage,
            int jumps) {

        PitchoutStats stats = statsService.updatePlayerStatsAfterGame(
                player, "Pitchout", won, kills, deaths, PitchoutStats.class);

        stats.addKnockbacks(knockbacks);
        stats.addFallsWithoutDamage(fallsWithoutDamage);
        stats.addTotalJumps(jumps);

        // Reset consecutive knockbacks if player died
        if (deaths > 0) {
            stats.resetConsecutiveKnockbacks();
        }

        statsService.saveStats(stats);

        return stats;
    }

    /**
     * Record a knockback
     * 
     * @param player The player who performed the knockback
     * @return The updated stats
     */
    public PitchoutStats recordKnockback(PlayerData player) {
        PitchoutStats stats = getOrCreateStats(player);
        stats.incrementKnockbacks();
        statsService.saveStats(stats);
        return stats;
    }

    /**
     * Record a fall without damage
     * 
     * @param player The player who fell without damage
     * @return The updated stats
     */
    public PitchoutStats recordFallWithoutDamage(PlayerData player) {
        PitchoutStats stats = getOrCreateStats(player);
        stats.incrementFallsWithoutDamage();
        statsService.saveStats(stats);
        return stats;
    }

    /**
     * Record a jump
     * 
     * @param player The player who jumped
     * @return The updated stats
     */
    public PitchoutStats recordJump(PlayerData player) {
        PitchoutStats stats = getOrCreateStats(player);
        stats.incrementTotalJumps();
        statsService.saveStats(stats);
        return stats;
    }
}