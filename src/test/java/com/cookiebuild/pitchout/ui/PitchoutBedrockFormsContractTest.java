package com.cookiebuild.pitchout.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.cookiebuild.cookiedough.ui.BedrockFormImages;
import com.cookiebuild.cookiedough.ui.BedrockMenuSessionRegistry;

class PitchoutBedrockFormsContractTest {
    @Test
    void formClassKeepsMapVoteAndReplayAsSeparateGuardedFlows() throws Exception {
        assertTrue(PitchoutBedrockForms.class.getDeclaredMethod(
                "openMapVote", org.bukkit.entity.Player.class, java.util.List.class,
                java.util.function.Consumer.class).getReturnType() == boolean.class);
        assertTrue(PitchoutBedrockForms.class.getDeclaredMethod(
                "openReplay", org.bukkit.entity.Player.class, Runnable.class).getReturnType() == boolean.class);
        assertTrue(BedrockFormImages.isKnown(PitchoutBedrockForms.MAP_IMAGE));
        assertTrue(BedrockFormImages.isKnown(PitchoutBedrockForms.REPLAY_IMAGE));
        assertTrue(BedrockFormImages.isKnown(PitchoutBedrockForms.CLOSE_IMAGE));
        assertTrue(PitchoutBedrockForms.class.getDeclaredField("SESSIONS").getType()
                == BedrockMenuSessionRegistry.class);
    }
}
