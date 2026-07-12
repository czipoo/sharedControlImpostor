package com.czipo.sharedControlImpostor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;
import java.util.UUID;

/**
 * Manages turn rotation, timers, countdown, and shared control state.
 * 
 * Core concept: ALL players share the same state (position, inventory, health, hunger).
 * Only the active player can interact. Others spectate and are periodically
 * synced to the active player's state.
 */
public class TurnManager {

    private final SharedControlImpostor plugin;
    private final GameManager gameManager;

    private boolean running = false;
    private BukkitRunnable turnTimerTask;
    private BukkitRunnable syncTask;
    
    private GameManager.SavedGameState resumeStateForNextTurn = null;
    private volatile boolean syncing = false; // Flag to prevent concurrent syncs

    public TurnManager(SharedControlImpostor plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
    }
    
    public void setResumeStateForNextTurn(GameManager.SavedGameState state) {
        this.resumeStateForNextTurn = state;
    }

    /**
     * Skip the current turn.
     */
    public void skipCurrentTurn() {
        if (!gameManager.isPlaying()) return;
        Player activePlayer = gameManager.getCurrentActivePlayer();
        String activeName = activePlayer != null ? activePlayer.getName() : "Player aktif";
        Bukkit.broadcast(Component.text("Skip giliran " + activeName).color(NamedTextColor.YELLOW));
        stopTurnTimer();
        stopSyncTask();
        stopSpectatorMode();
        startNextTurn();
    }

    /**
     * Start the next turn - picks a random active player.
     */
    public void startNextTurn() {
        List<PlayerData> activePlayers = gameManager.getActivePlayers();
        if (activePlayers.isEmpty()) return;

        // Random selection from active (non-eliminated) players, ensuring it's not the same player twice if possible
        PlayerData selected;
        if (activePlayers.size() > 1) {
            do {
                selected = activePlayers.get((int) (Math.random() * activePlayers.size()));
            } while (selected.getPlayerId().equals(gameManager.getCurrentActivePlayerId()));
        } else {
            selected = activePlayers.get(0);
        }

        // Close inventory of previous active player if exists to drop cursor/crafting items
        Player oldActive = gameManager.getCurrentActivePlayer();
        if (oldActive != null) {
            org.bukkit.inventory.ItemStack cursorItem = oldActive.getItemOnCursor();
            if (cursorItem != null && cursorItem.getType() != org.bukkit.Material.AIR) {
                oldActive.getWorld().dropItemNaturally(oldActive.getLocation(), cursorItem);
                oldActive.setItemOnCursor(null);
            }
            org.bukkit.inventory.InventoryView view = oldActive.getOpenInventory();
            if (view.getType() == org.bukkit.event.inventory.InventoryType.CRAFTING || view.getType() == org.bukkit.event.inventory.InventoryType.WORKBENCH) {
                org.bukkit.inventory.Inventory topInventory = view.getTopInventory();
                int slots = (view.getType() == org.bukkit.event.inventory.InventoryType.CRAFTING) ? 5 : 10;
                for (int i = 1; i < slots; i++) {
                    org.bukkit.inventory.ItemStack item = topInventory.getItem(i);
                    if (item != null && item.getType() != org.bukkit.Material.AIR) {
                        oldActive.getWorld().dropItemNaturally(oldActive.getLocation(), item);
                        topInventory.setItem(i, null);
                    }
                }
            }
            oldActive.closeInventory();
        }

        gameManager.setCurrentActivePlayer(selected.getPlayerId());
        gameManager.setTurnNumber(gameManager.getTurnNumber() + 1);
        gameManager.setCurrentTurnTimeLeft(gameManager.getTurnTimeSeconds());

        // Set game modes: active = SURVIVAL, others = SPECTATOR
        setGameModes(null);

        Player activePlayer = Bukkit.getPlayer(selected.getPlayerId());

        // Teleport active player to survival world if it's the very first turn!
        if (gameManager.getTurnNumber() == 1 && gameManager.isWaitingForFirstMove()) {
            if (activePlayer != null) {
                if (resumeStateForNextTurn != null) {
                    if (resumeStateForNextTurn.position != null) {
                        activePlayer.teleport(resumeStateForNextTurn.position);
                        activePlayer.playSound(resumeStateForNextTurn.position, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
                    }
                    if (resumeStateForNextTurn.inventory != null) activePlayer.getInventory().setContents(resumeStateForNextTurn.inventory);
                    if (resumeStateForNextTurn.armorContents != null) activePlayer.getInventory().setArmorContents(resumeStateForNextTurn.armorContents);
                    if (resumeStateForNextTurn.extraContents != null) activePlayer.getInventory().setExtraContents(resumeStateForNextTurn.extraContents);
                    activePlayer.getInventory().setHeldItemSlot(resumeStateForNextTurn.heldItemSlot);
                    activePlayer.setHealth(resumeStateForNextTurn.health);
                    activePlayer.setFoodLevel(resumeStateForNextTurn.foodLevel);
                    activePlayer.setSaturation(resumeStateForNextTurn.saturation);
                    activePlayer.setExhaustion(resumeStateForNextTurn.exhaustion);
                    activePlayer.getActivePotionEffects().forEach(e -> activePlayer.removePotionEffect(e.getType()));
                    if (resumeStateForNextTurn.potionEffects != null) resumeStateForNextTurn.potionEffects.forEach(activePlayer::addPotionEffect);
                    activePlayer.setFireTicks(resumeStateForNextTurn.fireTicks);
                    activePlayer.setFreezeTicks(resumeStateForNextTurn.freezeTicks);
                    resumeStateForNextTurn = null;
                } else {
                    org.bukkit.Location survivalSpawn = gameManager.getWorldManager().getSurvivalSpawn();
                    if (survivalSpawn != null) {
                        activePlayer.teleport(survivalSpawn);
                        activePlayer.playSound(survivalSpawn, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
                    }
                }
            }
        }

        // Broadcast who is playing
        if (activePlayer != null) {
            Bukkit.broadcast(Component.text(selected.getPlayerName() + " sedang bermain!")
                    .color(NamedTextColor.YELLOW).decorate(TextDecoration.BOLD));
        }

        // Start the turn timer countdown OR wait for first move
        if (!gameManager.isWaitingForFirstMove()) {
            // Sync all spectators to active player state after a short delay
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (gameManager.isPlaying()) {
                    syncAllPlayersToActive();
                }
            }, 5L);

            startTurnTimer();
            startPeriodicSync();
        } else {
            // Player 1 waiting for first move: show action bar immediately and keep showing it
            startWaitingActionBar();
        }
        
        running = true;
    }

