package com.czipo.sharedControlImpostor;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Collection;
import java.util.List;

/**
 * Manages world creation, teleportation, and world-related operations.
 */
public class WorldManager {

    private final SharedControlImpostor plugin;
    private final GameManager gameManager;

    private World meetingWorld;
    private World survivalWorld;
    private String survivalWorldName;
    private String meetingWorldName;
    private Location meetingSpawn;
    private Location survivalSpawn;



    public WorldManager(SharedControlImpostor plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;

        // Load config for custom world names (optional)
        this.meetingWorldName = plugin.getConfig().getString("worlds.meeting", "meeting");

        // Note: We no longer auto-cleanup old survival worlds on startup
        // because we might want to resume them.

        // Find or create the meeting world
        initMeetingWorld();
    }

    /**
     * Initialize the meeting world (lobby world).
     * Sets up worldborder center at -19, -382 with size 50.
     */
    private void initMeetingWorld() {
        // Try to load the custom meeting world
        meetingWorld = Bukkit.getWorld(meetingWorldName);
        if (meetingWorld == null) {
            // Try to load it from disk if it exists
            meetingWorld = new WorldCreator(meetingWorldName)
                    .environment(World.Environment.NORMAL)
                    .createWorld();
        }

        if (meetingWorld != null) {
            // Set worldborder center and size as requested
            WorldBorder border = meetingWorld.getWorldBorder();
            border.setCenter(-19, -382);
            border.setSize(50);

            // Set spawn to worldborder center
            int highestY = meetingWorld.getHighestBlockYAt(-19, -382);
            meetingSpawn = new Location(meetingWorld, -19, highestY + 1, -382);
            meetingWorld.setSpawnLocation(meetingSpawn);

            plugin.getLogger().info("Meeting world loaded: " + meetingWorldName);
            plugin.getLogger().info("Meeting spawn set to: " + meetingSpawn.getBlockX() + ", " + meetingSpawn.getBlockY() + ", " + meetingSpawn.getBlockZ());

            // Set global gamerule for locator bar
            Bukkit.getScheduler().runTask(plugin, () -> {
                meetingWorld.setGameRule(GameRule.SEND_COMMAND_FEEDBACK, false);
                meetingWorld.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "gamerule locator_bar false");
                meetingWorld.setGameRule(GameRule.SEND_COMMAND_FEEDBACK, true);
            });
        } else {
            plugin.getLogger().warning("Could not load meeting world: " + meetingWorldName);
        }
    }

    /**
     * Delete any leftover survival world folders from previous crashed/stopped sessions.
     */
    private void cleanupOldSurvivalWorlds() {
        java.io.File worldContainer = plugin.getServer().getWorldContainer();
        java.io.File[] folders = worldContainer.listFiles();
        if (folders != null) {
            for (java.io.File f : folders) {
                if (f.isDirectory() && f.getName().startsWith("survival_game_")) {
                    plugin.getLogger().info("Found leftover survival world, deleting: " + f.getName());
                    deleteDirectory(f);
                }
            }
        }
    }

    public boolean hasExistingSurvivalWorld() {
        java.io.File worldContainer = plugin.getServer().getWorldContainer();
        java.io.File[] folders = worldContainer.listFiles();
        if (folders != null) {
            for (java.io.File f : folders) {
                if (f.isDirectory() && f.getName().startsWith("survival_game_")) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean loadExistingSurvivalWorld() {
        java.io.File worldContainer = plugin.getServer().getWorldContainer();
        java.io.File[] folders = worldContainer.listFiles();
        java.io.File newest = null;
        if (folders != null) {
            for (java.io.File f : folders) {
                if (f.isDirectory() && f.getName().startsWith("survival_game_")) {
                    if (newest == null || f.lastModified() > newest.lastModified()) {
                        newest = f;
                    }
                }
            }
        }
        
        if (newest != null) {
            survivalWorldName = newest.getName();
            survivalWorld = new WorldCreator(survivalWorldName)
                    .environment(World.Environment.NORMAL)
                    .createWorld();
            if (survivalWorld != null) {
                survivalWorld.setGameRule(GameRule.KEEP_INVENTORY, true);
                survivalSpawn = survivalWorld.getSpawnLocation();
                return true;
            }
        }
        return false;
    }

    /**
     * Create a new survival world for the game.
     * Each /start creates a fresh world.
     */
    public void createSurvivalWorld() {
        // Generate a unique world name
        survivalWorldName = "survival_game_" + System.currentTimeMillis();

        // Delete old survival world if exists
        if (survivalWorld != null) {
            unloadAndDeleteWorld(survivalWorld);
        }
        
        cleanupOldSurvivalWorlds(); // Delete all leftover folders on disk

        // Create new survival world
        survivalWorld = new WorldCreator(survivalWorldName)
                .environment(World.Environment.NORMAL)
                .seed((long) (Math.random() * Long.MAX_VALUE))
                .generateStructures(true)
                .keepSpawnLoaded(net.kyori.adventure.util.TriState.FALSE)
                .createWorld();

        if (survivalWorld != null) {
            survivalSpawn = survivalWorld.getSpawnLocation();
            // Find a safe spawn location
            survivalSpawn = findSafeSpawn(survivalWorld);
            survivalWorld.setSpawnLocation(survivalSpawn);

            // Apply locator_bar false to survival world
            survivalWorld.setDifficulty(org.bukkit.Difficulty.NORMAL);
            Bukkit.getScheduler().runTask(plugin, () -> {
                survivalWorld.setGameRule(GameRule.SEND_COMMAND_FEEDBACK, false);
                survivalWorld.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "execute in minecraft:" + survivalWorldName + " run gamerule locator_bar false");
                survivalWorld.setGameRule(GameRule.SEND_COMMAND_FEEDBACK, true);
            });
        }
    }

    /**
     * Find a safe spawn location in the world.
     */
    private Location findSafeSpawn(World world) {
        Location spawn = world.getSpawnLocation();
        // Try to find the highest block at spawn
        int highestY = world.getHighestBlockYAt(spawn);
        return new Location(world, spawn.getX(), highestY + 1, spawn.getZ());
    }

    /**
     * Unload and delete a world.
     */
    private void unloadAndDeleteWorld(World world) {
        if (world == null) return;

        String worldName = world.getName();
        // Teleport all players out first
        for (Player player : world.getPlayers()) {
            if (meetingSpawn != null) {
                player.teleport(meetingSpawn);
            }
        }

        // Unload the world
        Bukkit.unloadWorld(world, false);

        // Delete world folder asynchronously to avoid blocking main thread
        plugin.getLogger().info("Deleting old survival world: " + worldName);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            java.io.File worldFolder = new java.io.File(plugin.getServer().getWorldContainer(), worldName);
            deleteDirectory(worldFolder);
        });
    }

    /**
     * Recursively delete a directory and all its contents.
     */
    private void deleteDirectory(java.io.File directory) {
        if (directory.exists()) {
            java.io.File[] files = directory.listFiles();
            if (files != null) {
                for (java.io.File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }

    /**
     * Get the meeting world.
     */
    public World getMeetingWorld() { return meetingWorld; }

    /**
     * Get the current survival world.
     */
    public World getSurvivalWorld() { return survivalWorld; }

    /**
     * Get meeting spawn location.
     */
    public Location getMeetingSpawn() { return meetingSpawn; }

    /**
     * Get survival spawn location.
     */
    public Location getSurvivalSpawn() { return survivalSpawn; }

    /**
     * Get a randomized meeting spawn within 12 blocks.
     */
    public Location getRandomizedMeetingSpawn() {
        if (meetingSpawn == null) return null;
        double offsetX = (Math.random() * 24) - 12;
        double offsetZ = (Math.random() * 24) - 12;
        Location loc = meetingSpawn.clone().add(offsetX, 0, offsetZ);
        loc.setY(loc.getWorld().getHighestBlockYAt(loc) + 1);
        return loc;
    }



    /**
     * Teleport all registered players to the survival world (game start).
     * Hides nametags and disables locator bar.
     */
    public void teleportToSurvivalWorld(List<PlayerData> players) {

        for (PlayerData pd : players) {
            Player player = Bukkit.getPlayer(pd.getPlayerId());
            if (player != null && survivalSpawn != null) {
                player.teleport(survivalSpawn);
                player.setGameMode(GameMode.SURVIVAL);
                player.getInventory().clear();
                player.setHealth(20.0);
                player.setFoodLevel(20);
                player.setSaturation(5.0f);
            }
        }
    }

    /**
     * Teleport all registered players to the meeting world (lobby/end game).
     * All players set to ADVENTURE mode. Nametags restored.
     */
    public void teleportToMeetingWorld(List<PlayerData> players) {
        for (PlayerData pd : players) {
            Player player = Bukkit.getPlayer(pd.getPlayerId());
            if (player != null && meetingSpawn != null) {
                player.setGameMode(GameMode.ADVENTURE);
                player.teleport(meetingSpawn);
                player.getInventory().clear();
            }
        }
    }

    /**
     * Teleport all players in the playerDataMap to meeting world (including eliminated).
     * Used by endGame/handleGameEnd to ensure ALL players get reset.
     */
    public void teleportAllToMeetingWorld(Collection<PlayerData> allPlayers) {
        for (PlayerData pd : allPlayers) {
            Player player = Bukkit.getPlayer(pd.getPlayerId());
            if (player != null && meetingSpawn != null) {
                player.setGameMode(GameMode.ADVENTURE);
                player.teleport(getRandomizedMeetingSpawn());
                player.getInventory().clear();
            }
        }
    }

    /**
     * Teleport active players to meeting world for a meeting.
     * Non-eliminated players get ADVENTURE mode.
     * Already-eliminated players get SPECTATOR mode (free movement).
     */
    public void teleportToMeetingWorldForMeeting(List<PlayerData> players) {
        for (PlayerData pd : players) {
            Player player = Bukkit.getPlayer(pd.getPlayerId());
            if (player != null && meetingSpawn != null) {
                player.teleport(getRandomizedMeetingSpawn());
                if (pd.isEliminated()) {
                    // Already eliminated: free spectator in meeting world
                    player.setGameMode(GameMode.SPECTATOR);
                    player.setSpectatorTarget(null);
                } else {
                    player.setGameMode(GameMode.ADVENTURE);
                }
                player.getInventory().clear();
            }
        }
    }



    /**
     * Clean up worlds on plugin disable.
     */
    public void cleanup() {
        if (survivalWorld != null) {
            unloadAndDeleteWorld(survivalWorld);
            survivalWorld = null;
        }
    }
}
