package com.czipo.sharedControlImpostor;

/**
 * Represents the current state of the game.
 */
public enum GameState {
    LOBBY,       // Players in meeting world, registering
    PLAYING,     // Game active in survival world
    MEETING,     // Meeting/voting phase
    VOTING,      // Voting panel open
    VOTE_RESULT, // Showing vote results
    FINISHED     // Game over
}
