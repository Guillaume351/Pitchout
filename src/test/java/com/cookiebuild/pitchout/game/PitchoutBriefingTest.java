package com.cookiebuild.pitchout.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

class PitchoutBriefingTest {
    @Test
    void briefingStatesGoalLivesAndToolsInEveryLocale() {
        for (Locale locale : List.of(Locale.ENGLISH, Locale.FRENCH, Locale.of("es"), Locale.of("pt", "BR"),
                Locale.of("bg"), Locale.of("hi"), Locale.GERMAN, Locale.ITALIAN)) {
            PitchoutBriefing briefing = PitchoutBriefing.forLocale(locale, 5);
            for (String line : List.of(briefing.title(), briefing.subtitle(), briefing.chat())) {
                assertFalse(line.startsWith("pitchout."), "untranslated line for " + locale);
                assertFalse(line.contains("{"), "unresolved placeholder for " + locale);
            }
            assertTrue(briefing.subtitle().contains("5"), "lives count for " + locale);
        }
        PitchoutBriefing french = PitchoutBriefing.forLocale(Locale.FRENCH, 5);
        assertEquals("Éjecte tout le monde !", french.title());
        assertEquals("5 vies · chaque chute en coûte une · 0 = éliminé", french.subtitle());
        assertTrue(french.chat().contains("pelle") && french.chat().contains("arc"));
    }

    @Test
    void arenaEntryOffersTheVoteInsteadOfOpeningItAndMatchStartBriefsPlayers() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/cookiebuild/pitchout/game/PitchoutGame.java"));
        assertFalse(source.contains("sendMapVotePrompt"), "entering the arena must not force-open the vote");
        assertTrue(source.contains("Pitchout.offerMapVote(player.getPlayer())"));
        String start = source.substring(source.indexOf("public void startGame()"),
                source.indexOf("private void startMatchPersistence()"));
        assertTrue(start.indexOf("super.startGame()") < start.indexOf("showRulesBriefing()"));
        // The vote item is removed at match start because teleportToGame resets the inventory first.
        String teleport = source.substring(source.indexOf("protected void teleportToGame"),
                source.indexOf("public boolean supportsSpectating()"));
        assertTrue(teleport.indexOf("player.resetPlayer()") < teleport.indexOf("setItem(0, shovel)"));
    }
}
