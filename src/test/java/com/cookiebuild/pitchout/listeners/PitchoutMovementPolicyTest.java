package com.cookiebuild.pitchout.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PitchoutMovementPolicyTest {
    @Test
    void queuedPlayerBelowWaitingAreaReturnsEvenThoughNotYetInGame() {
        assertEquals(PitchoutMovementPolicy.Action.RETURN_TO_WAITING,
                PitchoutMovementPolicy.decide(false, false, false, false, 65.9, 66.0, 65.0));
    }

    @Test
    void queuedPlayerInsideWaitingAreaIsLeftAlone() {
        assertEquals(PitchoutMovementPolicy.Action.NONE,
                PitchoutMovementPolicy.decide(false, false, false, false, 66.0, 66.0, 65.0));
    }

    @Test
    void finishedMatchDoesNotRedirectHubTeleportBackIntoPitchout() {
        assertEquals(PitchoutMovementPolicy.Action.NONE,
                PitchoutMovementPolicy.decide(false, true, false, true, 64.0, 94.0, 62.0));
        assertEquals(PitchoutMovementPolicy.Action.NONE,
                PitchoutMovementPolicy.decide(true, true, false, true, 61.0, 94.0, 62.0));
    }

    @Test
    void runningPlayerBelowKillPlaneUsesMatchFallHandling() {
        assertEquals(PitchoutMovementPolicy.Action.HANDLE_MATCH_FALL,
                PitchoutMovementPolicy.decide(true, true, false, false, 64.9, 66.0, 65.0));
    }

    @Test
    void spectatorAndUnownedRunningPlayerNeverLoseLives() {
        assertEquals(PitchoutMovementPolicy.Action.NONE,
                PitchoutMovementPolicy.decide(true, true, true, false, 64.9, 66.0, 65.0));
        assertEquals(PitchoutMovementPolicy.Action.NONE,
                PitchoutMovementPolicy.decide(true, false, false, false, 64.9, 66.0, 65.0));
    }
}
