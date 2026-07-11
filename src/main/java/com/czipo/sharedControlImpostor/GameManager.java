package com.czipo.sharedControlImpostor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Central manager for all game state and logic.
 */
public class GameManager {

    private final SharedControlImpostor plugin;
    private GameState gameState = GameState.LOBBY;

    // Player tracking
    private final Map<UUID, PlayerData> playerDataMap = new LinkedHashMap<>();

    // Game settings
    private int turnTimeSeconds = 15;
    private int impostorCount = 1;
    private int meetingCooldownSeconds = 30;

    // Current game state
    private Objective currentObjective;
    private UUID currentActivePlayerId;
    private int currentTurnTimeLeft;
    private int turnNumber;

    // Meeting state
    private long lastMeetingEndTime;
    private long gameStartTime;
    private boolean meetingCooldownActive = false;
    private final Set<UUID> playersWhoCalledMeeting = new HashSet<>();

    private boolean waitingForFirstMove = false;

    // Saved state before meeting
    private SavedGameState savedGameState;

    // Managers (will be set from plugin)
    private TurnManager turnManager;
    private VoteManager voteManager;
    private ScoreboardManager scoreboardManager;
    private WorldManager worldManager;
    private SettingsManager settingsManager;
    private ObjectiveManager objectiveManager;

    public GameManager(SharedControlImpostor plugin) {
        this.plugin = plugin;
        this.lastMeetingEndTime = 0;
        this.gameStartTime = 0;
        this.meetingCooldownActive = false;
    }

    // ===== Manager setters & getters =====
    public void setTurnManager(TurnManager turnManager) { this.turnManager = turnManager; }
    public void setVoteManager(VoteManager voteManager) { this.voteManager = voteManager; }
    public void setScoreboardManager(ScoreboardManager scoreboardManager) { this.scoreboardManager = scoreboardManager; }
    public void setWorldManager(WorldManager worldManager) { this.worldManager = worldManager; }
    public void setSettingsManager(SettingsManager settingsManager) { this.settingsManager = settingsManager; }
    public void setObjectiveManager(ObjectiveManager objectiveManager) { this.objectiveManager = objectiveManager; }

    public TurnManager getTurnManager() { return turnManager; }
    public VoteManager getVoteManager() { return voteManager; }
    public ScoreboardManager getScoreboardManager() { return scoreboardManager; }
    public WorldManager getWorldManager() { return worldManager; }
    public SettingsManager getSettingsManager() { return settingsManager; }
    public ObjectiveManager getObjectiveManager() { return objectiveManager; }

    public SharedControlImpostor getPlugin() { return plugin; }

    // ===== Game State =====
    public GameState getGameState() { return gameState; }
    public void setGameState(GameState state) { this.gameState = state; }
    public boolean isPlaying() { return gameState == GameState.PLAYING; }
    public boolean isMeeting() { return gameState == GameState.MEETING || gameState == GameState.VOTING || gameState == GameState.VOTE_RESULT; }
    public boolean isLobby() { return gameState == GameState.LOBBY; }
    public boolean isFinished() { return gameState == GameState.FINISHED; }

    // ===== Player Registration =====
    public boolean registerPlayer(Player player) {
        if (playerDataMap.containsKey(player.getUniqueId())) {
            return false; // Already registered
        }
        playerDataMap.put(player.getUniqueId(), new PlayerData(player));
        return true;
    }

