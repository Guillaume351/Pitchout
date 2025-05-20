package com.cookiebuild.pitchout.game;

import com.cookiebuild.cookiedough.model.GameStats;
import com.cookiebuild.cookiedough.model.PlayerData;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "pitchout_stats")
public class PitchoutStats extends GameStats {

    @Column(nullable = false)
    private int knockbacks;

    @Column(nullable = false)
    private int consecutiveKnockbacks;

    @Column(nullable = false)
    private int maxConsecutiveKnockbacks;

    @Column(nullable = false)
    private int totalJumps;

    // Constructors
    public PitchoutStats() {
        super();
        this.setGameType("Pitchout");
        this.knockbacks = 0;
        this.consecutiveKnockbacks = 0;
        this.maxConsecutiveKnockbacks = 0;
        this.totalJumps = 0;
    }

    public PitchoutStats(PlayerData player) {
        super(player, "Pitchout");
        this.knockbacks = 0;
        this.consecutiveKnockbacks = 0;
        this.maxConsecutiveKnockbacks = 0;
        this.totalJumps = 0;
    }

    // Getters and setters
    public int getKnockbacks() {
        return knockbacks;
    }

    public void setKnockbacks(int knockbacks) {
        this.knockbacks = knockbacks;
    }

    public int getConsecutiveKnockbacks() {
        return consecutiveKnockbacks;
    }

    public void setConsecutiveKnockbacks(int consecutiveKnockbacks) {
        this.consecutiveKnockbacks = consecutiveKnockbacks;
        if (consecutiveKnockbacks > maxConsecutiveKnockbacks) {
            this.maxConsecutiveKnockbacks = consecutiveKnockbacks;
        }
    }

    public int getMaxConsecutiveKnockbacks() {
        return maxConsecutiveKnockbacks;
    }

    public void setMaxConsecutiveKnockbacks(int maxConsecutiveKnockbacks) {
        this.maxConsecutiveKnockbacks = maxConsecutiveKnockbacks;
    }

    public int getTotalJumps() {
        return totalJumps;
    }

    public void setTotalJumps(int totalJumps) {
        this.totalJumps = totalJumps;
    }

    // Utility methods
    public void incrementKnockbacks() {
        this.knockbacks++;
        this.consecutiveKnockbacks++;
        if (this.consecutiveKnockbacks > this.maxConsecutiveKnockbacks) {
            this.maxConsecutiveKnockbacks = this.consecutiveKnockbacks;
        }
    }

    public void resetConsecutiveKnockbacks() {
        this.consecutiveKnockbacks = 0;
    }

    public void incrementTotalJumps() {
        this.totalJumps++;
    }

    public void addKnockbacks(int count) {
        this.knockbacks += count;
    }

    public void addTotalJumps(int count) {
        this.totalJumps += count;
    }

    // Calculated stats
    public double getKnockbacksPerGame() {
        if (getGamesPlayed() == 0) {
            return 0;
        }
        return (double) knockbacks / getGamesPlayed();
    }

    @Override
    public java.util.Map<String, String> getFormattedSpecificStats() {
        java.util.Map<String, String> specificStats = new java.util.LinkedHashMap<>();
        specificStats.put("Players Elim.", String.valueOf(this.getTotalKills()));
        specificStats.put("Total Knockbacks", String.valueOf(this.getKnockbacks()));
        specificStats.put("Max Combo", String.valueOf(this.getMaxConsecutiveKnockbacks()));
        return specificStats;
    }
}