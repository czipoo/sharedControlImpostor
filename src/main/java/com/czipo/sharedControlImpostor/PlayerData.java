package com.czipo.sharedControlImpostor;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Stores per-player game data.
 */
public class PlayerData {

    private final UUID playerId;
    private final String playerName;
    private PlayerRole role;
    private boolean eliminated;
    private boolean registered;
    private UUID votedFor;       // UUID of player they voted for, null = no vote, "skip" UUID = skip
    private boolean skipVote;

    public PlayerData(Player player) {
        this.playerId = player.getUniqueId();
        this.playerName = player.getName();
        this.role = null;
        this.eliminated = false;
        this.registered = true;
        this.votedFor = null;
        this.skipVote = false;
    }

    public UUID getPlayerId() { return playerId; }
    public String getPlayerName() { return playerName; }
    public PlayerRole getRole() { return role; }
    public void setRole(PlayerRole role) { this.role = role; }
    public boolean isEliminated() { return eliminated; }
    public void setEliminated(boolean eliminated) { this.eliminated = eliminated; }
    public boolean isRegistered() { return registered; }
    public void setRegistered(boolean registered) { this.registered = registered; }
    public UUID getVotedFor() { return votedFor; }
    public void setVotedFor(UUID votedFor) { this.votedFor = votedFor; this.skipVote = false; }
    public boolean isSkipVote() { return skipVote; }
    public void setSkipVote(boolean skipVote) { this.skipVote = skipVote; this.votedFor = null; }
    public boolean hasVoted() { return votedFor != null || skipVote; }
    public void clearVote() { this.votedFor = null; this.skipVote = false; }

    public boolean isImpostor() { return role == PlayerRole.IMPOSTOR; }
    public boolean isInvestigator() { return role == PlayerRole.INVESTIGATOR; }
    public boolean isActive() { return registered && !eliminated; }
}
