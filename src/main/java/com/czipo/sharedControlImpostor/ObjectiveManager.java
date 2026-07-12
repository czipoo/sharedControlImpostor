package com.czipo.sharedControlImpostor;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.util.*;

public class ObjectiveManager {

    private final GameManager gameManager;
    private final Map<UUID, List<Objective>> playerObjectives = new HashMap<>();
    private final List<Objective> sharedObjectives = new ArrayList<>();
    
    // The objective templates
    private final List<Objective> oneObjectivePool = new ArrayList<>();
    private final List<Objective> ownObjectivePool = new ArrayList<>();

    // Template / Custom override state (set by SettingsManager dialog)
    // null means "use random" (default).
    private Integer fixedOneTemplateIndex = null;  // for one-objective + template
    private final List<Integer> fixedOwnTemplateIndices = new ArrayList<>();  // for own-objective + template
    private CustomObjectiveData customOneObjective = null;
    private final List<CustomObjectiveData> customOwnObjectives = new ArrayList<>();
    private final Map<String, CustomObjectiveData> objectiveDataMap = new HashMap<>();

    public ObjectiveManager(GameManager gameManager) {
        this.gameManager = gameManager;
        initializePools();
    }
    
    private void initializePools() {
        oneObjectivePool.add(new Objective("one_nether", "Masuk ke dimensi nether", 1, true));
        oneObjectivePool.add(new Objective("one_piglin_pearl", "Dapatkan ender pearl dari piglin", 1, true));
        oneObjectivePool.add(new Objective("one_ghast_tear", "Dapatkan 1 Ghast Tear", 1, true));
        oneObjectivePool.add(new Objective("one_ancient_debris", "Dapatkan 1 Ancient Debris", 1, true));
        oneObjectivePool.add(new Objective("one_wither_skull", "Dapatkan 1 Wither Skeleton Skull", 1, true));
        oneObjectivePool.add(new Objective("one_breeze", "Bunuh 5 Breeze", 5, true));
        oneObjectivePool.add(new Objective("one_elder_guardian", "Bunuh Elder Guardian", 1, true));
        oneObjectivePool.add(new Objective("one_warden_death", "Bunuh diri dengan Warden", 1, true));
        oneObjectivePool.add(new Objective("one_ender_dragon", "Kalahkan Ender Dragon", 1, true));
        oneObjectivePool.add(new Objective("one_elytra", "Dapatkan Elytra", 1, true));
        
        ownObjectivePool.add(new Objective("own_trade_villager", "Trade dengan Villager", 1, false));
        ownObjectivePool.add(new Objective("own_enchant", "Gunakan Enchanting Table", 1, false));
        ownObjectivePool.add(new Objective("own_tame_cat", "Tame Cat", 1, false));
        ownObjectivePool.add(new Objective("own_axolotl_bucket", "Dapatkan Bucket of Axolotl", 1, false));
        ownObjectivePool.add(new Objective("own_hit_golem", "Pukul Iron Golem", 1, false));
        ownObjectivePool.add(new Objective("own_ride_horse", "Tunggangi Horse sejauh 100 block", 1, false));
        ownObjectivePool.add(new Objective("own_pufferfish", "Makan Pufferfish", 1, false));
        ownObjectivePool.add(new Objective("own_bedrock_height", "Capai ketinggian bedrock", 1, false));
        ownObjectivePool.add(new Objective("own_mlg_water", "MLG Water Bucket dari ketinggian 20 block", 1, false));
        ownObjectivePool.add(new Objective("own_gapple", "Makan 1 Golden Apple", 1, false));
        ownObjectivePool.add(new Objective("own_chicken_jockey", "Bunuh Chicken Jockey", 1, false));
        ownObjectivePool.add(new Objective("own_music_disc", "Dapatkan Music Disc dari Creeper", 1, false));
        ownObjectivePool.add(new Objective("own_mine_stone", "Mining 64 stone", 64, false));
        ownObjectivePool.add(new Objective("own_eat_cake", "Makan Cake", 1, false));
        ownObjectivePool.add(new Objective("own_5_wool", "Kumpulkan 5 warna Wool berbeda", 5, false));
        ownObjectivePool.add(new Objective("own_sprint_500", "Berlari sejauh 500 block", 500, false));
        ownObjectivePool.add(new Objective("own_iron_armor", "Pakai full set Iron Armor", 1, false));
        ownObjectivePool.add(new Objective("own_honey_bottle", "Dapatkan honey bottle", 1, false));
        ownObjectivePool.add(new Objective("own_5_biomes", "Pergi ke 5 biome berbeda", 5, false));
        ownObjectivePool.add(new Objective("own_ignite_tnt", "Nyalakan 5 TNT", 5, false));
        ownObjectivePool.add(new Objective("own_nether", "Masuk ke Nether", 1, false));
    }

