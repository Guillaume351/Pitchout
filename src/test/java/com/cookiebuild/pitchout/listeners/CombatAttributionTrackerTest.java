package com.cookiebuild.pitchout.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class CombatAttributionTrackerTest {
    @Test
    void attributionIsSingleUseAndGameScoped() {
        AtomicLong clock = new AtomicLong(1_000L);
        CombatAttributionTracker tracker = new CombatAttributionTracker(10_000L, clock::get);
        UUID victim = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        UUID game = UUID.randomUUID();

        tracker.record(victim, attacker, game);

        assertNull(tracker.consume(victim, UUID.randomUUID()));
        tracker.record(victim, attacker, game);
        assertEquals(attacker, tracker.consume(victim, game));
        assertNull(tracker.consume(victim, game));
    }

    @Test
    void attributionExpiresAndPlayerCleanupRemovesReferences() {
        AtomicLong clock = new AtomicLong(1_000L);
        CombatAttributionTracker tracker = new CombatAttributionTracker(100L, clock::get);
        UUID attacker = UUID.randomUUID();
        UUID firstVictim = UUID.randomUUID();
        UUID secondVictim = UUID.randomUUID();
        UUID game = UUID.randomUUID();

        tracker.record(firstVictim, attacker, game);
        clock.set(1_101L);
        assertNull(tracker.consume(firstVictim, game));

        tracker.record(firstVictim, attacker, game);
        tracker.record(secondVictim, attacker, game);
        tracker.removePlayer(attacker);
        assertEquals(0, tracker.size());
    }
}
