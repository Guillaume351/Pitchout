package com.cookiebuild.pitchout.map;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;

import org.junit.jupiter.api.Test;

class MapDisplayNamesTest {
    @Test
    void localizesKnownMapsWithoutChangingStableIdsOrCustomLabels() {
        assertEquals("Cookie Circuit",
                MapDisplayNames.localized("pitchout1", "Configured label", Locale.ENGLISH));
        assertEquals("Четирите ъгъла",
                MapDisplayNames.localized("pitchout2", "Configured label", Locale.of("bg")));
        assertEquals("शीतदंश",
                MapDisplayNames.localized("frozen", "Configured label", Locale.of("hi")));
        assertEquals("Community Arena",
                MapDisplayNames.localized("community", "Community Arena", Locale.of("bg")));
    }
}
