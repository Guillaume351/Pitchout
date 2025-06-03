package com.cookiebuild.pitchout.game;

import com.cookiebuild.cookiedough.model.Match;
import com.cookiebuild.cookiedough.model.PlayerData;
import com.cookiebuild.cookiedough.model.PlayerMatchPerformance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "pitchout_match_performances")
public class PitchoutMatchPerformance extends PlayerMatchPerformance {

    @Column(nullable = false)
    private int knockbacks;

    @Column(nullable = false)
    private int eliminations;

    @Column(nullable = false)
    private int maxCombo;

    @Column(nullable = false)
    private int selfFalls;

    public PitchoutMatchPerformance() {
        super();
    }

    public PitchoutMatchPerformance(Match match, PlayerData player) {
        super(match, player);
        this.knockbacks = 0;
        this.eliminations = 0;
        this.maxCombo = 0;
        this.selfFalls = 0;
    }

    public int getKnockbacks() {
        return knockbacks;
    }

    public void setKnockbacks(int knockbacks) {
        this.knockbacks = knockbacks;
    }

    public int getEliminations() {
        return eliminations;
    }

    public void setEliminations(int eliminations) {
        this.eliminations = eliminations;
    }

    public int getMaxCombo() {
        return maxCombo;
    }

    public void setMaxCombo(int maxCombo) {
        this.maxCombo = maxCombo;
    }

    public int getSelfFalls() {
        return selfFalls;
    }

    public void setSelfFalls(int selfFalls) {
        this.selfFalls = selfFalls;
    }

    public void incrementKnockbacks() {
        this.knockbacks++;
    }

    public void incrementEliminations() {
        this.eliminations++;
    }

    public void incrementSelfFalls() {
        this.selfFalls++;
    }
}
