package com.cookiebuild.pitchout.map;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** One vote per player, consumed when the next Pitchout arena is created. */
final class NextMapVote {
    record Selection(String mapName, boolean selectedByVote) {
    }

    private final Map<UUID, String> votesByPlayer = new HashMap<>();

    synchronized String vote(UUID playerId, String requestedMap, List<String> eligibleMaps) {
        String canonicalMap = eligibleMaps.stream()
                .filter(candidate -> candidate.equalsIgnoreCase(requestedMap))
                .findFirst()
                .orElse(null);
        if (canonicalMap != null) {
            votesByPlayer.put(playerId, canonicalMap);
        }
        return canonicalMap;
    }

    synchronized Selection consume(List<String> configuredMaps, String lastSelectedMap) {
        List<String> eligibleMaps = eligibleMaps(configuredMaps, lastSelectedMap);
        Map<String, Integer> counts = new HashMap<>();
        votesByPlayer.values().stream()
                .filter(eligibleMaps::contains)
                .forEach(map -> counts.merge(map, 1, Integer::sum));

        int winningVotes = counts.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<String> finalists = winningVotes == 0
                ? eligibleMaps
                : counts.entrySet().stream()
                        .filter(entry -> entry.getValue() == winningVotes)
                        .map(Map.Entry::getKey)
                        .sorted()
                        .toList();
        String selected = finalists.get(ThreadLocalRandom.current().nextInt(finalists.size()));
        votesByPlayer.clear();
        return new Selection(selected, winningVotes > 0);
    }

    static List<String> eligibleMaps(List<String> configuredMaps, String lastSelectedMap) {
        List<String> eligible = new ArrayList<>(configuredMaps);
        eligible.sort(String.CASE_INSENSITIVE_ORDER);
        if (eligible.size() > 1 && lastSelectedMap != null) {
            eligible.removeIf(map -> map.equalsIgnoreCase(lastSelectedMap));
        }
        return eligible;
    }
}
