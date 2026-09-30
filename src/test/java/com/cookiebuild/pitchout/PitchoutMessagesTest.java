package com.cookiebuild.pitchout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class PitchoutMessagesTest {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

    @Test
    void everySupportedLocaleHasExactKeyAndPlaceholderParity() {
        ResourceBundle english = PitchoutMessages.bundle(Locale.ENGLISH);
        assertNotNull(english);
        for (Locale locale : PitchoutMessages.SUPPORTED_LOCALES) {
            ResourceBundle translated = PitchoutMessages.bundle(locale);
            assertNotNull(translated, "bundle for " + locale);
            assertEquals(english.keySet(), translated.keySet(), "keys for " + locale);
            for (String key : english.keySet()) {
                assertEquals(placeholders(english.getString(key)), placeholders(translated.getString(key)),
                        key + " placeholders for " + locale);
            }
        }
    }

    @Test
    void frenchCopyUsesInformalTu() {
        ResourceBundle french = PitchoutMessages.bundle(Locale.FRENCH);
        for (String key : french.keySet()) {
            String value = french.getString(key).toLowerCase(Locale.ROOT);
            assertFalse(value.matches(".*\\bvous\\b.*|.*\\bvotre\\b.*|.*\\bvos\\b.*"), key + " must use tu");
        }
        assertEquals("Voter pour la prochaine arène",
                PitchoutMessages.find(Locale.CANADA_FRENCH, "pitchout.vote.item.name"));
    }

    @Test
    void followsCookieDoughLocalePolicyAndFallsBackToEnglish() {
        assertEquals("Votar na próxima arena",
                PitchoutMessages.find(Locale.of("pt", "PT"), "pitchout.vote.item.name"));
        assertEquals("Vote for the next arena",
                PitchoutMessages.find(Locale.JAPANESE, "pitchout.vote.item.name"));
        assertEquals("Vote for the next arena", PitchoutMessages.find(null, "pitchout.vote.item.name"));
        assertNull(PitchoutMessages.find(Locale.FRENCH, "pitchout.life_lost"),
                "keys owned by CookieDough must fall through to LocaleManager");
    }

    @Test
    void voteHintIsPlainTextNamingTheItemAndTheCommandInEveryLocale() {
        for (Locale locale : PitchoutMessages.SUPPORTED_LOCALES) {
            String itemName = PitchoutMessages.get(locale, "pitchout.vote.item.name");
            String hint = PitchoutMessages.get(locale, "pitchout.vote.hint", itemName);
            assertTrue(hint.contains(itemName), "hint names the item for " + locale);
            assertTrue(hint.contains("/pitchout vote"), "hint names the command for " + locale);
            assertFalse(hint.contains("{"), "hint has no unresolved placeholder for " + locale);
        }
        assertEquals("Vote pour la prochaine arène : utilise l’objet « Voter pour la prochaine arène » "
                        + "de ta barre ou tape /pitchout vote",
                PitchoutMessages.get(Locale.FRENCH, "pitchout.vote.hint",
                        PitchoutMessages.get(Locale.FRENCH, "pitchout.vote.item.name")));
    }

    private static Set<String> placeholders(String value) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(value);
        while (matcher.find()) found.add(matcher.group());
        return found;
    }
}
