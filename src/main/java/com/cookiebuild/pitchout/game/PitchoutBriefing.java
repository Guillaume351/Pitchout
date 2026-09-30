package com.cookiebuild.pitchout.game;

import java.util.Locale;

import com.cookiebuild.pitchout.PitchoutMessages;

/** Three-line how-to-play briefing shown at match start: goal (title), lives (subtitle), tools (chat). */
record PitchoutBriefing(String title, String subtitle, String chat) {
    static PitchoutBriefing forLocale(Locale locale, int lives) {
        return new PitchoutBriefing(
                PitchoutMessages.get(locale, "pitchout.rules.title"),
                PitchoutMessages.get(locale, "pitchout.rules.subtitle", lives),
                PitchoutMessages.get(locale, "pitchout.rules.chat"));
    }
}
