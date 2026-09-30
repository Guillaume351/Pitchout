package com.cookiebuild.pitchout.map;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class MapTemplateKillYTest {
    @Test
    void fallIsCountedFourBlocksBelowTheConfiguredSpawn() {
        assertEquals(4, MapTemplate.KILL_DEPTH_BELOW_SPAWN);
        assertEquals(60, MapTemplate.killY(64));
        assertEquals(19, MapTemplate.killY(23));
        assertEquals(63, MapTemplate.killY(67));
    }

    @Test
    void shippedArenasKeepTheirWaitingAreaGuardAndGetTheWiderMargin() {
        MapTemplate frostbite = new MapTemplate("frozen", "Frostbite",
                List.of(List.of(0.5, 67.0, -46.5, 0.0)), List.of(0.5, 69.0, -65.5, 0.0), 10);
        // Frostbite's carpet floor is at y=65.0625, so the old 2-block rule (65) left only 1/16 of a block.
        assertEquals(63, frostbite.getKillY());
        assertEquals(66, frostbite.getWaitingAreaMinY());
    }
}
