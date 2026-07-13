package com.cookiebuild.pitchout.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class PitchoutStatsTest {
    @Test
    void eliminationDoesNotCountASecondKnockback() {
        UUID attacker = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        PitchoutStats stats = new PitchoutStats();

        stats.recordKnockback(attacker, victim);
        stats.recordElimination(victim, attacker);

        PitchoutStats.Snapshot attackerStats = stats.snapshot(attacker);
        PitchoutStats.Snapshot victimStats = stats.snapshot(victim);
        assertEquals(1, attackerStats.eliminations());
        assertEquals(1, attackerStats.knockbacksGiven());
        assertEquals(1, victimStats.deaths());
        assertEquals(1, victimStats.knockbacksReceived());
    }

    @Test
    void tracksCombosAndSelfFallsIndependently() {
        UUID attacker = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        PitchoutStats stats = new PitchoutStats();

        stats.recordKnockback(attacker, victim);
        stats.recordKnockback(attacker, victim);
        stats.recordSelfFall(victim);

        assertEquals(2, stats.snapshot(attacker).maxCombo());
        assertEquals(1, stats.snapshot(victim).selfFalls());
        assertEquals(0, stats.snapshot(victim).deaths());
    }
}
