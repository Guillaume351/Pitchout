package com.cookiebuild.pitchout.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PitchoutBedrockFormsContractTest {
    @Test
    void formClassKeepsMapVoteAndReplayAsSeparateGuardedFlows() throws Exception {
        assertTrue(PitchoutBedrockForms.class.getDeclaredMethod(
                "openMapVote", org.bukkit.entity.Player.class, java.util.List.class,
                java.util.function.Consumer.class).getReturnType() == boolean.class);
        assertTrue(PitchoutBedrockForms.class.getDeclaredMethod(
                "openReplay", org.bukkit.entity.Player.class, Runnable.class).getReturnType() == boolean.class);
    }
}
