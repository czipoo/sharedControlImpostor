package com.czipo.sharedControlImpostor;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Loads and validates custom-objective targets from list_target.yml.
 */
public class TargetListManager {

    private final SharedControlImpostor plugin;
    private final Set<String> blocks = new HashSet<>();
    private final Set<String> items = new HashSet<>();
    private final Set<String> mobs = new HashSet<>();

    public TargetListManager(SharedControlImpostor plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        blocks.clear();
        items.clear();
        mobs.clear();

        plugin.saveResource("list_target.yml", true);
        File file = new File(plugin.getDataFolder(), "list_target.yml");
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);

        loadList(config.getStringList("blocks"), blocks);
        loadList(config.getStringList("items"), items);
        loadList(config.getStringList("mobs"), mobs);
        // Each list is kept strictly separate - blocks only in blocks, items only in items, mobs only in mobs
    }

    private void loadList(List<String> source, Set<String> target) {
        if (source == null) return;
        for (String entry : source) {
            String normalized = normalize(entry);
            if (!normalized.isEmpty()) {
                target.add(normalized);
            }
        }
    }

    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("minecraft:")) {
            s = s.substring("minecraft:".length());
        }
        return s.replace(' ', '_');
    }

    public boolean isValidBlock(String target) {
        return blocks.contains(normalize(target));
    }

    public boolean isValidItem(String target) {
        return items.contains(normalize(target));
    }

    public boolean isValidMob(String target) {
        return mobs.contains(normalize(target));
    }

    /**
     * @return error chat message, or null if valid
     */
    public String validateTarget(String action, String target) {
        String normalized = normalize(target);
        if (normalized.isEmpty()) return null;

        return switch (action) {
            case "mining" -> isValidBlock(normalized)
                    ? null
                    : "Target tidak ada di daftar Block atau salah penulisan";
            case "pickup" -> isValidItem(normalized)
                    ? null
                    : "Target tidak ada di daftar Item atau salah penulisan";
            case "kill" -> isValidMob(normalized)
                    ? null
                    : "Target tidak ada di daftar Mob atau salah penulisan";
            default -> "Aksi tidak valid!";
        };
    }
}
