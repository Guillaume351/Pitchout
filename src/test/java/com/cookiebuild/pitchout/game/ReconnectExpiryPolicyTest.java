package com.cookiebuild.pitchout.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ReconnectExpiryPolicyTest {
    @Test
    void returnsEverySimultaneouslyExpiredReservationAsOneBatch() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID active = UUID.fromString("00000000-0000-0000-0000-000000000003");
        Map<UUID, Long> disconnected = new LinkedHashMap<>();
        disconnected.put(second, 1_000L);
        disconnected.put(first, 1_000L);
        disconnected.put(active, 9_500L);

        assertEquals(java.util.List.of(first, second),
                ReconnectExpiryPolicy.expired(disconnected, 10_000L, 5_000L));
    }
}
