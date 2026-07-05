package com.czipo.sharedControlImpostor;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a game objective that investigators must complete.
 */
public class Objective {

    private final ObjectiveType type;
    private final String description;      // What investigators see
    private final String clue;             // What impostor sees (vague hint)
    private final int targetCount;         // Target amount (for count-based objectives)
    private final Material targetMaterial; // Target block/item material
    private int progress;                  // Current progress

    public Objective(ObjectiveType type, String description, String clue, int targetCount, Material targetMaterial) {
        this.type = type;
        this.description = description;
        this.clue = clue;
        this.targetCount = targetCount;
        this.targetMaterial = targetMaterial;
        this.progress = 0;
    }

    public ObjectiveType getType() { return type; }
    public String getDescription() { return description; }
    public String getClue() { return clue; }
    public int getTargetCount() { return targetCount; }
    public Material getTargetMaterial() { return targetMaterial; }
    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = progress; }
    public void addProgress(int amount) { this.progress += amount; }

    public boolean isCompleted() {
        return progress >= targetCount;
    }

    public String getProgressDisplay() {
        return description + " [" + progress + "/" + targetCount + "]";
    }

    /**
     * Types of objectives that can be assigned.
     */
    public enum ObjectiveType {
        CHOP_LOGS,
        MINE_ORE,
        CRAFT_ITEM,
        REACH_LOCATION,
        PLACE_BLOCKS,
        KILL_MOB
    }

    /**
     * Load objective templates from config file.
     */
    public static List<ObjectiveTemplate> loadFromConfig(FileConfiguration config) {
        List<ObjectiveTemplate> templates = new ArrayList<>();

        ConfigurationSection objectivesSection = config.getConfigurationSection("objectives.enabled");
        if (objectivesSection == null) {
            // Fallback to default if config is missing
            templates.add(new ObjectiveTemplate(ObjectiveType.MINE_ORE, "Mine {count} stone",
                    "Stone must be gathered from underground...", Material.STONE, 5));
            return templates;
        }

        for (String key : objectivesSection.getKeys(false)) {
            String objectiveString = objectivesSection.getString(key);
            if (objectiveString != null) {
                try {
                    // Format: type|description|clue|material|count
                    String[] parts = objectiveString.split("\\|");
                    if (parts.length == 5) {
                        ObjectiveType type = ObjectiveType.valueOf(parts[0]);
                        String description = parts[1];
                        String clue = parts[2];
                        Material material = Material.valueOf(parts[3]);
                        int count = Integer.parseInt(parts[4]);

                        templates.add(new ObjectiveTemplate(type, description, clue, material, count));
                    }
                } catch (Exception e) {
                    // Skip invalid objectives
                    System.err.println("Invalid objective format: " + objectiveString);
                }
            }
        }

        // If no valid objectives loaded, use fallback
        if (templates.isEmpty()) {
            templates.add(new ObjectiveTemplate(ObjectiveType.MINE_ORE, "Mine {count} cobblestone",
                    "Stone must be gathered from underground...", Material.COBBLESTONE, 10));
        }

        return templates;
    }

    /**
     * Template for creating objectives with variable counts.
     */
    public static class ObjectiveTemplate {
        public final ObjectiveType type;
        public final String descriptionTemplate;
        public final String clue;
        public final Material targetMaterial;
        public final int defaultCount;

        public ObjectiveTemplate(ObjectiveType type, String descriptionTemplate, String clue, Material targetMaterial, int defaultCount) {
            this.type = type;
            this.descriptionTemplate = descriptionTemplate;
            this.clue = clue;
            this.targetMaterial = targetMaterial;
            this.defaultCount = defaultCount;
        }

        public Objective create() {
            String desc = descriptionTemplate.replace("{count}", String.valueOf(defaultCount));
            return new Objective(type, desc, clue, defaultCount, targetMaterial);
        }
    }
}
