package com.cookiebuild.pitchout.map;

import java.util.Locale;

import com.cookiebuild.cookiedough.utils.LocaleManager;

/** Stable player-facing names for production configs created before display-name existed. */
final class MapDisplayNames {
    private MapDisplayNames() { }

    static String fallback(String mapName) {
        return switch (mapName) {
            case "pitchout1" -> "Cookie Circuit";
            case "pitchout2" -> "Four Corners";
            case "frozen" -> "Frostbite";
            default -> mapName;
        };
    }

    static String localized(String mapName, String configuredDisplayName, Locale locale) {
        String key = switch (mapName) {
            case "pitchout1" -> "pitchout.map.pitchout1";
            case "pitchout2" -> "pitchout.map.pitchout2";
            case "frozen" -> "pitchout.map.frozen";
            default -> null;
        };
        return key == null ? configuredDisplayName : LocaleManager.getMessage(key, locale);
    }
}
