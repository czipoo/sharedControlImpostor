package com.czipo.sharedControlImpostor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Handles game-related events:
 * - Prevents non-active players from interacting (spectator control)
 * - Tracks objective progress
 * - Handles player disconnects
 * - Handles vote item right-click
 */
public class GameListener implements Listener {

    private final SharedControlImpostor plugin;
    private final GameManager gameManager;

    public GameListener(SharedControlImpostor plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
    }

    // ===== Vote Item Utility =====

    /**
     * Check if an item is the Vote player head item using Component API.
     */
    private boolean isVoteItem(ItemStack item) {
        if (item == null || item.getType() != Material.KNOWLEDGE_BOOK) return false;
        if (!item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) return false;
        String name = PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName());
        return name.contains("Vote");
    }

    // ===== Spectator Restrictions =====

    @EventHandler
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR && gameManager.isPlaying()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onStopSpectating(com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR && gameManager.isPlaying()) {
            event.setCancelled(true);
        }
    }

    /**
     * Keep eliminated spectators inside the meeting world border.
     * Vanilla spectators ignore world border, so enforce it manually.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEliminatedSpectatorMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();
        if (player.getGameMode() != org.bukkit.GameMode.SPECTATOR) return;

        PlayerData pd = gameManager.getPlayerData(player);
        if (pd == null || !pd.isEliminated()) return;

        org.bukkit.World meetingWorld = gameManager.getWorldManager().getMeetingWorld();
        if (meetingWorld == null || !player.getWorld().equals(meetingWorld)) return;

        org.bukkit.WorldBorder border = meetingWorld.getWorldBorder();
        if (border.isInside(event.getTo())) return;

        Location safe = event.getFrom().clone();
        if (!border.isInside(safe)) {
            Location center = border.getCenter();
            safe = new Location(meetingWorld, center.getX(),
                    meetingWorld.getHighestBlockYAt(center) + 1.0,
                    center.getZ(),
                    player.getLocation().getYaw(),
                    player.getLocation().getPitch());
        }
        event.setTo(safe);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();

        // Handle vote item right-click during MEETING/VOTING phase
        if (gameManager.getGameState() == GameState.VOTING ||
            gameManager.getGameState() == GameState.MEETING) {
            ItemStack item = event.getItem();
            if (isVoteItem(item)) {
                gameManager.getVoteManager().openVotingPanel(player);
                event.setCancelled(true);
                return;
            }
        }

        // Check if player is in a game but not the active player
        if (gameManager.isPlaying()) {
            if (!player.getUniqueId().equals(gameManager.getCurrentActivePlayerId())) {
                // Non-active player - cancel all interactions
                event.setCancelled(true);
                return;
            }

            // Active player - check for vote item right-click (shouldn't happen during PLAYING, but safety check)
            ItemStack item = event.getItem();
            if (isVoteItem(item)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        if (gameManager.isPlaying()) {
            UUID activeId = gameManager.getCurrentActivePlayerId();
            if (!player.getUniqueId().equals(activeId)) {
                event.setCancelled(true);
                return;
            }
        }

        // Prevent moving the vote item
        ItemStack clickedItem = event.getCurrentItem();
        ItemStack cursorItem = event.getCursor();
        ItemStack hotbarItem = event.getClick() == org.bukkit.event.inventory.ClickType.NUMBER_KEY ?
                player.getInventory().getItem(event.getHotbarButton()) : null;

        if (isVoteItem(clickedItem) || isVoteItem(cursorItem) || isVoteItem(hotbarItem)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerSwapHandItems(org.bukkit.event.player.PlayerSwapHandItemsEvent event) {
        if (isVoteItem(event.getMainHandItem()) || isVoteItem(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        if (gameManager.isPlaying() && !player.getUniqueId().equals(gameManager.getCurrentActivePlayerId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();

        if (gameManager.isPlaying() && !player.getUniqueId().equals(gameManager.getCurrentActivePlayerId())) {
            event.setCancelled(true);
            return;
        }

    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();

        if (gameManager.isPlaying() && !player.getUniqueId().equals(gameManager.getCurrentActivePlayerId())) {
            event.setCancelled(true);
            return;
        }

    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();

        if (gameManager.isPlaying() && !player.getUniqueId().equals(gameManager.getCurrentActivePlayerId())) {
            // Spectators shouldn't take damage
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) return;

        if (gameManager.isPlaying()) {
            // Only active player can attack
            if (event.getDamager() instanceof Player) {
                Player attacker = (Player) event.getDamager();
                if (!attacker.getUniqueId().equals(gameManager.getCurrentActivePlayerId())) {
                    event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();

        // Vote item cannot be dropped in any game state
        ItemStack item = event.getItemDrop().getItemStack();
        if (isVoteItem(item)) {
            event.setCancelled(true);
            return;
        }

        if (gameManager.isPlaying()) {
            // Non-active players can't drop items
            if (!player.getUniqueId().equals(gameManager.getCurrentActivePlayerId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        // Start playing timer on first move
        if (gameManager.isPlaying() && gameManager.isWaitingForFirstMove()) {
            Player active = gameManager.getCurrentActivePlayer();
            if (active != null && player.getUniqueId().equals(active.getUniqueId())) {
                if (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getZ() != event.getTo().getZ()) {
                    gameManager.setWaitingForFirstMove(false);
                    long now = System.currentTimeMillis();
                    gameManager.setGameStartTime(now);
                    gameManager.setLastMeetingEndTime(now);
                    gameManager.getTurnManager().startTurnTimer();
                }
            }
        }
    }

    // ===== Player Join =====

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        
        // Delay teleportation by 1 tick to ensure player is fully loaded
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            
            if (gameManager.isLobby()) {
                org.bukkit.World meetingWorld = gameManager.getWorldManager().getMeetingWorld();
                if (meetingWorld != null) {
                    org.bukkit.Location spawn = gameManager.getWorldManager().getRandomizedMeetingSpawn();
                    player.teleport(spawn);
                    player.setGameMode(org.bukkit.GameMode.ADVENTURE);
                    player.getInventory().clear();
                    
                    if (player.isOp() && gameManager.getSettingsManager() != null) {
                        gameManager.getSettingsManager().giveSettingsItem(player);
                    }
                }
            } else if (gameManager.isPlaying()) {
                // During gameplay, teleport to survival world if registered
                PlayerData pd = gameManager.getPlayerData(player);
                if (pd != null && pd.isActive()) {
                    org.bukkit.World survivalWorld = gameManager.getWorldManager().getSurvivalWorld();
                    if (survivalWorld != null) {
                        org.bukkit.Location spawn = survivalWorld.getSpawnLocation();
                        player.teleport(spawn);
                        player.setGameMode(org.bukkit.GameMode.SPECTATOR);
                    }
                }
            } else if (gameManager.isMeeting()) {
                // During meeting, teleport to meeting world
                PlayerData pd = gameManager.getPlayerData(player);
                if (pd != null && pd.isActive()) {
                    org.bukkit.World meetingWorld = gameManager.getWorldManager().getMeetingWorld();
                    if (meetingWorld != null) {
                        org.bukkit.Location spawn = gameManager.getWorldManager().getRandomizedMeetingSpawn();
                        player.teleport(spawn);
                        player.setGameMode(org.bukkit.GameMode.ADVENTURE);
                    }
                }
            }
        }, 1L);
    }

    // ===== Player Death =====

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        
        // Only handle deaths during gameplay
        if (!gameManager.isPlaying()) return;
        
        // Check if this player is registered and active
        PlayerData pd = gameManager.getPlayerData(player);
        if (pd == null || !pd.isActive()) return;
        
        // Check settings for One Life and Keep Inventory
        SettingsManager settings = gameManager.getSettingsManager();
        if (settings != null) {
            if (settings.isOneLifeMode()) {
                // One Life: Impostor wins when any investigator dies
                // Find impostor names
                StringBuilder impostorNames = new StringBuilder();
                for (PlayerData ipd : gameManager.getAllPlayerData()) {
                    if (ipd.isImpostor()) {
                        if (impostorNames.length() > 0) impostorNames.append(", ");
                        impostorNames.append(ipd.getPlayerName());
                    }
                }
                final String impostorNamesStr = impostorNames.toString();
                final boolean isOneObj = settings.isOneObjectiveMode();
                String chatMsg = isOneObj
                        ? "Player mati sebelum menyelesaikan objektif!"
                        : "Player mati sebelum menyelesaikan semua objektif!";
                String sep = "§8========================================";

                // Show title to everyone, then end game
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.showTitle(net.kyori.adventure.title.Title.title(
                            Component.text("IMPOSTOR MENANG!").color(NamedTextColor.RED).decorate(TextDecoration.BOLD),
                            Component.text(impostorNamesStr + " adalah impostor").color(NamedTextColor.GRAY),
                            net.kyori.adventure.title.Title.Times.times(
                                    java.time.Duration.ofMillis(500), java.time.Duration.ofSeconds(4), java.time.Duration.ofSeconds(1))
                    ));
                    p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_ENDER_DRAGON_DEATH, 1f, 1f);
                }
                Bukkit.broadcast(Component.text(sep));
                Bukkit.broadcast(Component.text("IMPOSTOR MENANG!").color(NamedTextColor.RED).decorate(TextDecoration.BOLD));
                Bukkit.broadcast(Component.text(chatMsg).color(NamedTextColor.YELLOW));
                Bukkit.broadcast(Component.text(sep));

                // End immediately so turn/scoreboard resume cannot continue
                gameManager.setGameState(GameState.FINISHED);
                if (gameManager.getTurnManager() != null) gameManager.getTurnManager().stopTurn();
                if (gameManager.getScoreboardManager() != null) {
                    gameManager.getScoreboardManager().stopUpdates();
                    gameManager.getScoreboardManager().removeScoreboards();
                }

                // Delay endGame so players can see the message
                Bukkit.getScheduler().runTaskLater(plugin, gameManager::endGame, 100L);
                return;
            }
            
            // Just drop items (default survival behavior)
            event.setKeepInventory(false);
            event.setKeepLevel(false);
        } else {
            event.setKeepInventory(true);
            event.setKeepLevel(true);
            event.setDroppedExp(0);
            event.getDrops().clear();
        }
        
        // Respawn the player immediately in the survival world
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                // Get the survival world spawn or bed spawn
                org.bukkit.World survivalWorld = gameManager.getWorldManager().getSurvivalWorld();
                if (survivalWorld != null) {
                    org.bukkit.Location spawn = player.getBedSpawnLocation();
                    if (spawn == null || !spawn.getWorld().equals(survivalWorld)) {
                        spawn = survivalWorld.getSpawnLocation();
                    }
                    player.spigot().respawn();
                    player.teleport(spawn);
                    
                    // Sync state from active player (if we are a spectator who somehow died)
                    Player activePlayer = gameManager.getCurrentActivePlayer();
                    if (activePlayer != null && !player.getUniqueId().equals(activePlayer.getUniqueId())) {
                        player.getInventory().setContents(activePlayer.getInventory().getContents());
                        player.getInventory().setArmorContents(activePlayer.getInventory().getArmorContents());
                        if (activePlayer.getHealth() > 0) {
                            player.setHealth(activePlayer.getHealth());
                        }
                        player.setFoodLevel(activePlayer.getFoodLevel());
                        
                        // Force back to spectating
                        player.setGameMode(org.bukkit.GameMode.SPECTATOR);
                        player.setSpectatorTarget(activePlayer);
                    } else if (player.getUniqueId().equals(gameManager.getCurrentActivePlayerId())) {
                        // Active player died, set health to full and continue
                        player.setHealth(20.0);
                        player.setFoodLevel(20);
                    }
                }
            }
        }, 1L);
    }

    // ===== Player Respawn =====

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        
        if (gameManager.isLobby()) {
            org.bukkit.World meetingWorld = gameManager.getWorldManager().getMeetingWorld();
            if (meetingWorld != null) {
                event.setRespawnLocation(gameManager.getWorldManager().getMeetingSpawn());
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    player.setGameMode(org.bukkit.GameMode.ADVENTURE);
                }, 1L);
            }
            return;
        }
        
        // Only handle respawns during gameplay
        if (!gameManager.isPlaying()) return;
        
        // Check if this player is registered and active
        PlayerData pd = gameManager.getPlayerData(player);
        if (pd == null || !pd.isActive()) return;
        
        // Force respawn in survival world, check bed spawn first
        org.bukkit.World survivalWorld = gameManager.getWorldManager().getSurvivalWorld();
        if (survivalWorld != null) {
            org.bukkit.Location bedLoc = player.getBedSpawnLocation();
            if (bedLoc != null && bedLoc.getWorld().equals(survivalWorld)) {
                event.setRespawnLocation(bedLoc);
            } else {
                event.setRespawnLocation(survivalWorld.getSpawnLocation());
            }
        }
    }

    // ===== Player Disconnect =====

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        PlayerData pd = gameManager.getPlayerData(player);

        if (pd != null && gameManager.isPlaying()) {
            // Handle disconnect during game
            plugin.getTurnManager().handlePlayerDisconnect(player.getUniqueId());
        }

        // Clean up vote displays and vote item if in meeting/voting phase
        if (gameManager.isMeeting() || gameManager.getGameState() == GameState.VOTING) {
            // Remove this player's vote display armor stand
            plugin.getVoteManager().removePlayerVoteDisplay(player.getUniqueId());
        }
    }

    // ===== Bed Spawn Point =====
    @EventHandler(priority = EventPriority.HIGH)
    public void onBedInteract(PlayerInteractEvent event) {
        if (!gameManager.isPlaying()) return;
        if (event.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            if (event.getClickedBlock().getType().name().endsWith("_BED")) {
                Player player = event.getPlayer();
                // Only active player can set spawn point
                if (!player.getUniqueId().equals(gameManager.getCurrentActivePlayerId())) return;
                
                Location bedLoc = event.getClickedBlock().getLocation();
                // Set for all active players silently
                for (PlayerData pd : gameManager.getActivePlayers()) {
                    Player p = Bukkit.getPlayer(pd.getPlayerId());
                    if (p != null) {
                        p.setBedSpawnLocation(bedLoc, true);
                    }
                }
                // No broadcast - silent spawn point set
            }
        }
    }

    // ===== Item Pickup Restriction =====

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();

        if (gameManager.isPlaying() && !player.getUniqueId().equals(gameManager.getCurrentActivePlayerId())) {
            event.setCancelled(true);
        }
    }

    // ===== Prevent spectator commands =====

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String cmd = event.getMessage().toLowerCase();


        // Allow game commands during spectator mode
        if (cmd.startsWith("/meeting") || cmd.startsWith("/listplayer")) {
            return; // Allow these commands
        }

        // Block other commands for spectators during game
        if (gameManager.isPlaying() && !player.getUniqueId().equals(gameManager.getCurrentActivePlayerId())) {
            // Only block if they're not a mod/admin (OP)
            if (!player.isOp()) {
                event.setCancelled(true);
            }
        }
    }

    // ===== Spectator Locking =====
    @EventHandler
    public void onSpectatorTeleport(PlayerTeleportEvent event) {
        // Do not cancel SPECTATE teleports here because we use setSpectatorTarget
        // which triggers this event. If cancelled, POV mode will not work.
    }

    @EventHandler
    public void onSpectatorDismount(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == org.bukkit.GameMode.SPECTATOR && event.isSneaking()) {
            if (gameManager.isPlaying() && !player.getUniqueId().equals(gameManager.getCurrentActivePlayerId())) {
                event.setCancelled(true);
            }
        }
    }

    // ===== Bed & Respawn Anchor Spawn Sync =====

    /**
     * When any active player sleeps in a bed, sync the bed spawn location to ALL active players.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerBedEnter(PlayerBedEnterEvent event) {
        if (!gameManager.isPlaying()) return;
        if (event.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) return;
        Player player = event.getPlayer();
        if (gameManager.getPlayerData(player) == null) return;

        org.bukkit.block.Block bed = event.getBed();
        Location bedLoc = bed.getLocation();

        // Delay 1 tick to ensure the spawn is set on the player first
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (PlayerData pd : gameManager.getActivePlayers()) {
                Player p = Bukkit.getPlayer(pd.getPlayerId());
                if (p != null && !p.getUniqueId().equals(player.getUniqueId())) {
                    p.setBedSpawnLocation(bedLoc, true);
                }
            }
        }, 1L);
    }

    /**
     * When active player interacts with a respawn anchor (charges it), sync spawn to all players.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteractAnchor(PlayerInteractEvent event) {
        if (!gameManager.isPlaying()) return;
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;
        if (event.getClickedBlock().getType() != Material.RESPAWN_ANCHOR) return;

        Player player = event.getPlayer();
        if (!player.getUniqueId().equals(gameManager.getCurrentActivePlayerId())) return;

        Location anchorLoc = event.getClickedBlock().getLocation();
        // Delay to let the anchor update and player spawn be set
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Location playerSpawn = player.getBedSpawnLocation();
            Location syncLoc = (playerSpawn != null) ? playerSpawn : anchorLoc;
            for (PlayerData pd : gameManager.getActivePlayers()) {
                Player p = Bukkit.getPlayer(pd.getPlayerId());
                if (p != null && !p.getUniqueId().equals(player.getUniqueId())) {
                    p.setBedSpawnLocation(syncLoc, true);
                }
            }
        }, 2L);
    }

    // ===== Potion Effect Sync =====

    /**
     * When active player gains a potion effect, immediately sync it to all spectators.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionEffectAdd(EntityPotionEffectEvent event) {
        if (!gameManager.isPlaying()) return;
        if (!(event.getEntity() instanceof Player)) return;
        if (event.getAction() != EntityPotionEffectEvent.Action.ADDED &&
            event.getAction() != EntityPotionEffectEvent.Action.CHANGED) return;

        Player player = (Player) event.getEntity();
        if (!player.getUniqueId().equals(gameManager.getCurrentActivePlayerId())) return;

        org.bukkit.potion.PotionEffect newEffect = event.getNewEffect();
        if (newEffect == null) return;

        // Delay 1 tick to ensure the effect is applied on the active player
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (PlayerData pd : gameManager.getActivePlayers()) {
                Player p = Bukkit.getPlayer(pd.getPlayerId());
                if (p != null && !p.getUniqueId().equals(player.getUniqueId())) {
                    p.addPotionEffect(newEffect);
                }
            }
        }, 1L);
    }

    /**
     * When active player loses a potion effect, remove it from all spectators too.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPotionEffectRemove(EntityPotionEffectEvent event) {
        if (!gameManager.isPlaying()) return;
        if (!(event.getEntity() instanceof Player)) return;
        if (event.getAction() != EntityPotionEffectEvent.Action.REMOVED &&
            event.getAction() != EntityPotionEffectEvent.Action.CLEARED) return;

        Player player = (Player) event.getEntity();
        if (!player.getUniqueId().equals(gameManager.getCurrentActivePlayerId())) return;

        org.bukkit.potion.PotionEffectType removedType = event.getOldEffect() != null ? event.getOldEffect().getType() : null;
        if (removedType == null) return;

        for (PlayerData pd : gameManager.getActivePlayers()) {
            Player p = Bukkit.getPlayer(pd.getPlayerId());
            if (p != null && !p.getUniqueId().equals(player.getUniqueId())) {
                p.removePotionEffect(removedType);
            }
        }
    }

}
