package com.cookiebuild.pitchout.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

class PitchoutJavaVoteMenuTest {
    @Test
    void sizesToWholeRowsWithRoomForTheCloseButton() {
        assertEquals(9, PitchoutJavaVoteMenu.sizeFor(0));
        assertEquals(9, PitchoutJavaVoteMenu.sizeFor(2));
        assertEquals(9, PitchoutJavaVoteMenu.sizeFor(8));
        assertEquals(18, PitchoutJavaVoteMenu.sizeFor(9));
        assertEquals(54, PitchoutJavaVoteMenu.sizeFor(200));
    }

    @Test
    void mapsSlotsToOptionsAndIgnoresCloseAndEmptySlots() {
        List<String> maps = List.of("frozen", "pitchout2");
        assertEquals("frozen", PitchoutJavaVoteMenu.optionAt(maps, 0, 9));
        assertEquals("pitchout2", PitchoutJavaVoteMenu.optionAt(maps, 1, 9));
        assertNull(PitchoutJavaVoteMenu.optionAt(maps, 2, 9));
        assertNull(PitchoutJavaVoteMenu.optionAt(maps, 8, 9));
        assertNull(PitchoutJavaVoteMenu.optionAt(maps, -999, 9));
        assertNull(PitchoutJavaVoteMenu.optionAt(maps, 20, 9), "clicks in the player inventory never vote");
    }
}
