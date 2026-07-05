package com.czipo.sharedControlImpostor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages the entire voting/meeting system:
 * - Giving vote items to players
 * - Creating and handling voting panel (chest UI)
 * - Armor stand displays for votes
 * - Vote tracking and result calculation
 * - Vote timer (1 minute, shown as experience bar)
 */
public class VoteManager {

    private final SharedControlImpostor plugin;
    private final GameManager gameManager;

    // Vote tracking: voter UUID -> voted-for player UUID (null means skip)
    private final Map<UUID, UUID> votes = new HashMap<>();
    private final Map<UUID, Boolean> skipVotes = new HashMap<>();

    // Armor stands for vote display
    private final Map<UUID, ArmorStand> voteArmorStands = new HashMap<>();

    // Vote timer
    private BukkitRunnable voteTimerTask;
    private int voteTimeLeft;
    private static final int VOTE_TIME_SECONDS = 60;
    private boolean votingActive = false;
    private boolean timerStarted = false;
    private BukkitRunnable preVoteTask;

    // Open voting panels tracking
    private final Set<UUID> playersWithOpenPanel = new HashSet<>();

    public VoteManager(SharedControlImpostor plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
    }

    public boolean isTimerStarted() {
        return timerStarted;
    }

    /**
     * Start the voting phase.
     */
    public void startVoting() {
        gameManager.setGameState(GameState.VOTING);
        votingActive = true;
        timerStarted = false; // Starts when first vote is cast
        votes.clear();
        skipVotes.clear();
        playersWithOpenPanel.clear();
        voteTimeLeft = VOTE_TIME_SECONDS;

        List<PlayerData> activePlayers = gameManager.getActivePlayers();

        // Give vote item to each player
        for (PlayerData pd : activePlayers) {
            Player player = Bukkit.getPlayer(pd.getPlayerId());
            if (player != null) {
                giveVoteItem(player);
                // Initialize exp bar for timer display
                player.setExp(1.0f);
                player.setLevel(VOTE_TIME_SECONDS);
            }
        }

        // Start armor stand update task
        startArmorStandUpdateTask();
        
        // Start action bar reminder until someone votes/moves
        if (preVoteTask != null) preVoteTask.cancel();
        preVoteTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!votingActive || timerStarted) {
                    this.cancel();
                    return;
                }
                for (PlayerData pd : gameManager.getActivePlayers()) {
                    Player p = Bukkit.getPlayer(pd.getPlayerId());
                    if (p != null) {
                        p.sendActionBar(Component.text("Buka menu voting dengan klik item di slot 5").color(NamedTextColor.YELLOW));
                    }
                }
            }
        };
        preVoteTask.runTaskTimer(plugin, 0L, 20L);
        
        // Timer will start when first vote is cast
    }

    /**
     * Give the vote item (player head) to a player at slot 5 (hotbar index 4).
     */
    private void giveVoteItem(Player player) {
        ItemStack voteItem = new ItemStack(Material.PLAYER_HEAD, 1);

        SkullMeta meta = (SkullMeta) voteItem.getItemMeta();
        if (meta != null) {
            // Set the head to a specific skin (Czipo0 as specified)
            org.bukkit.profile.PlayerProfile profile = Bukkit.createPlayerProfile("Czipo0");
            meta.setOwnerProfile(profile);
            meta.displayName(Component.text("Vote").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
            voteItem.setItemMeta(meta);
        }

        // Place at slot 5 (hotbar index 4)
        player.getInventory().setItem(4, voteItem);
    }

    /**
     * Open the voting panel for a player.
     */
    public void openVotingPanel(Player voter) {
        PlayerData voterData = gameManager.getPlayerData(voter);
        if (voterData == null || !voterData.isActive()) return;

        List<PlayerData> activePlayers = gameManager.getActivePlayers();
        List<PlayerData> otherPlayers = activePlayers.stream()
                .filter(pd -> !pd.getPlayerId().equals(voter.getUniqueId()))
                .collect(Collectors.toList());

        int playerCount = otherPlayers.size() + 1; // including voter

        // Determine chest size
        int chestSize;
        if (playerCount <= 8) {
            // 3-8 total players: 3 rows (27 slots)
            chestSize = 27;
        } else {
            // 9-29 total players: 6 rows (54 slots)
            chestSize = 54;
        }

        Inventory panel = Bukkit.createInventory(null, chestSize,
                Component.text("VOTING - Pilih Impostor!").color(NamedTextColor.RED).decorate(TextDecoration.BOLD));

        // Fill with gray stained glass pane (empty slots)
        ItemStack glassPane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE, 1);
        ItemMeta glassMeta = glassPane.getItemMeta();
        glassMeta.displayName(Component.text(" "));
        glassPane.setItemMeta(glassMeta);

        for (int i = 0; i < chestSize; i++) {
            panel.setItem(i, glassPane);
        }

        // Place player heads
        if (playerCount <= 8) {
            // Row 2 (slots 9-17), columns 2-8 (slots 10-16)
            int headSlot = 10;
            for (PlayerData pd : otherPlayers) {
                ItemStack head = createPlayerHead(pd);
                panel.setItem(headSlot, head);
                headSlot++;
                if (headSlot > 16) break; // Max 7 heads in row 2
            }

            // Skip vote at row 3, column 5 (slot 22)
            ItemStack skipItem = createSkipVoteItem();
            panel.setItem(22, skipItem);
        } else {
            // Rows 2-5 (slots 9-35), columns 2-8 (slots 10-16, 19-25, 28-34, 37-43)
            int[] rowStarts = {10, 19, 28, 37};
            int headIndex = 0;
            for (int rowStart : rowStarts) {
                for (int col = rowStart; col <= rowStart + 6 && headIndex < otherPlayers.size(); col++) {
                    ItemStack head = createPlayerHead(otherPlayers.get(headIndex));
                    panel.setItem(col, head);
                    headIndex++;
                }
            }

            // Skip vote at row 6, column 5 (slot 49)
            ItemStack skipItem = createSkipVoteItem();
            panel.setItem(49, skipItem);
        }

        voter.openInventory(panel);
        playersWithOpenPanel.add(voter.getUniqueId());
    }

    /**
     * Create a player head ItemStack for voting.
     */
    private ItemStack createPlayerHead(PlayerData pd) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD, 1);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            // Set to the player's own head skin
            org.bukkit.profile.PlayerProfile profile = Bukkit.createPlayerProfile(pd.getPlayerName());
            meta.setOwnerProfile(profile);
            meta.displayName(Component.text(pd.getPlayerName()).color(NamedTextColor.YELLOW));
            meta.lore(java.util.List.of(Component.text("> Klik untuk vote " + pd.getPlayerName() + " sebagai impostor").color(NamedTextColor.GRAY)));
            head.setItemMeta(meta);
        }
        return head;
    }

    /**
     * Create the skip vote item (barrier block).
     */
    private ItemStack createSkipVoteItem() {
        ItemStack skip = new ItemStack(Material.BARRIER, 1);
        ItemMeta meta = skip.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Skip Vote").color(NamedTextColor.GRAY).decorate(TextDecoration.BOLD));
            meta.lore(java.util.List.of(Component.text("Klik untuk melewati voting").color(NamedTextColor.GRAY), Component.text("Kamu tidak akan memilih siapapun").color(NamedTextColor.GRAY)));
            skip.setItemMeta(meta);
        }
        return skip;
    }

    /**
     * Handle a vote click in the voting panel.
     */
    public void handleVoteClick(InventoryClickEvent event) {
        Player voter = (Player) event.getWhoClicked();
        ItemStack clickedItem = event.getCurrentItem();

        if (clickedItem == null || clickedItem.getType() == Material.AIR) return;
        if (!clickedItem.hasItemMeta()) return;

        // Check if this is a player head vote
        if (clickedItem.getType() == Material.PLAYER_HEAD) {
            SkullMeta meta = (SkullMeta) clickedItem.getItemMeta();
            if (meta != null && meta.getPlayerProfile() != null) {
                String votedName = meta.getPlayerProfile().getName();
                if (votedName != null) {
                    Player votedPlayer = Bukkit.getPlayerExact(votedName);
                    if (votedPlayer != null && !votedPlayer.getUniqueId().equals(voter.getUniqueId())) {
                        castVote(voter, votedPlayer.getUniqueId(), false);
                        voter.closeInventory();
                        playersWithOpenPanel.remove(voter.getUniqueId());
                    }
                }
            }
        }
        // Check if this is skip vote
        else if (clickedItem.getType() == Material.BARRIER) {
            castVote(voter, null, true);
            voter.closeInventory();
            playersWithOpenPanel.remove(voter.getUniqueId());
        }

        event.setCancelled(true);
    }

    /**
     * Cast a vote (or change an existing vote).
     */
    private void castVote(Player voter, UUID votedForId, boolean isSkip) {
        UUID voterId = voter.getUniqueId();
        PlayerData voterData = gameManager.getPlayerData(voterId);

        // Remove previous vote display
        removeVoteDisplay(voterId);

        if (isSkip) {
            votes.remove(voterId);
            skipVotes.put(voterId, true);
            if (voterData != null) voterData.setSkipVote(true);

            voter.sendMessage(Component.text("Kamu pilih : Skip Vote").color(NamedTextColor.GRAY));
            // Show skip vote text above voter's head
            createSkipVoteDisplay(voter);
        } else {
            skipVotes.remove(voterId);
            votes.put(voterId, votedForId);
            if (voterData != null) voterData.setVotedFor(votedForId);

            Player votedPlayer = Bukkit.getPlayer(votedForId);
            if (votedPlayer != null) {
                voter.sendMessage(Component.text("Kamu pilih : " + votedPlayer.getName()).color(NamedTextColor.YELLOW));
                // Show voted player's head spinning above voter's head
                createPlayerVoteDisplay(voter, votedPlayer);
            }
        }

        // Play vote sound
        voter.playSound(voter.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);

        if (!timerStarted) {
            timerStarted = true;
            startVoteTimer();
        }

        // Check if all players have voted
        checkAllVoted();
    }

    /**
     * Create a spinning player head armor stand above the voter's head.
     */
    private void createPlayerVoteDisplay(Player voter, Player votedPlayer) {
        // Remove existing armor stand for this voter
        removeVoteDisplay(voter.getUniqueId());

        Location loc = voter.getLocation().add(0, 2.2, 0);

        ArmorStand armorStand = (ArmorStand) voter.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        armorStand.setInvisible(true);
        armorStand.setInvulnerable(true);
        armorStand.setSilent(true);
        armorStand.setMarker(true); // Small hitbox
        armorStand.setGravity(false);
        armorStand.setSmall(true);
        armorStand.setCustomNameVisible(false);
        armorStand.setCollidable(false);

        // Set the head item to voted player's head
        ItemStack headItem = new ItemStack(Material.PLAYER_HEAD, 1);
        SkullMeta headMeta = (SkullMeta) headItem.getItemMeta();
        if (headMeta != null) {
            org.bukkit.profile.PlayerProfile profile = Bukkit.createPlayerProfile(votedPlayer.getName());
            headMeta.setOwnerProfile(profile);
            headItem.setItemMeta(headMeta);
        }
        armorStand.getEquipment().setHelmet(headItem);

        voteArmorStands.put(voter.getUniqueId(), armorStand);
    }

    /**
     * Create a skip vote display above the voter's head.
     */
    private void createSkipVoteDisplay(Player voter) {
        removeVoteDisplay(voter.getUniqueId());

        Location loc = voter.getLocation().add(0, 2.2, 0);

        ArmorStand armorStand = (ArmorStand) voter.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        armorStand.setInvisible(true);
        armorStand.setInvulnerable(true);
        armorStand.setSilent(true);
        armorStand.setMarker(true);
        armorStand.setGravity(false);
        armorStand.setSmall(true);
        armorStand.customName(Component.text("*SKIP VOTE*").color(NamedTextColor.WHITE).decorate(TextDecoration.BOLD));
        armorStand.setCustomNameVisible(true);
        armorStand.setCollidable(false);

        voteArmorStands.put(voter.getUniqueId(), armorStand);
    }

    /**
     * Remove vote display armor stand for a voter.
     */
    private void removeVoteDisplay(UUID voterId) {
        ArmorStand existing = voteArmorStands.remove(voterId);
        if (existing != null) {
            existing.remove();
        }
    }

    /**
     * Public method to remove a player's vote display when they disconnect.
     * Also cleans up their vote from tracking maps.
     */
    public void removePlayerVoteDisplay(UUID playerId) {
        // Remove armor stand display
        removeVoteDisplay(playerId);
        
        // Remove from vote tracking
        votes.remove(playerId);
        skipVotes.remove(playerId);
        playersWithOpenPanel.remove(playerId);
        
        // Clear vote in PlayerData
        PlayerData pd = gameManager.getPlayerData(playerId);
        if (pd != null) {
            pd.clearVote();
        }
        
        if (votingActive) {
            checkAllVoted();
        }
    }

    /**
     * Start the armor stand update task - makes stands follow players and spin.
     */
    private void startArmorStandUpdateTask() {
        new BukkitRunnable() {
            private float rotationAngle = 0;

            @Override
            public void run() {
                if (!votingActive) {
                    this.cancel();
                    return;
                }

                rotationAngle += 12; // Spin speed

                for (Map.Entry<UUID, ArmorStand> entry : voteArmorStands.entrySet()) {
                    UUID voterId = entry.getKey();
                    ArmorStand stand = entry.getValue();
                    Player voter = Bukkit.getPlayer(voterId);

                    if (voter == null || !voter.isOnline()) {
                        stand.remove();
                        continue;
                    }

                    // Make armor stand follow the voter
                    Location newLoc = voter.getLocation().add(0, 2.2, 0);
                    stand.teleport(newLoc);

                    // Spin the armor stand (rotate head)
                    // For player head votes, we rotate the stand itself
                    if (!skipVotes.containsKey(voterId)) {
                        stand.setRotation(rotationAngle % 360, 0);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 2L); // Every 2 ticks for smooth animation
    }

    /**
     * Start the vote timer countdown.
     */
    public void startVoteTimer() {
        // Do NOT check timerStarted here - it's already set by castVote before calling this
        if (voteTimerTask != null) {
            voteTimerTask.cancel();
        }

        voteTimerTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!votingActive) {
                    this.cancel();
                    return;
                }

                if (voteTimeLeft <= 0) {
                    // Timer expired - process results
                    processVoteResults();
                    this.cancel();
                    return;
                }

                // Show timer as experience bar level
                float progress = (float) voteTimeLeft / VOTE_TIME_SECONDS;
                for (PlayerData pd : gameManager.getActivePlayers()) {
                    Player player = Bukkit.getPlayer(pd.getPlayerId());
                    if (player != null) {
                        player.setExp(progress);
                        player.setLevel(voteTimeLeft);
                        player.sendActionBar(Component.text("Waktu Voting: " + voteTimeLeft + "s")
                                .color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
                    }
                }

                // Countdown sound at last 10 seconds
                if (voteTimeLeft <= 10) {
                    for (PlayerData pd : gameManager.getActivePlayers()) {
                        Player player = Bukkit.getPlayer(pd.getPlayerId());
                        if (player != null) {
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.5f, 1.5f);
                        }
                    }
                }

                voteTimeLeft--;
            }
        };

        voteTimerTask.runTaskTimer(plugin, 0L, 20L);
    }

    /**
     * Check if all active players have voted.
     */
    private void checkAllVoted() {
        List<PlayerData> activePlayers = gameManager.getActivePlayers();
        for (PlayerData pd : activePlayers) {
            if (!pd.hasVoted()) return; // Not everyone has voted
        }
        // All voted - process results
        processVoteResults();
    }

    /**
     * Process vote results and determine outcome.
     */
    private void processVoteResults() {
        votingActive = false;

        if (voteTimerTask != null) {
            voteTimerTask.cancel();
            voteTimerTask = null;
        }

        gameManager.setGameState(GameState.VOTE_RESULT);

        // Remove all vote displays
        removeAllVoteDisplays();

        // Remove vote items from all players and close panel
        for (PlayerData pd : gameManager.getActivePlayers()) {
            Player player = Bukkit.getPlayer(pd.getPlayerId());
            if (player != null) {
                player.closeInventory();
                removeVoteItem(player);
                player.setExp(0);
                player.setLevel(0);
            }
        }

        // Calculate vote counts
        Map<UUID, Integer> voteCounts = new HashMap<>();
        int skipCount = 0;

        for (Map.Entry<UUID, UUID> entry : votes.entrySet()) {
            UUID votedFor = entry.getValue();
            voteCounts.merge(votedFor, 1, Integer::sum);
        }
        for (Map.Entry<UUID, Boolean> entry : skipVotes.entrySet()) {
            if (entry.getValue()) skipCount++;
        }

        // Find the player with the most votes
        UUID mostVotedPlayer = null;
        int mostVotes = 0;
        boolean isTie = false;

        for (Map.Entry<UUID, Integer> entry : voteCounts.entrySet()) {
            if (entry.getValue() > mostVotes) {
                mostVotes = entry.getValue();
                mostVotedPlayer = entry.getKey();
                isTie = false;
            } else if (entry.getValue() == mostVotes) {
                isTie = true;
            }
        }

        // Broadcast vote results
        broadcastVoteResults(voteCounts, skipCount);

        // Determine outcome
        if (skipCount > mostVotes || (mostVotes == 0 && skipCount > 0)) {
            // Skip vote wins
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                gameManager.returnFromMeeting("Skip Voting | Permainan di lanjutkan", null);
            }, 60L); // 3 second delay
        } else if (isTie || mostVotedPlayer == null) {
            // Tie - continue game
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                gameManager.returnFromMeeting("Voting Seri | Permainan di lanjutkan", null);
            }, 60L);
        } else {
            // Someone was voted out
            final UUID eliminatedId = mostVotedPlayer;
            PlayerData eliminatedData = gameManager.getPlayerData(eliminatedId);
            if (eliminatedData != null) {
                if (eliminatedData.isImpostor()) {
                    // Impostor found!
                    gameManager.eliminatePlayer(eliminatedId);

                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        gameManager.returnFromMeeting(eliminatedData.getPlayerName() + " tereliminasi", eliminatedId);
                    }, 80L); // 4 second delay for dramatic effect
                } else {
                    // Investigator was wrongly voted out
                    gameManager.eliminatePlayer(eliminatedId);

                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        gameManager.returnFromMeeting(eliminatedData.getPlayerName() + " tereliminasi", eliminatedId);
                    }, 60L);
                }
            }
        }
    }

    /**
     * Broadcast vote results to all players, sorted by votes descending (skip included).
     */
    private void broadcastVoteResults(Map<UUID, Integer> voteCounts, int skipCount) {
        Bukkit.broadcast(Component.text("══════════════════════════════").color(NamedTextColor.GOLD));
        Bukkit.broadcast(Component.text("  HASIL VOTING").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        Bukkit.broadcast(Component.text("══════════════════════════════").color(NamedTextColor.GOLD));

        // Build a combined list of all entries (player votes + skip) sorted by count descending
        List<Map.Entry<UUID, Integer>> sorted = new java.util.ArrayList<>(voteCounts.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        // Interleave skip at correct position based on count
        boolean skipPrinted = false;
        for (Map.Entry<UUID, Integer> entry : sorted) {
            // Print skip before this entry if skip has more or equal votes
            if (!skipPrinted && skipCount >= entry.getValue()) {
                Bukkit.broadcast(Component.text("Skip : " + skipCount + " suara").color(NamedTextColor.GRAY));
                skipPrinted = true;
            }
            PlayerData pd = gameManager.getPlayerData(entry.getKey());
            if (pd != null) {
                Bukkit.broadcast(Component.text(pd.getPlayerName() + " : " + entry.getValue() + " suara")
                        .color(NamedTextColor.WHITE));
            }
        }
        // If skip wasn't printed yet (skip has fewer votes than all players, or no player votes)
        if (!skipPrinted && skipCount > 0) {
            Bukkit.broadcast(Component.text("Skip : " + skipCount + " suara").color(NamedTextColor.GRAY));
        }

        Bukkit.broadcast(Component.text("══════════════════════════════").color(NamedTextColor.GOLD));
    }

    /**
     * Remove all vote display armor stands.
     */
    private void removeAllVoteDisplays() {
        for (ArmorStand stand : voteArmorStands.values()) {
            stand.remove();
        }
        voteArmorStands.clear();
    }

    /**
     * Remove vote item from a player's inventory using Component API.
     */
    private void removeVoteItem(Player player) {
        for (int i = 0; i < 9; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType() == Material.PLAYER_HEAD) {
                if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
                    String name = PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName());
                    if (name.contains("Vote")) {
                        player.getInventory().setItem(i, null);
                    }
                }
            }
        }
    }

    /**
     * Check if a player has an open voting panel.
     */
    public boolean hasOpenPanel(UUID playerId) {
        return playersWithOpenPanel.contains(playerId);
    }

    /**
     * Clean up all vote-related state and entities.
     */
    public void cleanup() {
        votingActive = false;
        timerStarted = false;

        if (voteTimerTask != null) {
            voteTimerTask.cancel();
            voteTimerTask = null;
        }
        if (preVoteTask != null) {
            preVoteTask.cancel();
            preVoteTask = null;
        }

        removeAllVoteDisplays();

        for (PlayerData pd : gameManager.getAllPlayerData()) {
            Player player = Bukkit.getPlayer(pd.getPlayerId());
            if (player != null) {
                removeVoteItem(player);
                player.setExp(0);
                player.setLevel(0);
            }
            pd.clearVote();
        }

        votes.clear();
        skipVotes.clear();
        playersWithOpenPanel.clear();
    }

    public boolean isVotingActive() { return votingActive; }
}
