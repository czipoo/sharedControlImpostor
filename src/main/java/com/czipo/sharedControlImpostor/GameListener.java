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
        if (item == null || item.getType() != Material.PLAYER_HEAD) return false;
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

        // Track objective progress
        trackObjectiveProgress(event);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();

        if (gameManager.isPlaying() && !player.getUniqueId().equals(gameManager.getCurrentActivePlayerId())) {
            event.setCancelled(true);
            return;
        }

        // Track objective progress (place blocks)
        if (gameManager.isPlaying() && gameManager.getCurrentObjective() != null) {
            Objective objective = gameManager.getCurrentObjective();
            if (objective.getType() == Objective.ObjectiveType.PLACE_BLOCKS) {
                objective.addProgress(1);
                if (objective.isCompleted()) {
                    checkObjectiveCompletion(objective);
                }
            }
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
                    gameManager.setGameStartTime(System.currentTimeMillis());
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
        
        // Keep the player in the survival world by setting keep inventory and not dropping exp
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        // Clear the drops list to prevent items from dropping
        event.getDrops().clear();
        
        // Respawn the player immediately in the survival world
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) {
                // Get the survival world spawn
                org.bukkit.World survivalWorld = gameManager.getWorldManager().getSurvivalWorld();
                if (survivalWorld != null) {
                    org.bukkit.Location spawn = survivalWorld.getSpawnLocation();
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
        
        // Force respawn in survival world
        org.bukkit.World survivalWorld = gameManager.getWorldManager().getSurvivalWorld();
        if (survivalWorld != null) {
            event.setRespawnLocation(survivalWorld.getSpawnLocation());
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

    // ===== Objective Tracking =====

    private void trackObjectiveProgress(BlockBreakEvent event) {
        if (!gameManager.isPlaying()) return;
        Objective objective = gameManager.getCurrentObjective();
        if (objective == null) return;

        Material broken = event.getBlock().getType();

        switch (objective.getType()) {
            case CHOP_LOGS:
                if (broken == objective.getTargetMaterial()) {
                    objective.addProgress(1);
                    checkObjectiveCompletion(objective);
                }
                break;
            case MINE_ORE:
                if (broken == objective.getTargetMaterial()) {
                    objective.addProgress(1);
                    checkObjectiveCompletion(objective);
                }
                break;
            default:
                break;
        }
    }

    private void checkObjectiveCompletion(Objective objective) {
        if (objective.isCompleted()) {
            // Objective completed = Investigators win immediately, regardless of player counts
            gameManager.handleGameEnd(new GameManager.WinCheckResult(
                    GameManager.WinType.INVESTIGATORS_WIN,
                    "Investigator Menang! Objektif berhasil dilakukan!"
            ));
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

        // Allow game commands during spectator mode
        String cmd = event.getMessage().toLowerCase();
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

    // ===== Crafting Objective Tracking =====

    @EventHandler
    public void onCraftItem(org.bukkit.event.inventory.CraftItemEvent event) {
        if (!gameManager.isPlaying()) return;
        if (!(event.getWhoClicked() instanceof Player)) return;

        Player player = (Player) event.getWhoClicked();
        if (!player.getUniqueId().equals(gameManager.getCurrentActivePlayerId())) return;

        Objective objective = gameManager.getCurrentObjective();
        if (objective == null) return;

        if (objective.getType() == Objective.ObjectiveType.CRAFT_ITEM) {
            ItemStack result = event.getRecipe().getResult();
            if (result.getType() == objective.getTargetMaterial()) {
                objective.addProgress(1);
                checkObjectiveCompletion(objective);
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
