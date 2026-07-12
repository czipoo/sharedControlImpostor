package com.czipo.sharedControlImpostor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles all plugin commands:
 * /regis - Register player to the game
 * /regisall - Register all online players
 * /unregis - Unregister player from the game
 * /start - Start the game
 * /meeting - Call a meeting during gameplay
 * /skip - Skip the active turn
 * /endgame - End the current game
 * /commandinfo - Show command information
 */
public class CommandHandler implements CommandExecutor, TabCompleter {

    private final SharedControlImpostor plugin;
    private final GameManager gameManager;

    public CommandHandler(SharedControlImpostor plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmdName = command.getName().toLowerCase();

        switch (cmdName) {
            case "regis":
                return handleRegis(sender, args);
            case "regisall":
                return handleRegisAll(sender);
            case "unregis":
                return handleUnregis(sender, args);
            case "start":
                return handleStart(sender);
            case "meeting":
                return handleMeeting(sender);
            case "endgame":
                return handleEndGame(sender);
            case "listplayer":
                return handleListPlayer(sender);
            case "commandinfo":
                return handleCommandInfo(sender);
            case "skip":
                return handleSkip(sender);

            default:
                sender.sendMessage(Component.text("Command tidak tersedia.").color(NamedTextColor.RED));
                return false;
        }
    }

