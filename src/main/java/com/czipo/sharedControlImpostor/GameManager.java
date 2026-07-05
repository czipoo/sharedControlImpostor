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

    public TurnManager getTurnManager() { return turnManager; }
    public VoteManager getVoteManager() { return voteManager; }
    public ScoreboardManager getScoreboardManager() { return scoreboardManager; }
    public WorldManager getWorldManager() { return worldManager; }

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
        if (playerCount <= 3) return 1;
        if (playerCount <= 8) return 2;
        return Math.max(1, playerCount / 3);
    }

    // ===== Turn Time =====
    public int getTurnTimeSeconds() { return turnTimeSeconds; }
    public void setTurnTimeSeconds(int seconds) {
        this.turnTimeSeconds = Math.max(5, seconds); // Minimum 5 seconds
    }

    // ===== Meeting Cooldown =====
    public int getMeetingCooldownSeconds() { return meetingCooldownSeconds; }

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

    // ===== Objective =====
    public Objective getCurrentObjective() { return currentObjective; }
    public void setCurrentObjective(Objective objective) { this.currentObjective = objective; }

    // ===== First Move Tracking =====
    public boolean isWaitingForFirstMove() { return waitingForFirstMove; }
    public void setWaitingForFirstMove(boolean waiting) { this.waitingForFirstMove = waiting; }

    // ===== Saved Game State =====
    public SavedGameState getSavedGameState() { return savedGameState; }
    public void setSavedGameState(SavedGameState savedGameState) { this.savedGameState = savedGameState; }

    public void setGameStartTime(long time) { this.gameStartTime = time; }

    // ===== Start Game =====
    public void startGame() {
        List<PlayerData> registered = getRegisteredPlayers();
        if (registered.size() < 3) {
            Bukkit.broadcast(Component.text("Need at least 3 players to start!").color(NamedTextColor.RED));
            return;
        }

        // Validate impostor count
        int maxImpostors = getMaxImpostorCount(registered.size());
        if (impostorCount > maxImpostors) {
            impostorCount = maxImpostors;
            Bukkit.broadcast(Component.text("Impostor count adjusted to " + impostorCount + " for balance.").color(NamedTextColor.YELLOW));
        }
        if (impostorCount >= registered.size()) {
            Bukkit.broadcast(Component.text("Too many impostors for this player count!").color(NamedTextColor.RED));
            return;
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
        }

        // Pick a random objective from config
        List<Objective.ObjectiveTemplate> templates = Objective.loadFromConfig(plugin.getConfig());
        Collections.shuffle(templates);
        currentObjective = templates.get(0).create();

        // Gacha Animation before starting
        new org.bukkit.scheduler.BukkitRunnable() {
            int ticks = 0;
            final int maxTicks = 40; // 2 seconds of fast shuffling (every 2 ticks)

            @Override
            public void run() {
                if (ticks < maxTicks) {
                    // Shuffling animation
                    for (PlayerData pd : registered) {
                        Player player = Bukkit.getPlayer(pd.getPlayerId());
                        if (player != null) {
                            String randomRole = Math.random() > 0.5 ? "IMPOSTOR" : "INVESTIGATOR";
                            NamedTextColor color = randomRole.equals("IMPOSTOR") ? NamedTextColor.RED : NamedTextColor.GREEN;
                            
                            net.kyori.adventure.title.Title title = net.kyori.adventure.title.Title.title(
                                Component.text(randomRole).color(color).decorate(TextDecoration.BOLD),
                                Component.empty(),
                                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofMillis(500), java.time.Duration.ZERO)
                            );
                            player.showTitle(title);
                            player.playSound(player.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 1.0f, 2.0f);
                        }
                    }
                    ticks += 2;
                } else {
                    // Animation finished, show actual roles
                    for (PlayerData pd : registered) {
                        Player player = Bukkit.getPlayer(pd.getPlayerId());
                        if (player != null) {
                            if (pd.isImpostor()) {
                                net.kyori.adventure.title.Title title = net.kyori.adventure.title.Title.title(
                                    Component.text("IMPOSTOR").color(NamedTextColor.RED).decorate(TextDecoration.BOLD),
                                    Component.text("Clue: " + currentObjective.getClue()).color(NamedTextColor.YELLOW),
                                    net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(250), java.time.Duration.ofSeconds(3), java.time.Duration.ofSeconds(1))
                                );
                                player.showTitle(title);
                                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ENDER_DRAGON_GROWL, 1.0f, 1.0f);
                            } else {
                                net.kyori.adventure.title.Title title = net.kyori.adventure.title.Title.title(
                                    Component.text("INVESTIGATOR").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD),
                                    Component.text(currentObjective.getDescription()).color(NamedTextColor.WHITE),
                                    net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(250), java.time.Duration.ofSeconds(3), java.time.Duration.ofSeconds(1))
                                );
                                player.showTitle(title);
                                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                            }
                        }
                    }

                    this.cancel();

                    // Start the game logic after a short delay to let players read their role
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        gameState = GameState.PLAYING;
                        meetingCooldownActive = false;
                        lastMeetingEndTime = 0;
                        turnNumber = 0;
                        waitingForFirstMove = true;

                        worldManager.teleportToSurvivalWorld(registered);
                        
                        // Play teleport sound
                        for (PlayerData pd : registered) {
                            Player player = Bukkit.getPlayer(pd.getPlayerId());
                            if (player != null) {
                                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
                            }
                        }

                        turnManager.startNextTurn();
                        scoreboardManager.startUpdates();
                    }, 60L); // 3 seconds delay
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
        currentObjective = null;
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
            }
        }
    }



    // ===== Win Condition Checks =====
    public WinCheckResult checkWinCondition() {
        List<PlayerData> activeImpostors = getActivePlayers().stream()
                .filter(PlayerData::isImpostor)
                .collect(Collectors.toList());

        List<PlayerData> activeInvestigators = getActivePlayers().stream()
                .filter(PlayerData::isInvestigator)
                .collect(Collectors.toList());

        if (activeImpostors.isEmpty()) {
            return new WinCheckResult(WinType.INVESTIGATORS_WIN, "Investigator Menang! Semua impostor telah tereliminasi!");
        }
        if (activeInvestigators.size() <= 1) {
            return new WinCheckResult(WinType.IMPOSTOR_WIN, "Impostor Menang! Hanya tersisa " + activeInvestigators.size() + " investigator!");
        }
        return new WinCheckResult(WinType.NO_WIN, null);
    }

    /**
     * Eliminate a player (after voting).
     */
    public void eliminatePlayer(UUID playerId) {
        PlayerData pd = getPlayerData(playerId);
        if (pd != null) {
            pd.setEliminated(true);
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
        Bukkit.broadcast(Component.text(resultMessage).color(NamedTextColor.YELLOW).decorate(TextDecoration.BOLD));

        // Clear votes
        for (PlayerData pd : getActivePlayers()) {
            pd.clearVote();
        }
        voteManager.cleanup();

        // Check win condition
        WinCheckResult winResult = checkWinCondition();
        if (winResult.type != WinType.NO_WIN) {
            handleGameEnd(winResult);
            return;
        }

        // If there was an eliminated player, keep them in meeting world as FREE spectator
        // (they can move around freely, NOT locked to a specific entity)
        if (eliminatedPlayerId != null) {
            Player eliminated = Bukkit.getPlayer(eliminatedPlayerId);
            if (eliminated != null) {
                // Teleport to meeting world spawn as free spectator
                org.bukkit.World mw = worldManager.getMeetingWorld();
                if (mw != null) {
                    eliminated.teleport(worldManager.getRandomizedMeetingSpawn());
                }
                eliminated.setGameMode(org.bukkit.GameMode.SPECTATOR);
                eliminated.setSpectatorTarget(null); // Free spectator, not locked
                eliminated.sendMessage(Component.text("Kamu sudah tereliminasi. Tonton permainan!").color(NamedTextColor.RED));
            }
        }

        // Resume game
        gameState = GameState.PLAYING;
        lastMeetingEndTime = System.currentTimeMillis();
        meetingCooldownActive = true;

        // Restore state and teleport back (only active players)
        turnManager.restoreStateAndResume(savedGameState, eliminatedPlayerId);

        scoreboardManager.startUpdates();
    }

    // ===== Handle Game End =====
    public void handleGameEnd(WinCheckResult winResult) {
        turnManager.stopTurn();
        scoreboardManager.stopUpdates();
        voteManager.cleanup();

        gameState = GameState.FINISHED;

        // Broadcast win message
        Bukkit.broadcast(Component.text("══════════════════════════════").color(NamedTextColor.GOLD));
        Bukkit.broadcast(Component.text(winResult.message).color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        Bukkit.broadcast(Component.text("══════════════════════════════").color(NamedTextColor.GOLD));

        // Find impostor names
        java.util.List<String> impostorNames = new java.util.ArrayList<>();
        for (PlayerData pd : playerDataMap.values()) {
            if (pd.isImpostor()) {
                impostorNames.add(pd.getPlayerName());
            }
        }
        String impostorNameStr = "Unknown";
        if (impostorNames.size() == 1) {
            impostorNameStr = impostorNames.get(0);
        } else if (impostorNames.size() == 2) {
            impostorNameStr = impostorNames.get(0) + " dan " + impostorNames.get(1);
        } else if (impostorNames.size() > 2) {
            impostorNameStr = String.join(", ", impostorNames.subList(0, impostorNames.size() - 1)) + " dan " + impostorNames.get(impostorNames.size() - 1);
        }

        // Show title and play sound, then reset roles
        for (PlayerData pd : playerDataMap.values()) {
            Player player = Bukkit.getPlayer(pd.getPlayerId());
            if (player != null) {
                if (winResult.type == WinType.INVESTIGATORS_WIN) {
                    net.kyori.adventure.title.Title title = net.kyori.adventure.title.Title.title(
                        Component.text("Investigator Menang").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD),
                        Component.text(impostorNameStr + " adalah Impostor").color(NamedTextColor.RED),
                        net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(500), java.time.Duration.ofSeconds(4), java.time.Duration.ofSeconds(1))
                    );
                    player.showTitle(title);
                    player.playSound(player.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
                } else if (winResult.type == WinType.IMPOSTOR_WIN) {
                    net.kyori.adventure.title.Title title = net.kyori.adventure.title.Title.title(
                        Component.text("Impostor Menang").color(NamedTextColor.RED).decorate(TextDecoration.BOLD),
                        Component.text(impostorNameStr + " adalah Impostor").color(NamedTextColor.RED),
                        net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(500), java.time.Duration.ofSeconds(4), java.time.Duration.ofSeconds(1))
                    );
                    player.showTitle(title);
                    player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_WITHER_DEATH, 1.0f, 1.0f);
                }
            }
        }

        // Immediately teleport to meeting world and clear data
        for (PlayerData pd : playerDataMap.values()) {
            pd.setRole(null);
            pd.setEliminated(false);
            pd.clearVote();
        }
        worldManager.teleportAllToMeetingWorld(playerDataMap.values());
        scoreboardManager.removeScoreboards();
        gameState = GameState.LOBBY;
    }

    // ===== Win Type =====
    public enum WinType {
        INVESTIGATORS_WIN,
        IMPOSTOR_WIN,
        NO_WIN
    }

    public static class WinCheckResult {
        public final WinType type;
        public final String message;
        public WinCheckResult(WinType type, String message) {
            this.type = type;
            this.message = message;
        }
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
        public final Location position;
        public final java.util.Collection<org.bukkit.potion.PotionEffect> potionEffects;

        public SavedGameState(UUID activePlayerId, int turnTimeLeft, int turnNumber,
                              ItemStack[] inventory, ItemStack[] armorContents,
                              ItemStack[] extraContents, int heldItemSlot,
                              double health, int foodLevel, float saturation,
                              Location position,
                              java.util.Collection<org.bukkit.potion.PotionEffect> potionEffects) {
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
            this.position = position;
            this.potionEffects = potionEffects;
        }
    }
}
