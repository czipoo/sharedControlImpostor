package com.czipo.sharedControlImpostor;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
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

    /** Item entity UUIDs that should not count for "dapatkan item" (player drops / block drops) */
    private final Set<UUID> ignoredPickupItems = new HashSet<>();
    /** Items a player put into containers — taking them back should not count */
    private final Map<UUID, Map<Material, Integer>> depositedItems = new HashMap<>();

    public ObjectiveListener(SharedControlImpostor plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
    }

    private void addProgress(Player player, String objectiveId, int amount) {
        if (!gameManager.isPlaying()) return;
        if (!player.getUniqueId().equals(gameManager.getCurrentActivePlayerId())) return;
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
        if (!player.getUniqueId().equals(gameManager.getCurrentActivePlayerId())) return false;
        PlayerData pd = gameManager.getPlayerData(player);
        if (pd == null || !pd.isActive() || pd.isImpostor()) return false;
        
        List<Objective> objectives = gameManager.getObjectiveManager().getPlayerObjectives(player.getUniqueId());
        if (objectives == null) return false;
        
        return objectives.stream().anyMatch(o -> o.getId().equals(objectiveId) && !o.isCompleted());
    }
    
    private Objective getObjective(Player player, String objectiveId) {
        if (!gameManager.isPlaying()) return null;
        if (!player.getUniqueId().equals(gameManager.getCurrentActivePlayerId())) return null;
        PlayerData pd = gameManager.getPlayerData(player);
        if (pd == null || !pd.isActive() || pd.isImpostor()) return null;
        
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
            addProgress(killer, "own_kill_10_zombie", 1);
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
                String entityName = TargetListManager.normalize(type.name());
                for (Objective obj : objs) {
                    if (obj.getId().startsWith("custom_")) {
                        CustomObjectiveData data = gameManager.getObjectiveManager().getCustomDataByObjective(obj);
                        if (data != null && data.getAction().equals("kill")) {
                            String dataTarget = TargetListManager.normalize(data.getTarget());
                            if (entityName.equals(dataTarget)) {
                                addProgress(killer, obj.getId(), 1);
                            } else {
                                // defensive: try comparing without underscores (e.g., arrow vs tipped_arrow)
                                if (entityName.endsWith("_" + dataTarget) || dataTarget.endsWith("_" + entityName)) {
                                    addProgress(killer, obj.getId(), 1);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player deadPlayer = event.getEntity();
        
        // Handle warden death objective
        if (deadPlayer.getLastDamageCause() instanceof org.bukkit.event.entity.EntityDamageByEntityEvent damageEvent) {
            if (damageEvent.getDamager() instanceof Warden || damageEvent.getDamager().getType() == EntityType.WARDEN) {
                setCompleted(deadPlayer, "one_warden_death");
                setCompleted(deadPlayer, "own_warden_death");
            }
        }

        // If the dead player was the active player, spectators need to be retargeted
        if (gameManager.isPlaying() && deadPlayer.getUniqueId().equals(gameManager.getCurrentActivePlayerId())) {
            // Force re-target all spectators to the dead player's location temporarily
            // The turn manager will handle switching to the next player
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player newActive = gameManager.getCurrentActivePlayer();
                if (newActive != null && !newActive.getUniqueId().equals(deadPlayer.getUniqueId())) {
                    // Retarget all spectators to the new active player
                    for (PlayerData pd : gameManager.getActivePlayers()) {
                        if (pd.getPlayerId().equals(newActive.getUniqueId())) continue;
                        Player spectator = Bukkit.getPlayer(pd.getPlayerId());
                        if (spectator != null && spectator.getGameMode() == GameMode.SPECTATOR) {
                            spectator.teleport(newActive.getLocation());
                            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                if (spectator.isOnline() && newActive.isOnline()) {
                                    spectator.setSpectatorTarget(newActive);
                                }
                            }, 5L);
                        }
                    }
                }
            }, 10L);
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
        
        // Custom Objective (Pickup) — only count genuine world pickups (not player/block drops)
        if (!ignoredPickupItems.contains(event.getItem().getUniqueId())) {
            addCustomPickupProgress(p, type, event.getItem().getItemStack().getAmount());
        } else {
            ignoredPickupItems.remove(event.getItem().getUniqueId());
        }

        // If win condition was triggered during this handler, cancel the pickup
        // so the item is NOT added to inventory after endGame already cleared it.
        if (!gameManager.isPlaying()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        ignoredPickupItems.add(event.getItemDrop().getUniqueId());
    }


    @EventHandler
    public void onCraftItem(org.bukkit.event.inventory.CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) return;
        ItemStack result = event.getCurrentItem();
        if (result == null || result.getType() == Material.AIR) return;

        int amount = result.getAmount();
        if (event.isShiftClick()) {
            // Approximate max craftable amount; counting formula multiplies result stack
            ItemStack[] matrix = event.getInventory().getMatrix();
            int maxCrafts = Integer.MAX_VALUE;
            for (ItemStack ingredient : matrix) {
                if (ingredient == null || ingredient.getType() == Material.AIR) continue;
                maxCrafts = Math.min(maxCrafts, ingredient.getAmount());
            }
            if (maxCrafts != Integer.MAX_VALUE && maxCrafts > 0) {
                amount = result.getAmount() * maxCrafts;
            }
        }
        addCustomPickupProgress(p, result.getType(), amount);
    }

    @EventHandler
    public void onFurnaceExtract(org.bukkit.event.inventory.FurnaceExtractEvent event) {
        addCustomPickupProgress(event.getPlayer(), event.getItemType(), event.getItemAmount());
    }

    private void addCustomPickupProgress(Player player, Material type, int amount) {
        if (amount <= 0) return;
        
        // Track template objectives
        if (type == Material.IRON_INGOT) addProgress(player, "own_32_iron", amount);
        if (type == Material.DIAMOND_PICKAXE) setCompleted(player, "own_craft_diamond_pickaxe");

        List<Objective> objs = gameManager.getObjectiveManager().getPlayerObjectives(player.getUniqueId());
        if (objs == null) return;
        String matName = TargetListManager.normalize(type.name());
        for (Objective obj : objs) {
            if (!obj.getId().startsWith("custom_")) continue;
            CustomObjectiveData data = gameManager.getObjectiveManager().getCustomDataByObjective(obj);
            if (data != null && data.getAction().equals("pickup")) {
                String dataTarget = TargetListManager.normalize(data.getTarget());
                if (matName.equals(dataTarget) || matName.endsWith("_" + dataTarget) || dataTarget.endsWith("_" + matName)) {
                    addProgress(player, obj.getId(), amount);
                }
            }
        }
    }

    private boolean isPlayerStorageInventory(org.bukkit.inventory.Inventory inventory) {
        if (inventory == null) return true;
        InventoryType type = inventory.getType();
        return type == InventoryType.PLAYER
                || type == InventoryType.CRAFTING
                || type == InventoryType.CREATIVE;
    }

    /**
     * Count items taken from containers (chest, hopper, etc.) while preventing
     * re-deposit / withdraw loops via a per-material deposit debt.
     */
    private void handleContainerObtain(Player player, InventoryClickEvent event) {
        if (event.getClickedInventory() == null) return;

        // Track deposits: shift-click from player inventory into open container
        if (isPlayerStorageInventory(event.getClickedInventory())
                && event.isShiftClick()
                && event.getCurrentItem() != null
                && event.getCurrentItem().getType() != Material.AIR
                && event.getView().getTopInventory() != null
                && !isPlayerStorageInventory(event.getView().getTopInventory())) {
            addDepositDebt(player.getUniqueId(), event.getCurrentItem().getType(), event.getCurrentItem().getAmount());
            return;
        }

        // Track deposits: place cursor item into container slot
        if (!isPlayerStorageInventory(event.getClickedInventory())
                && event.getCursor() != null
                && event.getCursor().getType() != Material.AIR
                && !event.isShiftClick()
                && (event.getCurrentItem() == null
                    || event.getCurrentItem().getType() == Material.AIR
                    || event.getCurrentItem().isSimilar(event.getCursor()))) {
            InventoryType.SlotType slotType = event.getSlotType();
            if (slotType != InventoryType.SlotType.RESULT) {
                int placeAmount = event.isRightClick() ? 1 : event.getCursor().getAmount();
                addDepositDebt(player.getUniqueId(), event.getCursor().getType(), placeAmount);
                return;
            }
        }

        // Taking items out of a container
        if (isPlayerStorageInventory(event.getClickedInventory())) return;
        if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) return;

        InventoryType topType = event.getView().getTopInventory().getType();
        // Crafting / smelting have dedicated events — avoid double counting
        if (topType == InventoryType.WORKBENCH || topType == InventoryType.CRAFTING
                || topType == InventoryType.FURNACE || topType == InventoryType.BLAST_FURNACE
                || topType == InventoryType.SMOKER) {
            return;
        }

        InventoryType.SlotType slotType = event.getSlotType();
        if (slotType != InventoryType.SlotType.CONTAINER
                && slotType != InventoryType.SlotType.FUEL
                && slotType != InventoryType.SlotType.RESULT) {
            return;
        }

        Material type = event.getCurrentItem().getType();
        int obtainedAmount;
        if (event.isShiftClick()) {
            obtainedAmount = event.getCurrentItem().getAmount();
        } else if (event.getCursor() != null && event.getCursor().getType() != Material.AIR
                && !event.getCursor().isSimilar(event.getCurrentItem())) {
            return; // can't pick up with incompatible cursor
        } else if (event.isRightClick()) {
            obtainedAmount = (event.getCurrentItem().getAmount() + 1) / 2;
        } else {
            obtainedAmount = event.getCurrentItem().getAmount();
        }

        int countable = consumeDepositDebt(player.getUniqueId(), type, obtainedAmount);
        if (countable > 0) {
            addCustomPickupProgress(player, type, countable);
        }
    }

    private void addDepositDebt(UUID playerId, Material type, int amount) {
        if (amount <= 0) return;
        depositedItems.computeIfAbsent(playerId, k -> new HashMap<>())
                .merge(type, amount, Integer::sum);
    }

    /** @return amount that should count as newly obtained after subtracting deposit debt */
    private int consumeDepositDebt(UUID playerId, Material type, int amount) {
        Map<Material, Integer> debtMap = depositedItems.get(playerId);
        if (debtMap == null) return amount;
        int debt = debtMap.getOrDefault(type, 0);
        if (debt <= 0) return amount;
        int used = Math.min(debt, amount);
        debt -= used;
        if (debt <= 0) debtMap.remove(type);
        else debtMap.put(type, debt);
        return amount - used;
    }
    
    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        if (event.getBlock().getType() == Material.ANCIENT_DEBRIS) {
            setCompleted(p, "one_ancient_debris");
        } else if (event.getBlock().getType() == Material.STONE) {
            addProgress(p, "own_mine_stone", 1);
        }
        
        // Custom Objective (Mining Block only — never pickup)
        List<Objective> objs = gameManager.getObjectiveManager().getPlayerObjectives(p.getUniqueId());
        if (objs != null) {
            String blockName = TargetListManager.normalize(event.getBlock().getType().name());
            for (Objective obj : objs) {
                if (obj.getId().startsWith("custom_")) {
                    CustomObjectiveData data = gameManager.getObjectiveManager().getCustomDataByObjective(obj);
                    if (data != null && data.getAction().equals("mining")) {
                        String dataTarget = TargetListManager.normalize(data.getTarget());
                        if (blockName.equals(dataTarget) || blockName.endsWith("_" + dataTarget) || dataTarget.endsWith("_" + blockName)) {
                            addProgress(p, obj.getId(), 1);
                        }
                    }
                }
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
        if (event.getOwner() instanceof Player p && (event.getEntity() instanceof Cat || event.getEntity() instanceof Wolf)) {
            setCompleted(p, "own_tame_cat_wolf");
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
        Objective biomeObj = getObjective(p, "one_10_biomes");
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
            handleContainerObtain(p, event);

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
