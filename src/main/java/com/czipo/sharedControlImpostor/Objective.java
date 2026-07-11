package com.czipo.sharedControlImpostor;

import java.util.UUID;
import java.util.Set;
import java.util.HashSet;

public class Objective {
    private final String id;
    private final String description;
    private final int targetCount;
    private int progress;
    private boolean isCompleted;
    private final boolean isOneObjective;
    
    // For specific tracking (e.g., biomes visited, wool colors)
    private final Set<String> memory = new HashSet<>();

    public Objective(String id, String description, int targetCount, boolean isOneObjective) {
        this.id = id;
        this.description = description;
        this.targetCount = targetCount;
        this.progress = 0;
        this.isCompleted = false;
        this.isOneObjective = isOneObjective;
    }

    public String getId() { return id; }
    public String getDescription() { return description; }
    public int getTargetCount() { return targetCount; }
    public int getProgress() { return progress; }
    public boolean isCompleted() { return isCompleted; }
    public boolean isOneObjective() { return isOneObjective; }
    public Set<String> getMemory() { return memory; }

    public void setProgress(int progress) {
        this.progress = progress;
        checkCompletion();
    }

    public void addProgress(int amount) {
        this.progress += amount;
        checkCompletion();
    }
    
    public void setCompleted() {
        this.progress = targetCount;
        this.isCompleted = true;
    }

    private void checkCompletion() {
        if (this.progress >= targetCount) {
            this.progress = targetCount;
            this.isCompleted = true;
        }
    }

    public String getProgressDisplay() {
        if (isCompleted) {
            return "§a" + description + " §a(Selesai) ✔";
        }
        if (targetCount > 1) {
            return description + " [" + progress + "/" + targetCount + "]";
        }
        return description;
    }
    
    public Objective cloneObjective() {
        Objective obj = new Objective(this.id, this.description, this.targetCount, this.isOneObjective);
        obj.progress = this.progress;
        obj.isCompleted = this.isCompleted;
        obj.memory.addAll(this.memory);
        return obj;
    }
}