    /**
     * Show action bar "Giliranmu untuk bermain" continuously until player moves.
     */
    private BukkitRunnable waitingActionBarTask;
    private void startWaitingActionBar() {
        stopWaitingActionBar();
        Player activePlayer = gameManager.getCurrentActivePlayer();
        if (activePlayer == null) return;
        waitingActionBarTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!gameManager.isWaitingForFirstMove() || !gameManager.isPlaying()) {
                    this.cancel();
                    return;
                }
                Player p = gameManager.getCurrentActivePlayer();
                if (p != null) {
                    p.sendActionBar(Component.text("Giliranmu untuk bermain").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD));
                }
            }
        };
        waitingActionBarTask.runTaskTimer(plugin, 0L, 10L); // Every 0.5 second
    }

    private void stopWaitingActionBar() {
        if (waitingActionBarTask != null) {
            try { waitingActionBarTask.cancel(); } catch (IllegalStateException ignored) {}
            waitingActionBarTask = null;
        }
    }

    /**
     * Synchronize all players' inventory, health, hunger, armor, and position
     * to match the currently active player. This is the core "shared control" mechanism.
     * Uses a flag to prevent concurrent syncs.
     */
    private void syncAllPlayersToActive() {
        if (syncing) return; // Prevent concurrent syncs
        syncing = true;
        
        try {
            Player activePlayer = gameManager.getCurrentActivePlayer();
            if (activePlayer == null) return;

            for (PlayerData pd : gameManager.getRegisteredPlayers()) {
                Player player = Bukkit.getPlayer(pd.getPlayerId());
                if (player == null || player.getUniqueId().equals(activePlayer.getUniqueId())) continue;

                // Sync position and rotation
                player.teleport(activePlayer.getLocation());
                
                if (pd.isEliminated()) continue;

                // Sync inventory contents
                player.getInventory().setContents(activePlayer.getInventory().getContents());
                player.getInventory().setArmorContents(activePlayer.getInventory().getArmorContents());
                player.getInventory().setExtraContents(activePlayer.getInventory().getExtraContents());
                player.getInventory().setHeldItemSlot(activePlayer.getInventory().getHeldItemSlot());

                // Sync health
                player.setHealth(activePlayer.getHealth());

                // Sync hunger and saturation
                player.setFoodLevel(activePlayer.getFoodLevel());
                player.setSaturation(activePlayer.getSaturation());

                // Sync remaining air (if underwater)
                player.setMaximumAir(activePlayer.getMaximumAir());
                player.setRemainingAir(activePlayer.getRemainingAir());

                // Sync fire ticks
                player.setFireTicks(activePlayer.getFireTicks());

                // Sync fall distance
                player.setFallDistance(activePlayer.getFallDistance());

                // Sync potion effects
                for (org.bukkit.potion.PotionEffect effect : player.getActivePotionEffects()) {
                    player.removePotionEffect(effect.getType());
                }
                player.addPotionEffects(activePlayer.getActivePotionEffects());
                
                // Sync EXP and Level
                player.setExp(activePlayer.getExp());
                player.setLevel(activePlayer.getLevel());
                
                // Sync Bed Spawn Location
                if (activePlayer.getBedSpawnLocation() != null) {
                    player.setBedSpawnLocation(activePlayer.getBedSpawnLocation(), true);
                } else {
                    player.setBedSpawnLocation(null, true);
                }

                // Sync freeze ticks
                player.setFreezeTicks(activePlayer.getFreezeTicks());

                // Sync exhaustion
                player.setExhaustion(activePlayer.getExhaustion());

                // Sync velocity (transfer momentum)
                player.setVelocity(activePlayer.getVelocity().clone());
            }
            
            // Update mob targets
            for (org.bukkit.entity.Entity entity : activePlayer.getNearbyEntities(32, 32, 32)) {
                if (entity instanceof org.bukkit.entity.Mob) {
                    org.bukkit.entity.Mob mob = (org.bukkit.entity.Mob) entity;
                    if (mob.getTarget() instanceof Player && !mob.getTarget().getUniqueId().equals(activePlayer.getUniqueId())) {
                        mob.setTarget(activePlayer);
                    }
                }
            }
        } finally {
            syncing = false;
        }
    }

    /**
     * Set game modes: active player = SURVIVAL, all others = SPECTATOR.
     * Spectators will be forced to spectate the active player.
     * @param eliminatedPlayerId No longer used, can be null
     */
    private void setGameModes(UUID eliminatedPlayerId) {
        UUID activeId = gameManager.getCurrentActivePlayerId();
        Player activePlayer = gameManager.getCurrentActivePlayer();

        // Iterate over ALL registered players so eliminated players also get their spectator target updated
        for (PlayerData pd : gameManager.getRegisteredPlayers()) {

            Player player = Bukkit.getPlayer(pd.getPlayerId());
            if (player == null) continue;

            if (player.getUniqueId().equals(activeId)) {
                // Active player - full control
                player.setGameMode(GameMode.SURVIVAL);
                player.setAllowFlight(false);
                player.setFlying(false);
                player.setInvisible(false);
                player.setCollidable(true);
                // Remove vote item if present (from previous meeting)
                removeVoteItem(player);
            } else {
                // Spectator
                removeVoteItem(player);
                if (!gameManager.isWaitingForFirstMove()) {
                    player.setGameMode(GameMode.SPECTATOR);
                    player.setCollidable(false);
                    player.setInvisible(false);
                    // Force spectator to spectate the active player (delay by 1 tick to ensure teleport finishes)
                    if (activePlayer != null) {
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            if (player.isOnline() && player.getGameMode() == GameMode.SPECTATOR) {
                                player.teleport(activePlayer.getLocation());
                                player.setSpectatorTarget(activePlayer);
                            }
                        }, 10L);
                    }
                }
            }
        }
    }

    /**
     * Remove any vote items from a player's inventory using Component API.
     */
    private void removeVoteItem(Player player) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType() == Material.KNOWLEDGE_BOOK) {
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
     * Start the turn timer countdown.
     * Handles the 3-second countdown with chat messages and sound effects.
     */
    public void startTurnTimer() {
        stopTurnTimer(); // Stop any existing timer
        stopWaitingActionBar(); // Stop waiting action bar if running

        // Notify active player with action bar + sound, and ensure spectator targets are set
        Player activePlayer = gameManager.getCurrentActivePlayer();
        if (activePlayer != null) {
            activePlayer.sendActionBar(Component.text("Giliranmu untuk bermain").color(NamedTextColor.GREEN).decorate(TextDecoration.BOLD));
            activePlayer.playSound(activePlayer.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        }

        if (syncTask == null || syncTask.isCancelled()) {
            startPeriodicSync();
        }

        turnTimerTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!running || !gameManager.isPlaying()) {
                    this.cancel();
                    return;
                }

                int timeLeft = gameManager.getCurrentTurnTimeLeft();
                if (timeLeft <= 0) {
                    stopSpectatorMode();
                    startNextTurn();
                    return;
                }

                timeLeft--;
                gameManager.setCurrentTurnTimeLeft(timeLeft);
                gameManager.getScoreboardManager().forceUpdate();

                if (timeLeft > 0 && timeLeft <= 3) {
                    Bukkit.broadcast(Component.text(timeLeft + " detik sebelum giliran berikutnya")
                            .color(NamedTextColor.RED));

                    for (PlayerData pd : gameManager.getActivePlayers()) {
                        Player player = Bukkit.getPlayer(pd.getPlayerId());
                        if (player != null) {
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f,
                                    timeLeft == 1 ? 2.0f : 1.0f);
                        }
                    }
                } else if (timeLeft <= 0) {
                    stopSpectatorMode();
                    startNextTurn();
                }
            }
        };

        turnTimerTask.runTaskTimer(plugin, 20L, 20L); // Starts after 1 second
    }

    /**
     * Start periodic sync task that keeps all spectators aligned with the active player.
     * Runs every 5 ticks (250ms) for smooth following.
     */
    private void startPeriodicSync() {
        stopSyncTask(); // Stop any existing sync task

        syncTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (!running || !gameManager.isPlaying()) {
                    this.cancel();
                    return;
                }

                Player activePlayer = gameManager.getCurrentActivePlayer();
                if (activePlayer == null) return;

                UUID activeId = gameManager.getCurrentActivePlayerId();

                for (PlayerData pd : gameManager.getRegisteredPlayers()) {
                    Player player = Bukkit.getPlayer(pd.getPlayerId());
                    if (player == null || player.getUniqueId().equals(activeId)) continue;

                    // --- PERBAIKAN 1: PAKSA LEPAS SAAT TARGET MATI ---
                    if (activePlayer.isDead() || !activePlayer.isOnline()) {
                        // Jika spectator masih menempel ke mayat, paksa lepas!
                        if (player.getSpectatorTarget() != null) {
                            player.setSpectatorTarget(null); 
                        }
                        continue; // Skip sinkronisasi sampai target hidup kembali
                    }

                    // Pastikan mode penonton tetap terjaga
                    if (player.getGameMode() != GameMode.SPECTATOR) {
                        player.setGameMode(GameMode.SPECTATOR);
                        player.setCollidable(false);
                    }

                    // --- PERBAIKAN 2: JEDA TELEPORTASI & KUNCI KAMERA ---
                    org.bukkit.entity.Entity currentTarget = player.getSpectatorTarget();
                    if (currentTarget == null || !currentTarget.getUniqueId().equals(activeId)) {
        
                        player.setSpectatorTarget(null); // Pastikan status server bersih dulu
                        player.teleport(activePlayer.getLocation()); // Pindahkan fisik penonton ke target
                        player.playSound(activePlayer.getLocation(), org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f); // Teleport sound for spectator
        
                        // Beri waktu 5 tick (250ms) agar klien pemain punya waktu untuk memuat chunk dunia
                        // sebelum kamera dipaksa menempel.
                        Bukkit.getScheduler().runTaskLater(plugin, () -> {
                            // Cek lagi apakah setelah 5 tick pemain masih online dan target belum mati
                            if (player.isOnline() && !activePlayer.isDead()) {
                                player.setSpectatorTarget(activePlayer);
                            }
                        }, 5L); 
                    }
                    
                    if (pd.isEliminated()) continue; // Do not sync inventory/health for eliminated players

                    // --- SINKRONISASI STATUS ---
                    player.getInventory().setContents(activePlayer.getInventory().getContents());
                    player.getInventory().setArmorContents(activePlayer.getInventory().getArmorContents());
                    player.getInventory().setExtraContents(activePlayer.getInventory().getExtraContents());
                    player.getInventory().setHeldItemSlot(activePlayer.getInventory().getHeldItemSlot());
                    player.setHealth(activePlayer.getHealth());
                    player.setFoodLevel(activePlayer.getFoodLevel());
                    player.setSaturation(activePlayer.getSaturation());
                }
            }
        };

        syncTask.runTaskTimer(plugin, 5L, 5L); // Every 5 ticks
    }

    /**
     * Stop the turn timer.
     */
    public void stopTurn() {
        stopTurnTimer();
        stopSyncTask();
        stopWaitingActionBar();
        stopSpectatorMode();
        running = false;
    }

    private void stopTurnTimer() {
        if (turnTimerTask != null) {
            try {
                turnTimerTask.cancel();
            } catch (IllegalStateException ignored) {}
            turnTimerTask = null;
        }
    }

    private void stopSyncTask() {
        if (syncTask != null) {
            try {
                syncTask.cancel();
            } catch (IllegalStateException ignored) {}
            syncTask = null;
        }
    }

    /**
     * Remove spectator mode from all players (restore to SURVIVAL).
     */
    private void stopSpectatorMode() {
        for (PlayerData pd : gameManager.getActivePlayers()) {
            Player player = Bukkit.getPlayer(pd.getPlayerId());
            if (player != null) {
                if (player.getGameMode() == GameMode.SPECTATOR) {
                    player.setSpectatorTarget(null);
                }
                player.setGameMode(GameMode.SURVIVAL);
                player.setAllowFlight(false);
                player.setFlying(false);
            }
        }
    }

    /**
     * Save the current game state (for meeting interruption).
     * Captures who was playing, how much time was left, and the full player state
     * (inventory, health, hunger, armor, position) from the active player.
     */
    public GameManager.SavedGameState saveCurrentState() {
        Player activePlayer = gameManager.getCurrentActivePlayer();

        // Capture full state from the active player
        ItemStack[] inventory = null;
        ItemStack[] armorContents = null;
        ItemStack[] extraContents = null;
        int heldItemSlot = 0;
        double health = 20.0;
        int foodLevel = 20;
        float saturation = 5.0f;
        Location position = null;
        java.util.Collection<org.bukkit.potion.PotionEffect> potionEffects = null;
        int fireTicks = 0;
        int freezeTicks = 0;
        org.bukkit.util.Vector velocity = new org.bukkit.util.Vector(0, 0, 0);
        float exhaustion = 0.0f;

        if (activePlayer != null) {
            // Clone inventory arrays to prevent modification
            inventory = cloneItemStackArray(activePlayer.getInventory().getContents());
            armorContents = cloneItemStackArray(activePlayer.getInventory().getArmorContents());
            extraContents = cloneItemStackArray(activePlayer.getInventory().getExtraContents());
            heldItemSlot = activePlayer.getInventory().getHeldItemSlot();
            health = activePlayer.getHealth();
            foodLevel = activePlayer.getFoodLevel();
            saturation = activePlayer.getSaturation();
            position = activePlayer.getLocation().clone();
            potionEffects = new java.util.ArrayList<>(activePlayer.getActivePotionEffects());
            fireTicks = activePlayer.getFireTicks();
            freezeTicks = activePlayer.getFreezeTicks();
            velocity = activePlayer.getVelocity().clone();
            exhaustion = activePlayer.getExhaustion();
        }

        return new GameManager.SavedGameState(
                gameManager.getCurrentActivePlayerId(),
                gameManager.getCurrentTurnTimeLeft(),
                gameManager.getTurnNumber(),
                inventory,
                armorContents,
                extraContents,
                heldItemSlot,
                health,
                foodLevel,
                saturation,
                exhaustion,
                position,
                potionEffects,
                fireTicks,
                freezeTicks,
                velocity
        );
    }

    /**
     * Deep clone an ItemStack array to prevent reference issues.
     */
    private ItemStack[] cloneItemStackArray(ItemStack[] original) {
        if (original == null) return null;
        ItemStack[] cloned = new ItemStack[original.length];
        for (int i = 0; i < original.length; i++) {
            cloned[i] = original[i] != null ? original[i].clone() : null;
        }
        return cloned;
    }

    /**
     * Restore game state and resume from where we left off (after meeting).
     * Restores inventory, health, hunger, position, and turn state.
     * @param eliminatedPlayerId If not null, this player stays in meeting world
     */
    public void restoreStateAndResume(GameManager.SavedGameState savedState, UUID eliminatedPlayerId) {
        // Check if the previously active player has been eliminated
        boolean activeWasEliminated = eliminatedPlayerId != null
                && eliminatedPlayerId.equals(savedState.activePlayerId);

        List<PlayerData> activePlayers = gameManager.getActivePlayers();
        org.bukkit.World survivalWorld = gameManager.getWorldManager().getSurvivalWorld();

        // Determine restore location — must be in survival world
        Location restoreLocation;
        if (savedState.position != null && survivalWorld != null
                && savedState.position.getWorld() != null
                && savedState.position.getWorld().equals(survivalWorld)) {
            restoreLocation = savedState.position;
        } else if (survivalWorld != null) {
            restoreLocation = gameManager.getWorldManager().getSurvivalSpawn();
        } else {
            restoreLocation = null;
        }

        // Step 1: If active player was eliminated, do a fresh turn
        if (activeWasEliminated) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                // Setup turn state - waitingForFirstMove so timer starts when player moves
                gameManager.setWaitingForFirstMove(true);
                gameManager.setCurrentTurnTimeLeft(gameManager.getTurnTimeSeconds()); // Timer swap dari awal
                startNextTurn(); // Gacha giliran (memilih pemain aktif baru)

                Player newActive = gameManager.getCurrentActivePlayer();
                if (newActive != null) {
                    if (restoreLocation != null) {
                        newActive.teleport(restoreLocation);
                        newActive.playSound(restoreLocation, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
                    }
                    
                    // Mewariskan state tubuh sebelumnya ke pemain baru
                    if (savedState.inventory != null) newActive.getInventory().setContents(savedState.inventory);
                    if (savedState.armorContents != null) newActive.getInventory().setArmorContents(savedState.armorContents);
                    if (savedState.extraContents != null) newActive.getInventory().setExtraContents(savedState.extraContents);
                    newActive.getInventory().setHeldItemSlot(savedState.heldItemSlot);
                    newActive.setHealth(savedState.health);
                    newActive.setFoodLevel(savedState.foodLevel);
                    newActive.setSaturation(savedState.saturation);
                    newActive.setExhaustion(savedState.exhaustion);
                    newActive.getActivePotionEffects().forEach(e -> newActive.removePotionEffect(e.getType()));
                    if (savedState.potionEffects != null) savedState.potionEffects.forEach(newActive::addPotionEffect);
                    newActive.setFireTicks(savedState.fireTicks);
                    newActive.setFreezeTicks(savedState.freezeTicks);
                    removeVoteItem(newActive);
                }

                // Spectators stay in meeting world and DO NOT switch to spectator mode yet
                for (PlayerData pd : gameManager.getActivePlayers()) {
                    if (newActive != null && pd.getPlayerId().equals(newActive.getUniqueId())) continue;
                    Player player = Bukkit.getPlayer(pd.getPlayerId());
                    if (player != null) {
                        removeVoteItem(player);
                    }
                }

                // Do NOT start periodic sync yet, it will start when the player moves
                running = true;
                scoreboardUpdate();
            }, 20L);
            return;
        }

        // Step 2: Active player was NOT eliminated - resume from saved state
        UUID activeId = savedState.activePlayerId;
        Player activePlayer = Bukkit.getPlayer(activeId);

        // Teleport active player first
        if (activePlayer != null && restoreLocation != null) {
            activePlayer.teleport(restoreLocation);
            activePlayer.playSound(restoreLocation, org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
        }

        // After 1 tick, restore state to active player, and set spectators to spectator mode (without teleporting)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // Restore full saved state to the active player
            if (activePlayer != null) {
                if (savedState.inventory != null) activePlayer.getInventory().setContents(savedState.inventory);
                if (savedState.armorContents != null) activePlayer.getInventory().setArmorContents(savedState.armorContents);
                if (savedState.extraContents != null) activePlayer.getInventory().setExtraContents(savedState.extraContents);
                activePlayer.getInventory().setHeldItemSlot(savedState.heldItemSlot);
                activePlayer.setHealth(savedState.health);
                activePlayer.setFoodLevel(savedState.foodLevel);
                activePlayer.setSaturation(savedState.saturation);
                activePlayer.setExhaustion(savedState.exhaustion);
                activePlayer.getActivePotionEffects().forEach(e -> activePlayer.removePotionEffect(e.getType()));
                if (savedState.potionEffects != null) savedState.potionEffects.forEach(activePlayer::addPotionEffect);
                activePlayer.setFireTicks(savedState.fireTicks);
                activePlayer.setFreezeTicks(savedState.freezeTicks);
                removeVoteItem(activePlayer);
                
                activePlayer.setGameMode(GameMode.SURVIVAL);
                activePlayer.setAllowFlight(false);
                activePlayer.setFlying(false);
                activePlayer.setInvisible(false);
                activePlayer.setCollidable(true);
            }

            // Spectators stay in meeting world and DO NOT switch to spectator mode yet
            for (PlayerData pd : gameManager.getActivePlayers()) {
                if (pd.getPlayerId().equals(activeId)) continue;
                Player player = Bukkit.getPlayer(pd.getPlayerId());
                if (player == null) continue;
                
                removeVoteItem(player);
            }

            // Set up turn state - waitingForFirstMove so timer starts when player moves
            gameManager.setCurrentActivePlayer(activeId);
            gameManager.setTurnNumber(savedState.turnNumber);
            gameManager.setCurrentTurnTimeLeft(savedState.turnTimeLeft); // Resume from saved time
            gameManager.setWaitingForFirstMove(true); // Timer starts when player moves

            // Start waiting action bar
            startWaitingActionBar();
            
            // DO NOT start periodic sync yet, it will start when the player moves
            running = true;
            scoreboardUpdate();

            // Broadcast who's playing
            if (activePlayer != null) {
                Bukkit.broadcast(Component.text(activePlayer.getName() + " sedang bermain! (resumed)")
                        .color(NamedTextColor.YELLOW).decorate(TextDecoration.BOLD));
            }
        }, 20L); // 1 second after active player teleports
    }

    private void scoreboardUpdate() {
        if (gameManager.getScoreboardManager() != null) {
            gameManager.getScoreboardManager().startUpdates();
        }
    }

    /**
     * Force-sync all players' state from active player.
     */
    public void forceSyncAll() {
        if (!gameManager.isPlaying()) return;
        syncAllPlayersToActive();
    }

    public boolean isRunning() { return running; }

    /**
     * Handle when a player disconnects during their turn.
     */
    public void handlePlayerDisconnect(UUID playerId) {
        if (!running) return;

        if (playerId.equals(gameManager.getCurrentActivePlayerId())) {
            // Active player disconnected - move to next turn
            stopSpectatorMode();
            startNextTurn();
        }
        // If a spectator disconnects, no special handling needed
    }
}