    public void assignObjectives() {
        playerObjectives.clear();
        sharedObjectives.clear();
        
        List<PlayerData> investigators = new ArrayList<>();
        for (PlayerData pd : gameManager.getActivePlayers()) {
            if (!pd.isImpostor()) investigators.add(pd);
        }

        SettingsManager sm = gameManager.getSettingsManager();
        boolean oneMode = sm.isOneObjectiveMode();
        String objType = sm.getObjectiveType(); // "random", "template", "custom"

        if (oneMode) {
            // ---- ONE OBJECTIVE mode ----
            Objective selected;
            if (objType.equals("custom") && customOneObjective != null) {
                selected = new Objective("custom_one", customOneObjective.getName(), customOneObjective.getAmount(), true);
                objectiveDataMap.put("custom_one", customOneObjective);
            } else if (objType.equals("template") && fixedOneTemplateIndex != null
                    && fixedOneTemplateIndex >= 0 && fixedOneTemplateIndex < oneObjectivePool.size()) {
                selected = oneObjectivePool.get(fixedOneTemplateIndex).cloneObjective();
            } else {
                // random (default)
                selected = oneObjectivePool.get(new Random().nextInt(oneObjectivePool.size())).cloneObjective();
            }
            for (PlayerData pd : investigators) {
                List<Objective> list = new ArrayList<>();
                list.add(selected);
                playerObjectives.put(pd.getPlayerId(), list);
            }
        } else {
            // ---- OWN OBJECTIVE mode ----
            if (objType.equals("custom") && !customOwnObjectives.isEmpty()) {
                // Filter out nulls
                List<CustomObjectiveData> validCustoms = new ArrayList<>();
                for (CustomObjectiveData c : customOwnObjectives) {
                    if (c != null) validCustoms.add(c);
                }
                if (!validCustoms.isEmpty()) {
                    // Assign from valid custom objectives one by one
                    for (int i = 0; i < investigators.size(); i++) {
                        CustomObjectiveData data = validCustoms.get(i % validCustoms.size());
                        String id = "custom_own_" + investigators.get(i).getPlayerId();
                        objectiveDataMap.put(id, data);
                        List<Objective> list = new ArrayList<>();
                        list.add(new Objective(id, data.getName(), data.getAmount(), false));
                        playerObjectives.put(investigators.get(i).getPlayerId(), list);
                    }
                }
            } else if (objType.equals("template") && !fixedOwnTemplateIndices.isEmpty()) {
                // Diacak ke tiap investigator dari pilihan yang diset di dialog
                List<Integer> shuffledIndices = new ArrayList<>(fixedOwnTemplateIndices);
                Collections.shuffle(shuffledIndices);
                
                for (int i = 0; i < investigators.size(); i++) {
                    List<Objective> list = new ArrayList<>();
                    int poolIndex = (i < shuffledIndices.size()) ? shuffledIndices.get(i) : 0;
                    if (poolIndex >= 0 && poolIndex < ownObjectivePool.size()) {
                        list.add(ownObjectivePool.get(poolIndex).cloneObjective());
                    } else {
                        list.add(ownObjectivePool.get(0).cloneObjective());
                    }
                    playerObjectives.put(investigators.get(i).getPlayerId(), list);
                }
            } else {
                // random (default)
                List<Objective> shuffledPool = new ArrayList<>();
                for (Objective o : ownObjectivePool) shuffledPool.add(o.cloneObjective());
                Collections.shuffle(shuffledPool);
                for (int i = 0; i < investigators.size(); i++) {
                    if (i >= shuffledPool.size()) break;
                    List<Objective> list = new ArrayList<>();
                    list.add(shuffledPool.get(i));
                    playerObjectives.put(investigators.get(i).getPlayerId(), list);
                }
            }
        }
    }
    
