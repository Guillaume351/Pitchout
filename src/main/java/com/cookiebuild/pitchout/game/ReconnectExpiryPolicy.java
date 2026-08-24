package com.cookiebuild.pitchout.game;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pure batch selection so simultaneous expirations are resolved before winners. */
final class ReconnectExpiryPolicy {
    private ReconnectExpiryPolicy() { }

    static List<UUID> expired(Map<UUID, Long> disconnectedAt, long nowMillis, long graceMillis) {
        if (disconnectedAt == null || disconnectedAt.isEmpty()) return List.of();
        return disconnectedAt.entrySet().stream()
                .filter(entry -> entry.getValue() != null && nowMillis - entry.getValue() > graceMillis)
                .map(Map.Entry::getKey)
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
    }
}
