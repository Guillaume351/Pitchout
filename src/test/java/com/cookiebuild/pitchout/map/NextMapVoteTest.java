package com.cookiebuild.pitchout.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class NextMapVoteTest {
    @Test
    void consumesWinningEligibleVoteAndClearsTheTally() {
        NextMapVote vote = new NextMapVote();
        List<String> eligible = NextMapVote.eligibleMaps(List.of("frozen", "pitchout1", "pitchout2"), "frozen");
        vote.vote(UUID.randomUUID(), "pitchout2", eligible);
        vote.vote(UUID.randomUUID(), "PITCHOUT2", eligible);
        vote.vote(UUID.randomUUID(), "pitchout1", eligible);

        NextMapVote.Selection selected = vote.consume(
                List.of("frozen", "pitchout1", "pitchout2"), "frozen");
        assertEquals("pitchout2", selected.mapName());
        assertTrue(selected.selectedByVote());

        NextMapVote.Selection afterClear = vote.consume(
                List.of("frozen", "pitchout1", "pitchout2"), "pitchout2");
        assertFalse(afterClear.selectedByVote());
    }

    @Test
    void rejectsCurrentArenaWhenAlternativesExist() {
        NextMapVote vote = new NextMapVote();
        List<String> eligible = NextMapVote.eligibleMaps(List.of("frozen", "pitchout1"), "frozen");

        assertEquals(List.of("pitchout1"), eligible);
        assertNull(vote.vote(UUID.randomUUID(), "frozen", eligible));
    }
}
