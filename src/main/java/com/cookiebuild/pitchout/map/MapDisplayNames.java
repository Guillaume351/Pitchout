package com.cookiebuild.pitchout.map;

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
}
