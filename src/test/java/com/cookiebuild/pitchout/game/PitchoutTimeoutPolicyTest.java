package com.cookiebuild.pitchout.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PitchoutTimeoutPolicyTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void ranksLivesThenEliminationsThenKnockbacks() {
        assertEquals(A, PitchoutTimeoutPolicy.winner(List.of(
                new PitchoutTimeoutPolicy.Standing(A, 3, 1, 9),
                new PitchoutTimeoutPolicy.Standing(B, 2, 20, 40))).orElseThrow());
        assertEquals(B, PitchoutTimeoutPolicy.winner(List.of(
                new PitchoutTimeoutPolicy.Standing(A, 3, 1, 20),
                new PitchoutTimeoutPolicy.Standing(B, 3, 2, 4))).orElseThrow());
        assertEquals(A, PitchoutTimeoutPolicy.winner(List.of(
                new PitchoutTimeoutPolicy.Standing(A, 3, 2, 5),
                new PitchoutTimeoutPolicy.Standing(B, 3, 2, 4))).orElseThrow());
    }

    @Test
    void exactTieIsAlwaysADraw() {
        assertTrue(PitchoutTimeoutPolicy.winner(List.of(
                new PitchoutTimeoutPolicy.Standing(A, 2, 1, 5),
                new PitchoutTimeoutPolicy.Standing(B, 2, 1, 5))).isEmpty());
    }
}
