package com.czipo.sharedControlImpostor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Manages the sidebar scoreboard display for all players.
 * Shows: plugin title, role, objective, current turn, timer.
 */
public class ScoreboardManager {

    private final SharedControlImpostor plugin;
    private final GameManager gameManager;

    private BukkitRunnable updateTask;
    private final Map<UUID, org.bukkit.scoreboard.Scoreboard> playerScoreboards = new HashMap<>();

    public ScoreboardManager(SharedControlImpostor plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
    }

    /**
     * Start periodic scoreboard updates (every second).
     */
    public void startUpdates() {
        stopUpdates();

        updateTask = new BukkitRunnable() {
            @Override
            public void run() {
                updateAllScoreboards();
            }
        };

        updateTask.runTaskTimer(plugin, 0L, 20L); // Every second
    }

    /**
     * Force an immediate scoreboard update for all players.
     * Called when critical state changes (like timer countdown) to ensure fresh data.
     */
    public void forceUpdate() {
        if (gameManager.isPlaying()) {
            updateAllScoreboards();
        }
    }

    /**
     * Stop scoreboard updates.
     */
    public void stopUpdates() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
    }

    /**
     * Update scoreboard for all registered players (including eliminated).
     */
    private void updateAllScoreboards() {
        for (PlayerData pd : gameManager.getRegisteredPlayers()) {
            Player player = Bukkit.getPlayer(pd.getPlayerId());
            if (player != null) {
                updatePlayerScoreboard(player, pd);
            }
        }
    }

    /**
     * Create or update a player's scoreboard.
     */
    private void updatePlayerScoreboard(Player player, PlayerData pd) {
        org.bukkit.scoreboard.ScoreboardManager bukkitManager = Bukkit.getScoreboardManager();
        if (bukkitManager == null) return;

        Scoreboard board = playerScoreboards.get(pd.getPlayerId());
        if (board == null) {
            board = bukkitManager.getNewScoreboard();
            playerScoreboards.put(pd.getPlayerId(), board);
        }

        // Get or create the objective
        org.bukkit.scoreboard.Objective sidebarObjective = board.getObjective("sci_sidebar");
        if (sidebarObjective != null) {
            // Unregister to clear entries cleanly and prevent "objective already exists" issues
            sidebarObjective.unregister();
        }

        sidebarObjective = board.registerNewObjective("sci_sidebar", Criteria.DUMMY,
                Component.text("Shared Control").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        sidebarObjective.setDisplaySlot(DisplaySlot.SIDEBAR);
        
        try {
            sidebarObjective.numberFormat(io.papermc.paper.scoreboard.numbers.NumberFormat.blank());
        } catch (Throwable t) {
            // Ignore if method not available on this server version
        }

        // Baris 1: Judul (sudah di set di atas)
        // Baris 2: Pembatas atas
        sidebarObjective.getScore("§7--------------------").setScore(9);

        // Baris 3: Role
        String roleText;
        NamedTextColor roleColor;
        if (pd == null || pd.getRole() == null) {
            roleText = "Waiting...";
            roleColor = NamedTextColor.GRAY;
        } else if (pd.isImpostor()) {
            roleText = "IMPOSTOR";
            roleColor = NamedTextColor.RED;
        } else {
            roleText = "INVESIGATOR";
            roleColor = NamedTextColor.GREEN;
        }
        sidebarObjective.getScore("§fRole: §" + (roleColor == NamedTextColor.RED ? "c" : "a") + roleText).setScore(8);

        // Baris 4: Kosong
        sidebarObjective.getScore(" ").setScore(7);

        // Baris 5: Objektif/Clue
        Objective gameObjective = gameManager.getCurrentObjective();
        String objectiveText;
        if (gameObjective == null) {
            objectiveText = "§fObjektif: §eNone";
        } else if (pd != null && pd.isImpostor()) {
            objectiveText = "§fClue: §e" + gameObjective.getClue();
        } else {
            objectiveText = "§fObjektif: §e" + gameObjective.getProgressDisplay();
        }
        
        // Truncate if too long (max 40 chars for legacy scoreboard)
        if (objectiveText.length() > 40) {
            objectiveText = objectiveText.substring(0, 37) + "...";
        }
        sidebarObjective.getScore(objectiveText).setScore(6);

        // Baris 6: Kosong
        sidebarObjective.getScore("  ").setScore(5);

        // Baris 7: Giliran
        Player activePlayer = gameManager.getCurrentActivePlayer();
        String turnText = activePlayer != null ? activePlayer.getName() : "None";
        if (pd != null && pd.isEliminated()) {
            sidebarObjective.getScore("§fStatus: §cEliminated").setScore(4);
        } else {
            sidebarObjective.getScore("§fGiliran: §b" + turnText).setScore(4);
        }

        // Baris 8: Timer
        int timeLeft = gameManager.getCurrentTurnTimeLeft();
        String timerChar = timeLeft <= 3 ? "c" : "e";
        sidebarObjective.getScore("§fTimer: §" + timerChar + timeLeft + "s").setScore(3);

        // Baris 9: Meeting
        if (gameManager.isMeeting()) {
            sidebarObjective.getScore("§fMeeting: §eSedang berlangsung").setScore(2);
        } else if (gameManager.isPlaying()) {
            int cooldown = gameManager.getMeetingCooldownRemaining();
            if (cooldown > 0) {
                sidebarObjective.getScore("§fMeeting: §7" + cooldown + "s").setScore(2);
            } else {
                sidebarObjective.getScore("§fMeeting: §aReady!").setScore(2);
            }
        } else {
            sidebarObjective.getScore("§fMeeting: §7-").setScore(2);
        }

        // Baris 10: Pembatas bawah
        sidebarObjective.getScore("§7-------------------- ").setScore(1);

        // Set the scoreboard for the player
        
        // Ensure nametag hiding applies to this scoreboard too
        org.bukkit.scoreboard.Team hideTeam = board.getTeam("sci_hidden");
        if (hideTeam == null) {
            hideTeam = board.registerNewTeam("sci_hidden");
        }
        hideTeam.setOption(org.bukkit.scoreboard.Team.Option.NAME_TAG_VISIBILITY, org.bukkit.scoreboard.Team.OptionStatus.NEVER);
        // Add ALL game players to the hide team, not just current player
        for (PlayerData pd2 : gameManager.getActivePlayers()) {
            Player p = Bukkit.getPlayer(pd2.getPlayerId());
            if (p != null) {
                hideTeam.addPlayer(p);
            }
        }
        
        player.setScoreboard(board);
    }

    /**
     * Remove all scoreboards.
     */
    public void removeScoreboards() {
        org.bukkit.scoreboard.ScoreboardManager bukkitManager = Bukkit.getScoreboardManager();
        if (bukkitManager == null) return;

        for (UUID playerId : playerScoreboards.keySet()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.setScoreboard(bukkitManager.getMainScoreboard());
            }
        }
        playerScoreboards.clear();
    }

    /**
     * Remove scoreboard for a specific player.
     */
    public void removePlayerScoreboard(UUID playerId) {
        org.bukkit.scoreboard.ScoreboardManager bukkitManager = Bukkit.getScoreboardManager();
        if (bukkitManager == null) return;

        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            player.setScoreboard(bukkitManager.getMainScoreboard());
        }
        playerScoreboards.remove(playerId);
    }

    /**
     * Show meeting/vote result info on scoreboard during meeting phase.
     */
    public void showMeetingInfo(String info) {
        for (PlayerData pd : gameManager.getActivePlayers()) {
            Player player = Bukkit.getPlayer(pd.getPlayerId());
            if (player != null) {
                // Use action bar for meeting info
                player.sendActionBar(Component.text(info).color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
            }
        }
    }
}