    // ===== /regis =====
    private boolean handleRegis(CommandSender sender, String[] args) {
        if (!gameManager.isLobby()) {
            sender.sendMessage(Component.text("Tidak bisa registrasi saat permainan berlangsung!").color(NamedTextColor.RED));
            return true;
        }

        Player target;
        if (args.length > 0) {
            // Register another player (OP only)
            if (!sender.isOp()) {
                sender.sendMessage(Component.text("Hanya OP yang bisa mendaftarkan/menghapus pemain lain!").color(NamedTextColor.RED));
                return true;
            }
            target = plugin.getServer().getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text("Player tidak ditemukan: " + args[0]).color(NamedTextColor.RED));
                return true;
            }
        } else {
            // Register self
            if (!(sender instanceof Player)) {
                sender.sendMessage(Component.text("Only players can use this command!").color(NamedTextColor.RED));
                return true;
            }
            target = (Player) sender;
        }

        if (gameManager.registerPlayer(target)) {
            sender.sendMessage(Component.text(target.getName() + " berhasil terdaftar!").color(NamedTextColor.GREEN));
            if (!sender.equals(target)) {
                target.sendMessage(Component.text("Kamu berhasil didaftarkan ke permainan!").color(NamedTextColor.GREEN));
            }
        } else {
            sender.sendMessage(Component.text(target.getName() + " sudah terdaftar.").color(NamedTextColor.YELLOW));
        }
        return true;
    }

    // ===== /regisall =====
    private boolean handleRegisAll(CommandSender sender) {
        if (!gameManager.isLobby()) {
            sender.sendMessage(Component.text("Tidak bisa registrasi saat permainan berlangsung!").color(NamedTextColor.RED));
            return true;
        }

        int count = 0;
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (gameManager.getPlayerData(player) != null) {
                sender.sendMessage(Component.text(player.getName() + " sudah terdaftar.").color(NamedTextColor.YELLOW));
            } else {
                gameManager.registerPlayer(player);
                count++;
            }
        }
        
        sender.sendMessage(Component.text(count + " player berhasil terdaftar! Total: ")
                .color(NamedTextColor.GREEN)
                .append(Component.text(gameManager.getRegisteredPlayerCount()).color(NamedTextColor.WHITE)));
        return true;
    }

    // ===== /unregis =====
    private boolean handleUnregis(CommandSender sender, String[] args) {
        if (!gameManager.isLobby()) {
            sender.sendMessage(Component.text("Tidak bisa unregis saat permainan berlangsung!").color(NamedTextColor.RED));
            return true;
        }

        Player target;
        if (args.length > 0) {
            if (!sender.isOp()) {
                sender.sendMessage(Component.text("Hanya OP yang bisa mendaftarkan/menghapus pemain lain!").color(NamedTextColor.RED));
                return true;
            }
            target = plugin.getServer().getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(Component.text("Player tidak ditemukan: " + args[0]).color(NamedTextColor.RED));
                return true;
            }
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage(Component.text("Only players can use this command!").color(NamedTextColor.RED));
                return true;
            }
            target = (Player) sender;
        }

        if (gameManager.unregisterPlayer(target)) {
            sender.sendMessage(Component.text(target.getName() + " berhasil dihapus dari permainan!").color(NamedTextColor.GREEN));
            if (!sender.equals(target)) {
                target.sendMessage(Component.text("Kamu telah dihapus dari permainan!").color(NamedTextColor.YELLOW));
            }
            
            // Re-check impostor limit
            int currentPlayers = gameManager.getRegisteredPlayerCount();
            int maxImpostors = gameManager.getMaxImpostorCount(currentPlayers > 0 ? currentPlayers : 3);
            if (gameManager.getImpostorCount() > maxImpostors) {
                gameManager.setImpostorCount(maxImpostors);
                Bukkit.broadcast(Component.text("Jumlah Impostor menjadi " + maxImpostors).color(NamedTextColor.YELLOW));
            }
            
        } else {
            sender.sendMessage(Component.text(target.getName() + " tidak terdaftar.").color(NamedTextColor.YELLOW));
        }
        return true;
    }

    // ===== /start =====
    private boolean handleStart(CommandSender sender) {
        if (!gameManager.isLobby()) {
            sender.sendMessage(Component.text("Permainan sedang berlangsung! Gunakan /endgame untuk menghentikannya.").color(NamedTextColor.RED));
            return true;
        }

        int registered = gameManager.getRegisteredPlayerCount();
        if (registered < 3) {
            sender.sendMessage(Component.text("Jumlah pemain minimal 3! Saat ini : " + registered)
                    .color(NamedTextColor.RED));
            return true;
        }

        sender.sendMessage(Component.text("Memulai Permainan...").color(NamedTextColor.GREEN));
        gameManager.startGame();
        return true;
    }

    // ===== /meeting =====
    private boolean handleMeeting(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(Component.text("Only players can call meetings!").color(NamedTextColor.RED));
            return true;
        }

        if (!gameManager.isPlaying()) {
            sender.sendMessage(Component.text("Tidak bisa meeting saat permainan tidak berlangsung!").color(NamedTextColor.RED));
            return true;
        }

        if (!gameManager.canCallMeeting()) {
            int cooldown = gameManager.getMeetingCooldownRemaining();
            sender.sendMessage(Component.text("Meeting sedang cooldown: " + cooldown + " detik lagi!")
                    .color(NamedTextColor.RED));
            return true;
        }

        Player player = (Player) sender;
        PlayerData pd = gameManager.getPlayerData(player);
        if (pd == null || !pd.isActive()) {
            sender.sendMessage(Component.text("Kamu telah tereliminasi!").color(NamedTextColor.RED));
            return true;
        }

        gameManager.startMeeting(player);
        return true;
    }

    // ===== /endgame =====
    private boolean handleEndGame(CommandSender sender) {
        if (!gameManager.isPlaying() && !gameManager.isMeeting() && gameManager.getGameState() != GameState.VOTING) {
            sender.sendMessage(Component.text("Tidak ada permainan yang sedang berlangsung!").color(NamedTextColor.RED));
            return true;
        }

        gameManager.endGame();
        return true;
    }



    // ===== /listplayer =====
    private boolean handleListPlayer(CommandSender sender) {
        sender.sendMessage(Component.text("DAFTAR PESERTA:").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        for (PlayerData pd : gameManager.getRegisteredPlayers()) {
            sender.sendMessage(Component.text("- ").color(NamedTextColor.GRAY).append(Component.text(pd.getPlayerName()).color(NamedTextColor.WHITE)));
        }
        return true;
    }



    // ===== /commandinfo =====
    private boolean handleCommandInfo(CommandSender sender) {
        sender.sendMessage(Component.text("   DAFTAR COMMAND   ").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        sender.sendMessage(Component.text("/regis <nama> ").color(NamedTextColor.GREEN).append(Component.text("- Daftarkan player ke permainan").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/regisall ").color(NamedTextColor.GREEN).append(Component.text("- Daftarkan semua player online").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/unregis <nama> ").color(NamedTextColor.GREEN).append(Component.text("- Hapus player dari daftar").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/listplayer ").color(NamedTextColor.GREEN).append(Component.text("- Lihat daftar pemain terdaftar").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/start ").color(NamedTextColor.GREEN).append(Component.text("- Mulai permainan").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/meeting ").color(NamedTextColor.GREEN).append(Component.text("- Memanggil meeting untuk meneliminasi player").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/skip ").color(NamedTextColor.GREEN).append(Component.text("- Lewati giliranmu").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/endgame ").color(NamedTextColor.GREEN).append(Component.text("- Mengakhiri permainan").color(NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("/commandinfo ").color(NamedTextColor.GREEN).append(Component.text("- Lah ini").color(NamedTextColor.GRAY)));
        return true;
    }

    // ===== /skip =====
    private boolean handleSkip(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Hanya player yang bisa menggunakan command ini.").color(NamedTextColor.RED));
            return true;
        }
        if (!player.isOp()) {
            player.sendMessage(Component.text("Hanya OP yang bisa menggunakan command ini!").color(NamedTextColor.RED));
            return true;
        }
        if (!gameManager.isPlaying()) {
            player.sendMessage(Component.text("Game belum dimulai!").color(NamedTextColor.RED));
            return true;
        }
        // Safe null check for meeting world
        org.bukkit.World meetingWorld = gameManager.getWorldManager().getMeetingWorld();
        if (meetingWorld != null && player.getWorld().equals(meetingWorld)) {
            player.sendMessage(Component.text("Tidak bisa skip giliran saat di world meeting!").color(NamedTextColor.RED));
            return true;
        }
        gameManager.getTurnManager().skipCurrentTurn();
        return true;
    }

    // ===== Tab Completion =====

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmdName = command.getName().toLowerCase();
        List<String> completions = new ArrayList<>();

        switch (cmdName) {
            case "regis":
            case "unregis":
                // Complete with online player names
                if (args.length == 1) {
                    completions.addAll(plugin.getServer().getOnlinePlayers().stream()
                            .map(Player::getName)
                            .collect(Collectors.toList()));
                }
                break;


            case "meeting":
            case "start":
            case "endgame":
            case "listplayer":
            case "regisall":
            case "commandinfo":
                // No arguments needed
                break;
        }

        // Filter based on what user has typed
        if (args.length > 0) {
            String input = args[args.length - 1].toLowerCase();
            completions = completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(input))
                    .collect(Collectors.toList());
        }

        return completions;
    }
}
