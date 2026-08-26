package com.cookiebuild.pitchout.listeners;

/** Pure movement decision shared by the Pitchout listener and its regression tests. */
final class PitchoutMovementPolicy {
    enum Action {
        NONE,
        RETURN_TO_WAITING,
        HANDLE_MATCH_FALL
    }

    private PitchoutMovementPolicy() {
    }

    static Action decide(boolean gameRunning, boolean playerInGame, boolean spectator,
            double destinationY, double waitingAreaMinY, double killY) {
        if (!gameRunning) {
            return destinationY < waitingAreaMinY ? Action.RETURN_TO_WAITING : Action.NONE;
        }
        if (!playerInGame || spectator) {
            return Action.NONE;
        }
        return destinationY < killY ? Action.HANDLE_MATCH_FALL : Action.NONE;
    }
}
