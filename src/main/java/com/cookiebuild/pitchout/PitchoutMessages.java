package com.cookiebuild.pitchout;

import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Pitchout-owned message bundle for copy that does not live in CookieDough's shared bundle.
 * Follows CookieDough's locale policy: language match, Portuguese to pt_BR, English fallback.
 */
public final class PitchoutMessages {
    static final String BUNDLE = "pitchout_messages";
    static final List<Locale> SUPPORTED_LOCALES = List.of(
            Locale.ENGLISH, Locale.FRENCH, Locale.of("es"), Locale.of("pt", "BR"),
            Locale.of("bg"), Locale.of("hi"), Locale.GERMAN, Locale.ITALIAN);
    private static final ResourceBundle.Control NO_SYSTEM_FALLBACK =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    private PitchoutMessages() { }

    /** Returns the localized message, or {@code null} when Pitchout's own bundle does not define the key. */
    public static String find(Locale locale, String key, Object... arguments) {
        ResourceBundle selected = bundle(supportedLocale(locale));
        ResourceBundle english = bundle(Locale.ENGLISH);
        String template = selected != null && selected.containsKey(key) ? selected.getString(key)
                : english != null && english.containsKey(key) ? english.getString(key) : null;
        if (template == null) return null;
        for (int index = 0; index < arguments.length; index++) {
            template = template.replace("{" + index + "}", String.valueOf(arguments[index]));
        }
        return template;
    }

    /** Like {@link #find} but never returns {@code null}. */
    public static String get(Locale locale, String key, Object... arguments) {
        String message = find(locale, key, arguments);
        return message == null ? key : message;
    }

    static Locale supportedLocale(Locale locale) {
        if (locale == null) return Locale.ENGLISH;
        return switch (locale.getLanguage()) {
            case "fr" -> Locale.FRENCH;
            case "es" -> Locale.of("es");
            case "pt" -> Locale.of("pt", "BR");
            case "bg" -> Locale.of("bg");
            case "hi" -> Locale.of("hi");
            case "de" -> Locale.GERMAN;
            case "it" -> Locale.ITALIAN;
            default -> Locale.ENGLISH;
        };
    }

    static ResourceBundle bundle(Locale locale) {
        try {
            return ResourceBundle.getBundle(BUNDLE, locale, PitchoutMessages.class.getClassLoader(),
                    NO_SYSTEM_FALLBACK);
        } catch (MissingResourceException missing) {
            return null;
        }
    }
}
