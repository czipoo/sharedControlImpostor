package com.czipo.sharedControlImpostor;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PiglinBarterEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Sound;
import org.bukkit.block.Biome;

import java.util.*;

public class ObjectiveListener implements Listener {

    private final SharedControlImpostor plugin;
    private final GameManager gameManager;
    
    // For horse tracking
    private final Map<UUID, org.bukkit.Location> horseStartLocation = new HashMap<>();
    // For MLG tracking
    private final Map<UUID, Float> lastFallDistance = new HashMap<>();
    // For sprint tracking
    private final Map<UUID, Double> sprintDistance = new HashMap<>();

    public ObjectiveListener(SharedControlImpostor plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
    }

    private void addProgress(Player player, String objectiveId, int amount) {
        if (!gameManager.isPlaying()) return;
        PlayerData pd = gameManager.getPlayerData(player);
        if (pd == null || !pd.isActive() || pd.isImpostor()) return;
        
        // First try the player's own objectives
        List<Objective> objectives = gameManager.getObjectiveManager().getPlayerObjectives(player.getUniqueId());
        if (objectives == null) return;
        
        for (Objective obj : objectives) {
            if (obj.getId().equals(objectiveId) && !obj.isCompleted()) {
                obj.addProgress(amount);
                if (obj.isCompleted()) {
                    player.sendMessage("§a[Objektif] Kamu telah menyelesaikan: " + obj.getDescription());
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                    gameManager.checkWinConditions();
                }
                gameManager.getScoreboardManager().forceUpdate();
                return;
            }
        }
        
        // In own-objective mode, also check shared objectives (inherited from eliminated players)
        // Any active investigator can complete them
        if (!gameManager.getSettingsManager().isOneObjectiveMode()) {
            List<Objective> sharedObjs = gameManager.getObjectiveManager().getSharedObjectives();
            if (sharedObjs != null) {
                for (Objective obj : sharedObjs) {
                    if (obj.getId().equals(objectiveId) && !obj.isCompleted()) {
                        obj.addProgress(amount);
                        if (obj.isCompleted()) {
                            player.sendMessage("§a[Objektif] Kamu menyelesaikan objektif warisan: " + obj.getDescription());
                            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                            gameManager.checkWinConditions();
                        }
                        gameManager.getScoreboardManager().forceUpdate();
                        return;
                    }
                }
            }
        }
    }
    
    private void setCompleted(Player player, String objectiveId) {
        addProgress(player, objectiveId, 9999);
    }
    
    private boolean hasObjective(Player player, String objectiveId) {
        if (!gameManager.isPlaying()) return false;
        PlayerData pd = gameManager.getPlayerData(player);
        if (pd == null || !pd.isActive() || pd.isImpostor()) return false;
        
        List<Objective> objectives = gameManager.getObjectiveManager().getPlayerObjectives(player.getUniqueId());
        if (objectives == null) return false;
        
        return objectives.stream().anyMatch(o -> o.getId().equals(objectiveId) && !o.isCompleted());
    }
    
    private Objective getObjective(Player player, String objectiveId) {
        if (!gameManager.isPlaying()) return null;
        List<Objective> objectives = gameManager.getObjectiveManager().getPlayerObjectives(player.getUniqueId());
        if (objectives == null) return null;
        for (Objective obj : objectives) {
            if (obj.getId().equals(objectiveId) && !obj.isCompleted()) {
                return obj;
            }
        }
        return null;
    }

    // ==========================================
    // ONE OBJECTIVE MODE EVENTS
    // ==========================================

    @EventHandler
    public void onDimensionChange(PlayerChangedWorldEvent event) {
        Player p = event.getPlayer();
        if (p.getWorld().getEnvironment() == World.Environment.NETHER) {
            setCompleted(p, "one_nether");
            setCompleted(p, "own_nether");
        }
    }

