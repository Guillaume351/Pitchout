package com.cookiebuild.pitchout.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.event.block.Action;
import org.junit.jupiter.api.Test;

class MapVoteItemTest {
    @Test
    void everyClickOpensTheVoteSoBedrockTapsAndLongPressesWork() {
        assertTrue(MapVoteItem.opensVote(Action.RIGHT_CLICK_AIR));
        assertTrue(MapVoteItem.opensVote(Action.RIGHT_CLICK_BLOCK));
        assertTrue(MapVoteItem.opensVote(Action.LEFT_CLICK_AIR));
        assertTrue(MapVoteItem.opensVote(Action.LEFT_CLICK_BLOCK));
        assertFalse(MapVoteItem.opensVote(Action.PHYSICAL));
    }

    @Test
    void itemCannotBeMovedByCursorHotbarSwapOrShiftClick() {
        assertTrue(MapVoteItem.shouldCancelInventoryClick(true, false, false));
        assertTrue(MapVoteItem.shouldCancelInventoryClick(false, true, false));
        assertTrue(MapVoteItem.shouldCancelInventoryClick(false, false, true));
        assertFalse(MapVoteItem.shouldCancelInventoryClick(false, false, false));
    }

    @Test
    void duplicateBedrockUseEventsOpenOnlyOneForm() {
        assertTrue(MapVoteItem.canOpen(null, 5_000L));
        assertFalse(MapVoteItem.canOpen(5_000L, 5_400L));
        assertTrue(MapVoteItem.canOpen(5_000L, 6_000L));
        assertTrue(MapVoteItem.canOpen(5_000L, 4_000L), "a clock step backwards must not lock the item");
    }

    @Test
    void itemSitsInTheHotbar() {
        assertTrue(MapVoteItem.SLOT >= 0 && MapVoteItem.SLOT <= 8);
    }
}