    public boolean registerAllOnline() {
        boolean anyNew = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!playerDataMap.containsKey(player.getUniqueId())) {
                playerDataMap.put(player.getUniqueId(), new PlayerData(player));
                anyNew = true;
            }
        }
        return anyNew;
    }

    public boolean unregisterPlayer(Player player) {
        if (!playerDataMap.containsKey(player.getUniqueId())) {
            return false;
        }
        playerDataMap.remove(player.getUniqueId());
        return true;
    }

    public PlayerData getPlayerData(UUID playerId) {
        return playerDataMap.get(playerId);
    }

    public PlayerData getPlayerData(Player player) {
        return getPlayerData(player.getUniqueId());
    }

    public Collection<PlayerData> getAllPlayerData() {
        return playerDataMap.values();
    }

    public List<PlayerData> getActivePlayers() {
        return playerDataMap.values().stream()
                .filter(PlayerData::isActive)
                .collect(Collectors.toList());
    }

    public List<PlayerData> getRegisteredPlayers() {
        return playerDataMap.values().stream()
                .filter(pd -> pd.isRegistered())
                .collect(Collectors.toList());
    }

    public int getActivePlayerCount() {
        return getActivePlayers().size();
    }

    public int getRegisteredPlayerCount() {
        return getRegisteredPlayers().size();
    }

    // ===== Impostor Count =====
    public int getImpostorCount() { return impostorCount; }

    public void setImpostorCount(int count) {
        this.impostorCount = count;
    }

    /**
     * Calculate maximum impostor count based on player count for balance.
     * 3 players = 1 impostor max
     * 4 players = 2 impostor max
     * 5-6 players = 2 impostor max
     * 7-8 players = 2 impostor max
     * 9+ players = 3 impostor max (roughly 1/3)
     */
    public int getMaxImpostorCount(int playerCount) {
        // Formula: floor((playerCount - 1) / 3), minimum 1
        // 3-6 = 1, 7-9 = 2, 10-12 = 3, etc.
        return Math.max(1, (playerCount - 1) / 3);
    }

    // ===== Turn Time =====
    public int getTurnTimeSeconds() { return turnTimeSeconds; }
    public void setTurnTimeSeconds(int seconds) {
        this.turnTimeSeconds = Math.max(5, seconds); // Minimum 5 seconds
    }

    // ===== Meeting Cooldown =====
    public int getMeetingCooldownSeconds() { return meetingCooldownSeconds; }
    public void setMeetingCooldownSeconds(int seconds) { this.meetingCooldownSeconds = seconds; }

    /**
     * Get the reference time for cooldown calculation.
     * Uses lastMeetingEndTime if a meeting has ended, otherwise uses gameStartTime.
     */
    private long getCooldownReferenceTime() {
        return meetingCooldownActive ? lastMeetingEndTime : gameStartTime;
    }

    public boolean canCallMeeting() {
        if (!isPlaying()) return false;
        long elapsed = (System.currentTimeMillis() - getCooldownReferenceTime()) / 1000;
        return elapsed >= meetingCooldownSeconds;
    }

    public int getMeetingCooldownRemaining() {
        long elapsed = (System.currentTimeMillis() - getCooldownReferenceTime()) / 1000;
        int remaining = meetingCooldownSeconds - (int) elapsed;
        return Math.max(0, remaining);
    }

    // ===== Current Active Player =====
    public UUID getCurrentActivePlayerId() { return currentActivePlayerId; }
    public Player getCurrentActivePlayer() {
        if (currentActivePlayerId == null) return null;
        return Bukkit.getPlayer(currentActivePlayerId);
    }
    public void setCurrentActivePlayer(UUID playerId) { this.currentActivePlayerId = playerId; }

    public int getCurrentTurnTimeLeft() { return currentTurnTimeLeft; }
    public void setCurrentTurnTimeLeft(int timeLeft) { this.currentTurnTimeLeft = timeLeft; }

    public int getTurnNumber() { return turnNumber; }
    public void setTurnNumber(int turnNumber) { this.turnNumber = turnNumber; }

    // ===== First Move Tracking =====
    public boolean isWaitingForFirstMove() { return waitingForFirstMove; }
    public void setWaitingForFirstMove(boolean waiting) { this.waitingForFirstMove = waiting; }

    // ===== Saved Game State =====
    public SavedGameState getSavedGameState() { return savedGameState; }
    public void setSavedGameState(SavedGameState savedGameState) { this.savedGameState = savedGameState; }

    public void setGameStartTime(long time) { this.gameStartTime = time; }
    
    public void setLastMeetingEndTime(long time) { this.lastMeetingEndTime = time; }

    // ===== Start Game =====
    public void startGame() {
        List<PlayerData> registered = getRegisteredPlayers();
        if (registered.size() < 3) {
            Bukkit.broadcast(Component.text("Need at least 3 players to start!").color(NamedTextColor.RED));
            return;
        }

        // Set state to playing early to prevent duplicate starts and Lobby events
        gameState = GameState.PLAYING;

        // Check maximum impostor limit
        int maxImpostors = getMaxImpostorCount(getRegisteredPlayerCount());
        if (impostorCount > maxImpostors) {
            impostorCount = maxImpostors;
        }
        if (impostorCount >= registered.size()) {
            Bukkit.broadcast(Component.text("Too many impostors for this player count!").color(NamedTextColor.RED));
            return;
        }
        
        // Close OP inventory and remove Settings item before game starts
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp()) {
                p.closeInventory();
                p.getInventory().setItem(4, null);
            }
        }

        // Reset meeting quotas
        playersWhoCalledMeeting.clear();

        // Generate world (will freeze momentarily, but we removed the text as requested)
        worldManager.createSurvivalWorld();

        // Assign roles randomly
        List<PlayerData> shuffled = new ArrayList<>(registered);
        Collections.shuffle(shuffled);

        for (int i = 0; i < impostorCount; i++) {
            shuffled.get(i).setRole(PlayerRole.IMPOSTOR);
        }
        for (int i = impostorCount; i < shuffled.size(); i++) {
            shuffled.get(i).setRole(PlayerRole.INVESTIGATOR);
        }

        // Reset eliminated status
        for (PlayerData pd : playerDataMap.values()) {
            pd.setEliminated(false);
            pd.clearVote();
            Player p = Bukkit.getPlayer(pd.getPlayerId());
            if (p != null) p.getInventory().clear();
        }

        // Assign objectives using ObjectiveManager
        objectiveManager.assignObjectives();

        // Role reveal animation
        new org.bukkit.scheduler.BukkitRunnable() {
            int ticks = 0;
            final int maxTicks = 60; // 3 seconds of alternating animation (every 2 ticks)
            boolean showImpostor = false;

            @Override
            public void run() {
                if (ticks < maxTicks) {
                    // Alternating animation between INVESTIGATOR and IMPOSTOR
                    showImpostor = !showImpostor;
                    for (PlayerData pd : registered) {
                        Player player = Bukkit.getPlayer(pd.getPlayerId());
                        if (player != null) {
                            if (showImpostor) {
                                player.showTitle(net.kyori.adventure.title.Title.title(
                                        Component.text("IMPOSTOR").color(NamedTextColor.RED).decorate(TextDecoration.BOLD),
                                        Component.text(" "),
                                        net.kyori.adventure.title.Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofMillis(300), java.time.Duration.ZERO)
                                ));
                            } else {
                                player.showTitle(net.kyori.adventure.title.Title.title(
                                        Component.text("INVESTIGATOR").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD),
                                        Component.text(" "),
                                        net.kyori.adventure.title.Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofMillis(300), java.time.Duration.ZERO)
                                ));
                            }
                        }
                        player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.5f, 1.5f);
                    }
                    ticks += 2;
                } else {
                    // Animation finished, show actual roles
                    for (PlayerData pd : registered) {
                        Player player = Bukkit.getPlayer(pd.getPlayerId());
                        if (player != null) {
                            if (pd.isImpostor()) {
                                player.showTitle(net.kyori.adventure.title.Title.title(
                                        Component.text("IMPOSTOR").color(NamedTextColor.RED).decorate(TextDecoration.BOLD),
                                        Component.text("Hentikan Investigator menyelesaikan objektifnya!").color(NamedTextColor.GRAY),
                                        net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(500), java.time.Duration.ofSeconds(4), java.time.Duration.ofSeconds(1))
                                ));
                                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1f);
                            } else {
                                // Get the player's objective description
                                String objDesc = "Selesaikan objektifmu!";
                                if (objectiveManager != null) {
                                    List<Objective> objs = objectiveManager.getPlayerObjectives(pd.getPlayerId());
                                    if (!objs.isEmpty()) objDesc = objs.get(0).getDescription();
                                }
                                player.showTitle(net.kyori.adventure.title.Title.title(
                                        Component.text("INVESTIGATOR").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD),
                                        Component.text(objDesc).color(NamedTextColor.YELLOW),
                                        net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(500), java.time.Duration.ofSeconds(4), java.time.Duration.ofSeconds(1))
                                ));
                                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                            }
                        }
                    }

                    this.cancel();

                    // Start the game logic after a short delay to let players read their role
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        meetingCooldownActive = false;
                        lastMeetingEndTime = 0;
                        turnNumber = 0;
                        waitingForFirstMove = true;

                        for (PlayerData pd : registered) {
                            Player player = Bukkit.getPlayer(pd.getPlayerId());
                            if (player != null) {
                                player.getInventory().clear();
                                player.setHealth(20.0);
                                player.setFoodLevel(20);
                                player.setSaturation(5.0f);
                            }
                        }

                        // Play teleport sound
                        for (PlayerData pd : registered) {
                            Player player = Bukkit.getPlayer(pd.getPlayerId());
                            if (player != null) {
                                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
                            }
                        }

                        // Send impostor team notification if multiple impostors
                        List<PlayerData> impostorList = registered.stream()
                                .filter(PlayerData::isImpostor)
                                .collect(Collectors.toList());
                        if (impostorList.size() >= 2) {
                            for (PlayerData pd : impostorList) {
                                Player pImpl = Bukkit.getPlayer(pd.getPlayerId());
                                if (pImpl == null) continue;
                                // Build list of other impostors
                                List<String> otherNames = impostorList.stream()
                                        .filter(other -> !other.getPlayerId().equals(pd.getPlayerId()))
                                        .map(PlayerData::getPlayerName)
                                        .collect(Collectors.toList());
                                String message;
                                if (otherNames.size() == 1) {
                                    message = "Kamu dan " + otherNames.get(0) + " adalah ";
                                } else {
                                    String joined = String.join(", ", otherNames.subList(0, otherNames.size() - 1))
                                            + " dan " + otherNames.get(otherNames.size() - 1);
                                    message = "Kamu, " + joined + " adalah ";
                                }
                                pImpl.sendMessage(Component.text(message).color(NamedTextColor.WHITE)
                                        .append(Component.text("IMPOSTOR").color(NamedTextColor.RED).decorate(TextDecoration.BOLD)));
                            }
                        }

                        turnManager.startNextTurn();
                        scoreboardManager.startUpdates();
                    }, 80L); // 4 seconds delay
                }
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    // ===== End Game =====
    public void endGame() {
        if (turnManager != null) turnManager.stopTurn();
        if (scoreboardManager != null) scoreboardManager.stopUpdates();
        if (voteManager != null) voteManager.cleanup();

        gameState = GameState.LOBBY;
        currentActivePlayerId = null;
        savedGameState = null;
        meetingCooldownActive = false;

        // Clear all player data
        for (PlayerData pd : playerDataMap.values()) {
            pd.setRole(null);
            pd.setEliminated(false);
            pd.clearVote();
        }

        // Teleport all back to meeting world
        worldManager.teleportAllToMeetingWorld(playerDataMap.values());

        // Remove scoreboard
        scoreboardManager.removeScoreboards();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp()) {
                p.sendMessage(Component.text("GAME BERAKHIR! Gunakan /start untuk memulai game baru").color(NamedTextColor.YELLOW));
                if (settingsManager != null) settingsManager.giveSettingsItem(p);
            }
        }
    }

    public void checkWinConditions() {
        if (!isPlaying()) return;
        String sep = "§8========================================";

        // Count active investigators and active impostors
        int activeInvestigators = 0;
        int activeImpostors = 0;
        for (PlayerData pd : playerDataMap.values()) {
            if (pd.isActive()) {
                if (pd.isImpostor()) activeImpostors++;
                else activeInvestigators++;
            }
        }

        // Impostors win if no investigators left, or investigators <= impostors
        if (activeInvestigators == 0 || activeInvestigators <= activeImpostors) {
            // Impostors win
            // Find impostor names for display
            StringBuilder impostorNames = new StringBuilder();
            for (PlayerData pd : playerDataMap.values()) {
                if (pd.isImpostor()) {
                    if (impostorNames.length() > 0) impostorNames.append(", ");
                    impostorNames.append(pd.getPlayerName());
                }
            }
            Bukkit.broadcast(Component.text(sep));
            Bukkit.broadcast(Component.text("IMPOSTOR MENANG!").color(NamedTextColor.RED).decorate(TextDecoration.BOLD));
            Bukkit.broadcast(Component.text(impostorNames.toString() + " adalah impostor").color(NamedTextColor.GRAY));
            Bukkit.broadcast(Component.text(sep));
            for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ENDER_DRAGON_DEATH, 1f, 1f);
            endGame();
            return;
        }

        // Check if objective is completed
        if (objectiveManager != null && objectiveManager.areAllObjectivesCompleted()) {
            // Investigators win
            Bukkit.broadcast(Component.text(sep));
            Bukkit.broadcast(Component.text("INVESTIGATOR MENANG!").color(NamedTextColor.AQUA).decorate(TextDecoration.BOLD));
            Bukkit.broadcast(Component.text("Objektif berhasil diselesaikan!").color(NamedTextColor.GRAY));
            Bukkit.broadcast(Component.text(sep));
            for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            endGame();
            return;
        }

        // Check if all impostors are eliminated
        boolean impostorsAlive = false;
        for (PlayerData pd : playerDataMap.values()) {
            if (pd.isActive() && pd.isImpostor()) {
                impostorsAlive = true;
                break;
            }
        }

        if (!impostorsAlive) {
            Bukkit.broadcast(Component.text(sep));
            Bukkit.broadcast(Component.text("INVESTIGATOR MENANG!").color(NamedTextColor.AQUA).decorate(TextDecoration.BOLD));
            Bukkit.broadcast(Component.text("Semua impostor telah tereliminasi!").color(NamedTextColor.GRAY));
            Bukkit.broadcast(Component.text(sep));
            for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            endGame();
        }
    }



    public void eliminatePlayer(UUID playerId) {
        PlayerData pd = getPlayerData(playerId);
        if (pd != null) {
            pd.setEliminated(true);
            if (objectiveManager != null) {
                objectiveManager.handlePlayerElimination(playerId);
            }
        }
    }

    // ===== Start Meeting =====
    public void startMeeting(Player caller) {
        if (!isPlaying()) return;

        // Cannot call meeting while waiting for first player to move
        if (waitingForFirstMove) {
            caller.sendMessage(Component.text("Tidak bisa memanggil meeting sebelum giliran pertama dimulai!").color(NamedTextColor.RED));
            return;
        }

        if (playersWhoCalledMeeting.contains(caller.getUniqueId())) {
            caller.sendMessage(Component.text("Kamu sudah melakukan meeting sebelumnya!").color(NamedTextColor.RED));
            return;
        }

        playersWhoCalledMeeting.add(caller.getUniqueId());
        caller.playSound(caller.getLocation(), org.bukkit.Sound.BLOCK_BELL_USE, 1.0f, 1.0f);

        org.bukkit.Bukkit.broadcast(Component.text(caller.getName() + " ingin melakukan meeting!")
                .color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));

        if (playersWhoCalledMeeting.size() >= getActivePlayerCount()) {
            playersWhoCalledMeeting.clear();
        }

        // Play alarm sound
        for (PlayerData pd : getActivePlayers()) {
            Player p = Bukkit.getPlayer(pd.getPlayerId());
            if (p != null) {
                p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.0f);
            }
        }

        // Save current game state (full state including inventory, health, etc.)
        savedGameState = turnManager.saveCurrentState();

        // Clear potion effects for meeting world
        for (PlayerData pd : getActivePlayers()) {
            Player p = Bukkit.getPlayer(pd.getPlayerId());
            if (p != null) {
                p.getActivePotionEffects().forEach(effect -> p.removePotionEffect(effect.getType()));
            }
        }

        // Stop turn timer
        turnManager.stopTurn();

        gameState = GameState.MEETING;

        // Teleport all players to meeting world
        worldManager.teleportToMeetingWorldForMeeting(getActivePlayers());

        // Start vote phase after brief delay
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            voteManager.startVoting();
        }, 40L); // 2 second delay
    }

    // ===== Return from Meeting =====
    public void returnFromMeeting(String resultMessage, UUID eliminatedPlayerId) {
        String separator = "§8========================================";
        Bukkit.broadcast(Component.text(separator));
        Bukkit.broadcast(Component.text(resultMessage).color(NamedTextColor.YELLOW).decorate(TextDecoration.BOLD));
        Bukkit.broadcast(Component.text(separator));

        // Clear votes
        for (PlayerData pd : getActivePlayers()) {
            pd.clearVote();
        }
        voteManager.cleanup();

        // Set state back to PLAYING before win check so the teleport etc. can work
        gameState = GameState.PLAYING;

        // Check win condition AFTER setting state
        checkWinConditions();
        // If game ended due to win, stop here
        if (!isPlaying()) return;

        // If there was an eliminated player, keep them in meeting world as spectator
        if (eliminatedPlayerId != null) {
            Player eliminated = Bukkit.getPlayer(eliminatedPlayerId);
            if (eliminated != null) {
                org.bukkit.World mw = worldManager.getMeetingWorld();
                if (mw != null) {
                    eliminated.teleport(worldManager.getRandomizedMeetingSpawn());
                }
                eliminated.setGameMode(org.bukkit.GameMode.SPECTATOR);
                eliminated.setSpectatorTarget(null);
                eliminated.sendMessage(Component.text("Kamu sudah tereliminasi. Tonton permainan!").color(NamedTextColor.RED));
            }
        }

        // We only set meetingCooldownActive here. The actual lastMeetingEndTime 
        // will be recorded when the active player makes their first move.
        meetingCooldownActive = true;

        // Restore state and teleport back (only active players)
        turnManager.restoreStateAndResume(savedGameState, eliminatedPlayerId);

        scoreboardManager.startUpdates();
    }

    // ===== Saved Game State =====
    /**
     * Comprehensive saved state that captures all player data needed to resume
     * after a meeting: active player, timer, inventory, health, hunger, position.
     */
    public static class SavedGameState {
        public final UUID activePlayerId;
        public final int turnTimeLeft;
        public final int turnNumber;

        // Full player state from the active player
        public final ItemStack[] inventory;
        public final ItemStack[] armorContents;
        public final ItemStack[] extraContents;
        public final int heldItemSlot;
        public final double health;
        public final int foodLevel;
        public final float saturation;
        public final float exhaustion;
        public final Location position;
        public final java.util.Collection<org.bukkit.potion.PotionEffect> potionEffects;
        public final int fireTicks;
        public final int freezeTicks;
        public final org.bukkit.util.Vector velocity;

        public SavedGameState(UUID activePlayerId, int turnTimeLeft, int turnNumber,
                              ItemStack[] inventory, ItemStack[] armorContents,
                              ItemStack[] extraContents, int heldItemSlot,
                              double health, int foodLevel, float saturation, float exhaustion,
                              Location position,
                              java.util.Collection<org.bukkit.potion.PotionEffect> potionEffects,
                              int fireTicks, int freezeTicks, org.bukkit.util.Vector velocity) {
            this.activePlayerId = activePlayerId;
            this.turnTimeLeft = turnTimeLeft;
            this.turnNumber = turnNumber;
            this.inventory = inventory;
            this.armorContents = armorContents;
            this.extraContents = extraContents;
            this.heldItemSlot = heldItemSlot;
            this.health = health;
            this.foodLevel = foodLevel;
            this.saturation = saturation;
            this.exhaustion = exhaustion;
            this.position = position;
            this.potionEffects = potionEffects;
            this.fireTicks = fireTicks;
            this.freezeTicks = freezeTicks;
            this.velocity = velocity;
        }
    }
}
