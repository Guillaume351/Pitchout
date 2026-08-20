package com.cookiebuild.pitchout;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class PersistenceArchitectureTest {
    @Test
    void gameUsesTransactionScopedServicesAndIndependentBoundedShutdownFlush() throws IOException {
        Path path = Path.of("src/main/java/com/cookiebuild/pitchout/game/PitchoutGame.java");
        if (!Files.exists(path)) path = Path.of("Pitchout").resolve(path);
        String game = Files.readString(path);

        assertFalse(game.contains("jakarta.persistence"));
        assertFalse(game.contains("createEntityManager"));
        assertFalse(game.contains("gameEntityManager"));
        assertTrue(game.contains("runTaskAsynchronously"));
        assertTrue(game.contains("BoundedAsyncFlush.runAndAwait"));
        String shutdownFlush = game.substring(game.indexOf("private void persistInterruptedOutcome"),
                game.indexOf("private void logWarning"));
        assertFalse(shutdownFlush.contains("getScheduler"));
        assertTrue(shutdownFlush.contains("new MatchService(null)"));
    }
}
