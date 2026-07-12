package com.czipo.sharedControlImpostor;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main plugin class for Shared Control Impostor.
 * A Minecraft mini-game where players share control of a single character,
 * with one or more impostors trying to sabotage investigators without being caught.
 */
public final class SharedControlImpostor extends JavaPlugin {

    private GameManager gameManager;
    private TurnManager turnManager;
    private VoteManager voteManager;
    private ScoreboardManager scoreboardManager;
    private WorldManager worldManager;
    private SettingsManager settingsManager;
    private ObjectiveManager objectiveManager;
    private CommandHandler commandHandler;

    @Override
    public void onEnable() {
        // Save default config
        saveDefaultConfig();

        // Initialize core managers
        gameManager = new GameManager(this);
        worldManager = new WorldManager(this, gameManager);
        turnManager = new TurnManager(this, gameManager);
        voteManager = new VoteManager(this, gameManager);
        scoreboardManager = new ScoreboardManager(this, gameManager);
        commandHandler = new CommandHandler(this, gameManager);

        // Link managers to GameManager
        gameManager.setTurnManager(turnManager);
        gameManager.setVoteManager(voteManager);
        gameManager.setScoreboardManager(scoreboardManager);
        gameManager.setWorldManager(worldManager);

        // Register event listeners
        this.settingsManager = new SettingsManager(this, gameManager);
        this.objectiveManager = new ObjectiveManager(gameManager);
        gameManager.setSettingsManager(settingsManager);
        gameManager.setObjectiveManager(objectiveManager);

        getServer().getPluginManager().registerEvents(new GameListener(this, gameManager), this);
        getServer().getPluginManager().registerEvents(new ObjectiveListener(this, gameManager), this);
        getServer().getPluginManager().registerEvents(settingsManager, this);
        getServer().getPluginManager().registerEvents(new VoteListener(this, gameManager), this);

        // Register commands
        registerCommand("regis", commandHandler);
        registerCommand("regisall", commandHandler);
        registerCommand("unregis", commandHandler);
        registerCommand("start", commandHandler);
        registerCommand("meeting", commandHandler);
        registerCommand("endgame", commandHandler);
        registerCommand("listplayer", commandHandler);
        registerCommand("commandinfo", commandHandler);
        registerCommand("skip", commandHandler);

        getLogger().info("Shared Control Impostor plugin enabled!");
        getLogger().info("Use /commandinfo for a list of commands.");

        // Remove plugin-namespaced command aliases (e.g. /sharedcontrolimpostor:start)
        // so players can only use /(command), not /sharedcontrolimpostor:(command)
        Bukkit.getScheduler().runTask(this, () -> {
            org.bukkit.command.CommandMap commandMap = getServer().getCommandMap();
            String pluginName = getName().toLowerCase();
            java.util.List<String> commandNames = java.util.List.of(
                "regis", "regisall", "unregis", "start", "meeting",
                "settimer", "setimpostor", "endgame", "listplayer", "commandinfo", "skip"
            );
            for (String name : commandNames) {
                org.bukkit.command.Command c = commandMap.getCommand(pluginName + ":" + name);
                if (c != null) {
                    c.unregister(commandMap);
                }
            }
        });
    }

    @Override
    public void onDisable() {
        // Clean up if game is running
        if (gameManager != null && !gameManager.isLobby()) {
            gameManager.endGame();
        }

        // Clean up worlds
        if (worldManager != null) {
            worldManager.cleanup();
        }

        // Clean up managers
        if (turnManager != null) turnManager.stopTurn();
        if (scoreboardManager != null) scoreboardManager.stopUpdates();
        if (voteManager != null) voteManager.cleanup();

        getLogger().info("Shared Control Impostor plugin disabled!");
    }

    /**
     * Register a command with the plugin.
     */
    private void registerCommand(String name, CommandHandler handler) {
        org.bukkit.command.PluginCommand cmd = getCommand(name);
        if (cmd != null) {
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        } else {
            getLogger().warning("Command '" + name + "' not found in plugin.yml!");
        }
    }

    // ===== Public accessors for managers =====

    public GameManager getGameManager() { return gameManager; }
    public TurnManager getTurnManager() { return turnManager; }
    public VoteManager getVoteManager() { return voteManager; }
    public ScoreboardManager getScoreboardManager() { return scoreboardManager; }
    public WorldManager getWorldManager() { return worldManager; }

}