    public List<Objective> getPlayerObjectives(UUID playerId) {
        List<Objective> list = new ArrayList<>();
        if (playerObjectives.containsKey(playerId)) {
            list.addAll(playerObjectives.get(playerId));
        }
        if (!gameManager.getSettingsManager().isOneObjectiveMode()) {
            list.addAll(sharedObjectives);
        }
        return list;
    }

    public List<Objective> getSharedObjectives() { return sharedObjectives; }
    public List<Objective> getOneObjectivePool() { return oneObjectivePool; }
    public List<Objective> getOwnObjectivePool() { return ownObjectivePool; }

    public void setCustomOneObjective(CustomObjectiveData data) { this.customOneObjective = data; }
    public void addCustomOwnObjective(CustomObjectiveData data) { this.customOwnObjectives.add(data); }
    public void setCustomOwnObjective(int index, CustomObjectiveData data) {
        while (this.customOwnObjectives.size() <= index) {
            this.customOwnObjectives.add(null);
        }
        this.customOwnObjectives.set(index, data);
    }
    public void clearCustomOwnObjectives() { this.customOwnObjectives.clear(); }
    public List<CustomObjectiveData> getCustomOwnObjectives() { return customOwnObjectives; }
    public CustomObjectiveData getCustomDataByObjective(Objective obj) { return objectiveDataMap.get(obj.getId()); }

    /**
     * Called by SettingsManager when "Set Template" is saved.
     * Stores the selected index so assignObjectives() can use it.
     */
    public void setFixedOneTemplateIndex(int index) {
        this.fixedOneTemplateIndex = index;
    }

    /**
     * Called by SettingsManager for Own Objective + Template mode
     */
    public void setFixedOwnTemplates(List<Integer> indices) {
        fixedOwnTemplateIndices.clear();
        fixedOwnTemplateIndices.addAll(indices);
    }


    public void handlePlayerElimination(UUID eliminatedPlayerId) {
        if (gameManager.getSettingsManager().isOneObjectiveMode()) return;
        
        // Pass uncompleted objectives to shared list
        if (playerObjectives.containsKey(eliminatedPlayerId)) {
            for (Objective obj : playerObjectives.get(eliminatedPlayerId)) {
                if (!obj.isCompleted()) {
                    sharedObjectives.add(obj);
                } else {
                    // Still add it so it displays as completed for others
                    sharedObjectives.add(obj); 
                }
            }
            playerObjectives.remove(eliminatedPlayerId);
            gameManager.getScoreboardManager().forceUpdate();
        }
    }

    public boolean areAllObjectivesCompleted() {
        if (gameManager.getSettingsManager().isOneObjectiveMode()) {
            // Just check the first investigator's objective (it's the same instance)
            for (List<Objective> objs : playerObjectives.values()) {
                for (Objective obj : objs) {
                    if (!obj.isCompleted()) return false;
                }
            }
            return true;
        } else {
            // Own objective mode: all personal and shared must be complete
            for (List<Objective> objs : playerObjectives.values()) {
                for (Objective obj : objs) {
                    if (!obj.isCompleted()) return false;
                }
            }
            for (Objective obj : sharedObjectives) {
                if (!obj.isCompleted()) return false;
            }
            return true;
        }
    }
}
