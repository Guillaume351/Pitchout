package com.cookiebuild.pitchout.game;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/** Fair timeout ranking: lives, eliminations, then effective knockbacks; exact ties draw. */
public final class PitchoutTimeoutPolicy {
    public record Standing(UUID playerId, int lives, int eliminations, int knockbacks) {
    }

    private static final Comparator<Standing> RANKING = Comparator
            .comparingInt(Standing::lives)
            .thenComparingInt(Standing::eliminations)
            .thenComparingInt(Standing::knockbacks);

    private PitchoutTimeoutPolicy() {
    }

    public static Optional<UUID> winner(Collection<Standing> standings) {
        if (standings == null || standings.isEmpty()) return Optional.empty();
        Standing best = standings.stream().max(RANKING).orElseThrow();
        long tied = standings.stream().filter(candidate -> sameScore(candidate, best)).count();
        return tied == 1 ? Optional.of(best.playerId()) : Optional.empty();
    }

    private static boolean sameScore(Standing left, Standing right) {
        return left.lives() == right.lives()
                && left.eliminations() == right.eliminations()
                && left.knockbacks() == right.knockbacks();
    }
}
