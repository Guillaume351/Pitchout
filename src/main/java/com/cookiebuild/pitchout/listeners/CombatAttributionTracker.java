package com.cookiebuild.pitchout.listeners;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** A bounded, expiring replacement for strong Player/Entity reference maps. */
final class CombatAttributionTracker {
    private static final int MAX_ATTRIBUTIONS = 512;

    private record Attribution(UUID attackerId, UUID gameId, long expiresAtMillis) {
    }

    private final Map<UUID, Attribution> lastHits = new HashMap<>();
    private final long attributionDurationMillis;
    private final LongSupplier clock;

    CombatAttributionTracker(long attributionDurationMillis, LongSupplier clock) {
        this.attributionDurationMillis = attributionDurationMillis;
        this.clock = clock;
    }

    void record(UUID victimId, UUID attackerId, UUID gameId) {
        if (victimId == null || attackerId == null || gameId == null || victimId.equals(attackerId)) {
            return;
        }
        if (lastHits.size() >= MAX_ATTRIBUTIONS) {
            pruneExpired();
            if (lastHits.size() >= MAX_ATTRIBUTIONS) {
                Iterator<UUID> iterator = lastHits.keySet().iterator();
                if (iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
        }
        lastHits.put(victimId,
                new Attribution(attackerId, gameId, clock.getAsLong() + attributionDurationMillis));
    }

    UUID consume(UUID victimId, UUID gameId) {
        Attribution attribution = lastHits.remove(victimId);
        if (attribution == null
                || !attribution.gameId().equals(gameId)
                || attribution.expiresAtMillis() < clock.getAsLong()) {
            return null;
        }
        return attribution.attackerId();
    }

    void removePlayer(UUID playerId) {
        lastHits.remove(playerId);
        lastHits.entrySet().removeIf(entry -> entry.getValue().attackerId().equals(playerId));
    }

    void clearGame(UUID gameId) {
        lastHits.entrySet().removeIf(entry -> entry.getValue().gameId().equals(gameId));
    }

    int size() {
        return lastHits.size();
    }

    private void pruneExpired() {
        long now = clock.getAsLong();
        lastHits.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() < now);
    }
}