    @EventHandler
    public void onPiglinBarter(PiglinBarterEvent event) {
        for (ItemStack item : event.getOutcome()) {
            if (item.getType() == Material.ENDER_PEARL) {
                // Find nearby players who might have caused this
                for (Entity entity : event.getEntity().getNearbyEntities(10, 10, 10)) {
                    if (entity instanceof Player) {
                        setCompleted((Player) entity, "one_piglin_pearl");
                    }
                }
            }
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        
        EntityType type = event.getEntityType();
        if (type == EntityType.GHAST) {
            for (ItemStack drop : event.getDrops()) {
                if (drop.getType() == Material.GHAST_TEAR) {
                    setCompleted(killer, "one_ghast_tear");
                    break;
                }
            }
        } else if (type == EntityType.WITHER_SKELETON) {
            for (ItemStack drop : event.getDrops()) {
                if (drop.getType() == Material.WITHER_SKELETON_SKULL) {
                    setCompleted(killer, "one_wither_skull");
                    break;
                }
            }
        } else if (type == EntityType.BREEZE) { // Added in 1.21
            addProgress(killer, "one_breeze", 1);
        } else if (type == EntityType.ELDER_GUARDIAN) {
            setCompleted(killer, "one_elder_guardian");
        } else if (type == EntityType.ENDER_DRAGON) {
            setCompleted(killer, "one_ender_dragon");
        } else if (type == EntityType.ZOMBIE) {
            // Check for chicken jockey
            if (event.getEntity().getVehicle() != null && event.getEntity().getVehicle().getType() == EntityType.CHICKEN) {
                if (event.getEntity() instanceof Zombie zombie && zombie.isBaby()) {
                    setCompleted(killer, "own_chicken_jockey");
                }
            }
        } else if (type == EntityType.CREEPER) {
            // Check if killed by skeleton for music disc
            if (event.getEntity().getLastDamageCause() instanceof org.bukkit.event.entity.EntityDamageByEntityEvent damageEvent) {
                if (damageEvent.getDamager() instanceof Skeleton) {
                    for (ItemStack drop : event.getDrops()) {
                        if (drop.getType().name().contains("MUSIC_DISC")) {
                            // Find nearest player
                            for (Entity p : event.getEntity().getNearbyEntities(20, 20, 20)) {
                                if (p instanceof Player) setCompleted((Player) p, "own_music_disc");
                            }
                        }
                    }
                }
            }
        }
        
        // Custom Objective (Kill)
        if (killer != null) {
            List<Objective> objs = gameManager.getObjectiveManager().getPlayerObjectives(killer.getUniqueId());
            if (objs != null) {
                for (Objective obj : objs) {
                    if (obj.getId().startsWith("custom_")) {
                        CustomObjectiveData data = gameManager.getObjectiveManager().getCustomDataByObjective(obj);
                        if (data != null && data.getAction().equals("kill") && type.name().equalsIgnoreCase(data.getTarget())) {
                            addProgress(killer, obj.getId(), 1);
                        }
                    }
                }
            }
        }
    }
    
    @EventHandler
    public void onPickupItem(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        Material type = event.getItem().getItemStack().getType();
        
        if (type == Material.GHAST_TEAR) setCompleted(p, "one_ghast_tear");
        if (type == Material.ANCIENT_DEBRIS) setCompleted(p, "one_ancient_debris");
        if (type == Material.WITHER_SKELETON_SKULL) setCompleted(p, "one_wither_skull");
        if (type == Material.ELYTRA) setCompleted(p, "one_elytra");
        
        // Own objectives
        if (type.name().contains("MUSIC_DISC")) setCompleted(p, "own_music_disc");
        if (type.name().contains("WOOL")) {
            Objective obj = getObjective(p, "own_5_wool");
            if (obj != null) {
                obj.getMemory().add(type.name());
                obj.setProgress(obj.getMemory().size());
                gameManager.getScoreboardManager().forceUpdate();
                if (obj.isCompleted()) {
                    p.sendMessage("§a[Objektif] Kamu telah menyelesaikan: " + obj.getDescription());
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                    gameManager.checkWinConditions();
                }
            }
        }
        
        // Custom Objective (Pickup)
        List<Objective> objs = gameManager.getObjectiveManager().getPlayerObjectives(p.getUniqueId());
        if (objs != null) {
            for (Objective obj : objs) {
                if (obj.getId().startsWith("custom_")) {
                    CustomObjectiveData data = gameManager.getObjectiveManager().getCustomDataByObjective(obj);
                    if (data != null && data.getAction().equals("pickup") && type.name().equalsIgnoreCase(data.getTarget())) {
                        addProgress(p, obj.getId(), event.getItem().getItemStack().getAmount());
                    }
                }
            }
        }
    }
    
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (event.getBlock().getType() == Material.ANCIENT_DEBRIS) {
            setCompleted(p, "one_ancient_debris");
        } else if (event.getBlock().getType() == Material.STONE) {
            addProgress(p, "own_mine_stone", 1);
        }
        
        // Custom Objective (Mining)
        List<Objective> objs = gameManager.getObjectiveManager().getPlayerObjectives(p.getUniqueId());
        if (objs != null) {
            for (Objective obj : objs) {
                if (obj.getId().startsWith("custom_")) {
                    CustomObjectiveData data = gameManager.getObjectiveManager().getCustomDataByObjective(obj);
                    if (data != null && data.getAction().equals("mining") && event.getBlock().getType().name().equalsIgnoreCase(data.getTarget())) {
                        addProgress(p, obj.getId(), 1);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        if (p.getLastDamageCause() instanceof org.bukkit.event.entity.EntityDamageByEntityEvent damageEvent) {
            if (damageEvent.getDamager() instanceof Warden || damageEvent.getDamager().getType() == EntityType.WARDEN) {
                setCompleted(p, "one_warden_death");
                setCompleted(p, "own_warden_death");
            }
        }
    }

    // ==========================================
    // OWN OBJECTIVE MODE EVENTS
    // ==========================================

    @EventHandler
    public void onTrade(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p) {
            if (event.getInventory().getType() == InventoryType.MERCHANT) {
                if (event.getRawSlot() == 2 && event.getCurrentItem() != null && event.getCurrentItem().getType() != Material.AIR) {
                    setCompleted(p, "own_trade_villager");
                }
            }
        }
    }

    @EventHandler
    public void onEnchant(EnchantItemEvent event) {
        setCompleted(event.getEnchanter(), "own_enchant");
    }

    @EventHandler
    public void onTame(EntityTameEvent event) {
        if (event.getOwner() instanceof Player p && event.getEntity() instanceof Cat) {
            setCompleted(p, "own_tame_cat");
        }
    }

    @EventHandler
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (event.getItemStack() != null && event.getItemStack().getType() == Material.AXOLOTL_BUCKET) {
            setCompleted(event.getPlayer(), "own_axolotl_bucket");
        }
    }
    
    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p && event.getEntity() instanceof IronGolem) {
            setCompleted(p, "own_hit_golem");
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        if (!gameManager.isPlaying()) return;
        
        lastFallDistance.put(p.getUniqueId(), p.getFallDistance());
        
        // Check horse riding
        if (p.isInsideVehicle() && p.getVehicle() instanceof Horse) {
            if (hasObjective(p, "own_ride_horse")) {
                if (!horseStartLocation.containsKey(p.getUniqueId())) {
                    horseStartLocation.put(p.getUniqueId(), p.getLocation());
                } else {
                    double dist = horseStartLocation.get(p.getUniqueId()).distance(p.getLocation());
                    if (dist >= 100) {
                        setCompleted(p, "own_ride_horse");
                        horseStartLocation.remove(p.getUniqueId());
                    }
                }
            }
        } else {
            horseStartLocation.remove(p.getUniqueId());
        }
        
        // Check sprint 500 blocks
        if (p.isSprinting()) {
            Objective obj = getObjective(p, "own_sprint_500");
            if (obj != null) {
                double dist = Math.sqrt(Math.pow(event.getTo().getX() - event.getFrom().getX(), 2) + Math.pow(event.getTo().getZ() - event.getFrom().getZ(), 2));
                if (dist > 0) {
                    double currentDist = sprintDistance.getOrDefault(p.getUniqueId(), 0.0) + dist;
                    if (currentDist >= 1.0) {
                        int blocks = (int) currentDist;
                        addProgress(p, "own_sprint_500", blocks);
                        sprintDistance.put(p.getUniqueId(), currentDist - blocks);
                    } else {
                        sprintDistance.put(p.getUniqueId(), currentDist);
                    }
                }
            }
        } else {
            sprintDistance.remove(p.getUniqueId());
        }
        
        // Check bedrock height
        if (p.getLocation().getY() <= p.getWorld().getMinHeight() + 5) {
            setCompleted(p, "own_bedrock_height");
        }
        
        // Check biomes
        Objective biomeObj = getObjective(p, "own_5_biomes");
        if (biomeObj != null) {
            Biome b = p.getLocation().getBlock().getBiome();
            if (!biomeObj.getMemory().contains(b.name())) {
                biomeObj.getMemory().add(b.name());
                biomeObj.setProgress(biomeObj.getMemory().size());
                gameManager.getScoreboardManager().forceUpdate();
                if (biomeObj.isCompleted()) {
                    p.sendMessage("§a[Objektif] Kamu telah menyelesaikan: " + biomeObj.getDescription());
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                    gameManager.checkWinConditions();
                }
            }
        }
    }
    
    @EventHandler
    public void onPlayerConsume(PlayerItemConsumeEvent event) {
        Player p = event.getPlayer();
        Material type = event.getItem().getType();
        if (type == Material.PUFFERFISH) setCompleted(p, "own_pufferfish");
        if (type == Material.GOLDEN_APPLE || type == Material.ENCHANTED_GOLDEN_APPLE) setCompleted(p, "own_gapple");
    }
    
    @EventHandler
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Player p = event.getPlayer();
        if (event.getBucket() == Material.WATER_BUCKET) {
            // Check MLG: fall distance > 20
            float fallDist = lastFallDistance.getOrDefault(p.getUniqueId(), p.getFallDistance());
            if (fallDist >= 20.0f || p.getFallDistance() >= 20.0f) {
                setCompleted(p, "own_mlg_water");
            }
        }
    }
    
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            Material type = event.getClickedBlock().getType();
            if (type.name().contains("CAKE")) {
                setCompleted(p, "own_eat_cake");
            }
            if (type == Material.BEE_NEST || type == Material.BEEHIVE) {
                if (event.getItem() != null && event.getItem().getType() == Material.GLASS_BOTTLE) {
                    if (event.getClickedBlock().getBlockData() instanceof org.bukkit.block.data.type.Beehive hive) {
                        if (hive.getHoneyLevel() == hive.getMaximumHoneyLevel()) {
                            setCompleted(p, "own_honey_bottle");
                        }
                    }
                }
            }
        }
    }
    
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p) {
            // Check full iron armor after a delay to let the equip happen
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (hasObjective(p, "own_iron_armor")) {
                    ItemStack helmet = p.getInventory().getHelmet();
                    ItemStack chest = p.getInventory().getChestplate();
                    ItemStack legs = p.getInventory().getLeggings();
                    ItemStack boots = p.getInventory().getBoots();
                    
                    if (helmet != null && helmet.getType() == Material.IRON_HELMET &&
                        chest != null && chest.getType() == Material.IRON_CHESTPLATE &&
                        legs != null && legs.getType() == Material.IRON_LEGGINGS &&
                        boots != null && boots.getType() == Material.IRON_BOOTS) {
                        setCompleted(p, "own_iron_armor");
                    }
                }
            }, 5L);
        }
    }
    
    @EventHandler
    public void onEntitySpawn(org.bukkit.event.entity.EntitySpawnEvent event) {
        if (event.getEntity() instanceof TNTPrimed tnt) {
            if (tnt.getSource() instanceof Player p) {
                addProgress(p, "own_ignite_tnt", 1);
            }
        }
    }
}
