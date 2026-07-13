package com.cookiebuild.pitchout.game;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Match-local Pitchout counters. Keeping this independent from Bukkit makes the
 * scoring rules deterministic and easy to test.
 */
final class PitchoutStats {
    record Snapshot(
            int eliminations,
            int deaths,
            int knockbacksGiven,
            int knockbacksReceived,
            int maxCombo,
            int selfFalls) {
    }

    private final Map<UUID, Integer> eliminations = new HashMap<>();
    private final Map<UUID, Integer> deaths = new HashMap<>();
    private final Map<UUID, Integer> knockbacksGiven = new HashMap<>();
    private final Map<UUID, Integer> knockbacksReceived = new HashMap<>();
    private final Map<UUID, Integer> currentCombos = new HashMap<>();
    private final Map<UUID, Integer> maxCombos = new HashMap<>();
    private final Map<UUID, Integer> selfFalls = new HashMap<>();

    void recordKnockback(UUID attackerId, UUID victimId) {
        if (attackerId == null || victimId == null || attackerId.equals(victimId)) {
            return;
        }

        increment(knockbacksGiven, attackerId);
        increment(knockbacksReceived, victimId);

        int combo = currentCombos.getOrDefault(attackerId, 0) + 1;
        currentCombos.put(attackerId, combo);
        maxCombos.merge(attackerId, combo, Math::max);
        currentCombos.put(victimId, 0);
    }

    void recordElimination(UUID victimId, UUID attackerId) {
        if (victimId == null) {
            return;
        }
        increment(deaths, victimId);
        currentCombos.put(victimId, 0);
        if (attackerId != null && !attackerId.equals(victimId)) {
            increment(eliminations, attackerId);
        }
    }

    void recordSelfFall(UUID playerId) {
        if (playerId != null) {
            increment(selfFalls, playerId);
        }
    }

    Snapshot snapshot(UUID playerId) {
        return new Snapshot(
                eliminations.getOrDefault(playerId, 0),
                deaths.getOrDefault(playerId, 0),
                knockbacksGiven.getOrDefault(playerId, 0),
                knockbacksReceived.getOrDefault(playerId, 0),
                maxCombos.getOrDefault(playerId, 0),
                selfFalls.getOrDefault(playerId, 0));
    }

    void clear() {
        eliminations.clear();
        deaths.clear();
        knockbacksGiven.clear();
        knockbacksReceived.clear();
        currentCombos.clear();
        maxCombos.clear();
        selfFalls.clear();
    }

    private static void increment(Map<UUID, Integer> counters, UUID playerId) {
        counters.merge(playerId, 1, Integer::sum);
    }
}
