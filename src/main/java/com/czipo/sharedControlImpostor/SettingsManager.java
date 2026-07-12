package com.czipo.sharedControlImpostor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.Sound;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.GameRule;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import net.kyori.adventure.key.Key;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SettingsManager implements Listener {

    private final SharedControlImpostor plugin;
    private final GameManager gameManager;

    private boolean oneLifeMode = false;
    private boolean createNewWorld = true;
    private boolean oneObjectiveMode = true; // default true

    // Objective dialog state
    private String objectiveType = "random"; // "random", "template", "custom"
    private int selectedTemplateIndex = 0;
    private final List<Integer> selectedOwnTemplateIndices = new ArrayList<>();
    private String customObjectiveText = "";

    public SettingsManager(SharedControlImpostor plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;

        // Start repeating task to enforce OP settings item in Lobby
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (gameManager.isLobby()) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.isOp()) {
                        // Ensure they have it
                        if (!isSettingsItem(p.getInventory().getItem(4))) {
                            giveSettingsItem(p);
                        }
                    } else {
                        // Ensure they DON'T have it
                        removeSettingsItem(p);
                    }
                }
            }
        }, 20L, 20L); // every 1 second
    }

    public boolean isOneLifeMode() { return oneLifeMode; }
    public boolean isCreateNewWorld() { return createNewWorld; }
    public boolean isOneObjectiveMode() { return oneObjectiveMode; }
    public String getObjectiveType() { return objectiveType; }
    public int getSelectedTemplateIndex() { return selectedTemplateIndex; }
    public String getCustomObjectiveText() { return customObjectiveText; }

    /**
     * Give the settings item to OP player (slot 4)
     */
    public void giveSettingsItem(Player player) {
        if (!player.isOp()) return;
        ItemStack item = new ItemStack(Material.TEST_BLOCK);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("Settings").color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
            item.setItemMeta(meta);
        }
        player.getInventory().setItem(4, item); // Slot 5 (0-indexed)
    }

    /**
     * Remove settings item from all players (called when game starts)
     */
    public void removeSettingsItem(Player player) {
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack it = player.getInventory().getItem(i);
            if (isSettingsItem(it)) {
                player.getInventory().setItem(i, null);
            }
        }
        // Also check offhand
        if (isSettingsItem(player.getInventory().getItemInOffHand())) {
            player.getInventory().setItemInOffHand(null);
        }
    }

    private boolean isSettingsItem(ItemStack item) {
        if (item == null || item.getType() != Material.TEST_BLOCK) return false;
        if (!item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) return false;
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(item.getItemMeta().displayName()).equals("Settings");
    }

    /**
     * Open the hopper GUI
     */
    public void openSettingsUI(Player player) {
        Inventory inv = Bukkit.createInventory(null, InventoryType.HOPPER, Component.text("Settings"));

        // Slot 0: One Life
        ItemStack totem = new ItemStack(Material.TOTEM_OF_UNDYING);
        ItemMeta totemMeta = totem.getItemMeta();
        if (oneLifeMode) {
            totemMeta.displayName(Component.text("One Life : ON").color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
            totemMeta.lore(List.of(Component.text("Jika mati, maka impostor menang").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        } else {
            totemMeta.displayName(Component.text("One Life : OFF").color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            totemMeta.lore(List.of(Component.text("Jika mati, player akan respawn").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        }
        totem.setItemMeta(totemMeta);
        inv.setItem(0, totem);

        // Slot 1: World Toggle
        ItemStack worldToggle;
        if (createNewWorld) {
            worldToggle = new ItemStack(Material.GRASS_BLOCK);
            ItemMeta meta = worldToggle.getItemMeta();
            meta.displayName(Component.text("Buat world baru").color(NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("Membuat world baru dan menghapus world sebelumnya").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            worldToggle.setItemMeta(meta);
        } else {
            worldToggle = new ItemStack(Material.DIRT);
            ItemMeta meta = worldToggle.getItemMeta();
            meta.displayName(Component.text("Lanjutkan world sebelumnya").color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("Melanjutkan world serta progress dari world sebelumnya").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            worldToggle.setItemMeta(meta);
        }
        inv.setItem(1, worldToggle);

        // Slot 2: Objective (opens dialog)
        ItemStack book = new ItemStack(Material.BOOK);
        ItemMeta bookMeta = book.getItemMeta();
        bookMeta.displayName(Component.text("Objective").color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        book.setItemMeta(bookMeta);
        inv.setItem(2, book);

        // Slot 3: Impostor Count - custom head with Among Us skin
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) head.getItemMeta();
        skullMeta.displayName(Component.text("Jumlah Impostor : " + gameManager.getImpostorCount()).color(NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        skullMeta.lore(List.of(
                Component.text("Klik Kanan : +").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Klik Kiri : -").color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
        ));
        // Set Among Us skin texture
        try {
            org.bukkit.profile.PlayerTextures textures;
            PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID(), "AmongUs");
            textures = profile.getTextures();
            textures.setSkin(new java.net.URL("http://textures.minecraft.net/texture/173766f2af552a81be855df460e21cb70c072c31539 2f8934c091345 9c449620".replace(" ", "")));
            profile.setTextures(textures);
            skullMeta.setOwnerProfile(profile);
        } catch (Exception ignored) {
            // Fallback - use default head
        }
        head.setItemMeta(skullMeta);
        inv.setItem(3, head);

        // Slot 4: Set Timer
        ItemStack clock = new ItemStack(Material.CLOCK);
        ItemMeta clockMeta = clock.getItemMeta();
        clockMeta.displayName(Component.text("Set Timer").color(NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
        clock.setItemMeta(clockMeta);
        inv.setItem(4, clock);

        player.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        // Prevent moving settings item from player inventory
        if (isSettingsItem(event.getCurrentItem())) {
            event.setCancelled(true);
            return;
        }
        // Prevent swapping items into the settings slot (slot 4)
        if (event.getHotbarButton() == 4) {
            ItemStack hotbarItem = player.getInventory().getItem(4);
            if (isSettingsItem(hotbarItem)) {
                event.setCancelled(true);
                return;
            }
        }
        // Prevent clicking anything INTO slot 4 if Settings is there
        // Only apply when NOT inside the Settings GUI (otherwise it blocks the Set Timer button!)
        boolean isInSettingsGui = event.getView().title().equals(Component.text("Settings"));
        if (!isInSettingsGui && event.getSlot() == 4 && isSettingsItem(player.getInventory().getItem(4))) {
            event.setCancelled(true);
            return;
        }

        // Settings GUI
        if (event.getView().title().equals(Component.text("Settings"))) {
            event.setCancelled(true);
            int slot = event.getRawSlot();
            if (slot == 0) { // One Life
                oneLifeMode = !oneLifeMode;
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                openSettingsUI(player);
            } else if (slot == 1) { // World Toggle
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                createNewWorld = !createNewWorld;
                openSettingsUI(player);
            } else if (slot == 2) { // Objective — open dialog
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    player.closeInventory();
                    openObjectiveDialog(player);
                }, 2L);
            } else if (slot == 3) { // Impostor Count
                int currentCount = gameManager.getImpostorCount();
                int playerCount = gameManager.getRegisteredPlayerCount();
                int maxImpostors = gameManager.getMaxImpostorCount(playerCount > 0 ? playerCount : 3);

                if (event.isRightClick()) { // Right click = add
                    int newCount = currentCount + 1;
                    if (newCount > maxImpostors) {
                        player.closeInventory();
                        player.sendMessage(Component.text("Maksimal Jumlah Impostor : " + maxImpostors).color(NamedTextColor.RED));
                        return;
                    }
                    gameManager.setImpostorCount(newCount);
                } else if (event.isLeftClick()) { // Left click = subtract
                    if (currentCount > 1) {
                        gameManager.setImpostorCount(currentCount - 1);
                    }
                }
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                openSettingsUI(player);
            } else if (slot == 4) { // Set Timer - use /dialog show
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    player.closeInventory();
                    openTimerDialog(player, false);
                }, 2L);
            }
        }
    }

    @EventHandler
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        // Prevent moving settings item to offhand
        if (isSettingsItem(event.getMainHandItem()) || isSettingsItem(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getItem() != null && event.getItem().getType() == Material.TEST_BLOCK) {
            if (isSettingsItem(event.getItem())) {
                if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
                    if (event.getPlayer().isOp() && gameManager.isLobby()) {
                        openSettingsUI(event.getPlayer());
                        event.setCancelled(true);
                    }
                }
            }
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (isSettingsItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    // No longer using ServerCommandEvent for OP/DEOP syncing since BukkitRunnable handles it reliably

    // ===== OBJECTIVE DIALOG SYSTEM =====

    /** Main entry – opens the correct page based on current state */
    public void openObjectiveDialog(Player player) {
        if (oneObjectiveMode && objectiveType.equals("random"))       openObjectivePage1(player);
        else if (!oneObjectiveMode && objectiveType.equals("random")) openObjectivePage2(player);
        else if (oneObjectiveMode && objectiveType.equals("template")) openObjectivePage3(player);
        else if (!oneObjectiveMode && objectiveType.equals("template")) openObjectivePage4(player);
        else if (oneObjectiveMode && objectiveType.equals("custom"))   openObjectivePage5(player);
        else /* own + custom */                                         openObjectivePage6(player);
    }

    /** Helper to dispatch a dialog to a player cleanly (no chat spam) */
    private void showDialog(Player player, String json) {
        org.bukkit.World meetingWorld = gameManager.getWorldManager() != null ? gameManager.getWorldManager().getMeetingWorld() : null;
        if (meetingWorld != null) meetingWorld.setGameRule(org.bukkit.GameRule.SEND_COMMAND_FEEDBACK, false);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "dialog show " + player.getName() + " " + json);
        if (meetingWorld != null) meetingWorld.setGameRule(org.bukkit.GameRule.SEND_COMMAND_FEEDBACK, true);
    }

    /** Build a JSON string for a single_option input with all objectives from the given pool */
    private String buildTemplateOptions(List<String> displayNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"type\":\"minecraft:single_option\",\"key\":\"template_idx\",\"label\":\"Pilih Objektif\",\"options\":[");
        for (int i = 0; i < displayNames.size(); i++) {
            if (i > 0) sb.append(",");
            String esc = displayNames.get(i).replace("\\", "\\\\").replace("\"", "\\\"");
            sb.append("{\"id\":\"" + i + "\",\"display\":\"" + esc + "\"}");
        }
        sb.append("],\"default\":" + selectedTemplateIndex + "}");
        return sb.toString();
    }

    /** Build multiple JSON inputs for own-objective mode */
    private String buildMultipleTemplateOptions(List<String> displayNames, int count) {
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < count; j++) {
            if (j > 0) sb.append(",");
            int init = (selectedOwnTemplateIndices.size() > j) ? selectedOwnTemplateIndices.get(j) : 0;
            sb.append("{\"type\":\"minecraft:single_option\",\"key\":\"template_idx_").append(j)
              .append("\",\"label\":\"Pilih Objektif ").append(j + 1).append("\",\"options\":[");
            for (int i = 0; i < displayNames.size(); i++) {
                if (i > 0) sb.append(",");
                String esc = displayNames.get(i).replace("\\", "\\\\").replace("\"", "\\\"");
                sb.append("{\"id\":\"").append(i).append("\",\"display\":\"").append(esc).append("\"}");
            }
            sb.append("],\"default\":").append(init).append("}");
        }
        return sb.toString();
    }

    /** Helper to get the one-objective display names */
    private List<String> getOneObjectiveNames() {
        List<String> names = new ArrayList<>();
        for (Objective o : gameManager.getObjectiveManager().getOneObjectivePool()) names.add(o.getDescription());
        return names;
    }

    /** Helper to get the own-objective display names */
    private List<String> getOwnObjectiveNames() {
        List<String> names = new ArrayList<>();
        for (Objective o : gameManager.getObjectiveManager().getOwnObjectivePool()) names.add(o.getDescription());
        return names;
    }

    // ---------- PAGE 1 : One Objective + Random ----------
    private void openObjectivePage1(Player player) {
        String json = "{"
            + "\"type\":\"minecraft:multi_action\","
            + "\"title\":\"Objective\","
            + "\"inputs\":[],"
            + "\"can_close_with_escape\":false,"
            + "\"exit_action\":{\"label\":\"Simpan\",\"width\":300},"
            + "\"actions\":["
            + "{\"label\":\"Mode : One Objective\",\"tooltip\":\"Investigator akan mendapat 1 objektif yang sama\",\"action\":{\"type\":\"dynamic/custom\",\"id\":\"sci:obj_toggle_mode\"}},"
            + "{\"label\":\"Random\",\"tooltip\":\"Mendapat objektif secara acak dari daftar template\",\"action\":{\"type\":\"dynamic/custom\",\"id\":\"sci:obj_toggle_type\"}}"
            + "]}";
        showDialog(player, json);
    }

    // ---------- PAGE 2 : Own Objective + Random ----------
    private void openObjectivePage2(Player player) {
        String json = "{"
            + "\"type\":\"minecraft:multi_action\","
            + "\"title\":\"Objective\","
            + "\"inputs\":[],"
            + "\"can_close_with_escape\":false,"
            + "\"exit_action\":{\"label\":\"Simpan\",\"width\":300},"
            + "\"actions\":["
            + "{\"label\":\"Mode : Own Objective\",\"tooltip\":\"Tiap Investigator akan mendapat objektifnya masing-masing\",\"action\":{\"type\":\"dynamic/custom\",\"id\":\"sci:obj_toggle_mode\"}},"
            + "{\"label\":\"Random\",\"tooltip\":\"Mendapat objektif secara acak dari daftar template\",\"action\":{\"type\":\"dynamic/custom\",\"id\":\"sci:obj_toggle_type\"}}"
            + "]}";
        showDialog(player, json);
    }

    // ---------- PAGE 3 : One Objective + Set Template ----------
    // NOTE: inputs (dropdown) always render ABOVE actions in the dialog.
    // There is no way to place the dropdown below the buttons in vanilla dialog JSON.
    private void openObjectivePage3(Player player) {
        String templateInput = buildTemplateOptions(getOneObjectiveNames());
        String json = "{"
            + "\"type\":\"minecraft:multi_action\","
            + "\"title\":\"Objective\","
            + "\"inputs\":[" + templateInput + "],"
            + "\"can_close_with_escape\":false,"
            + "\"exit_action\":{\"label\":\"Simpan\",\"width\":300,\"action\":{\"type\":\"dynamic/custom\",\"id\":\"sci:obj_save_template\"}},"
            + "\"actions\":["
            + "{\"label\":\"Mode : One Objective\",\"tooltip\":\"Investigator akan mendapat 1 objektif yang sama\",\"action\":{\"type\":\"dynamic/custom\",\"id\":\"sci:obj_toggle_mode\"}},"
            + "{\"label\":\"Set Template\",\"tooltip\":\"Pilih objektif dari daftar\",\"action\":{\"type\":\"dynamic/custom\",\"id\":\"sci:obj_toggle_type\"}}"
            + "]}";
        showDialog(player, json);
    }

    // ---------- PAGE 4 : Own Objective + Set Template ----------
    private void openObjectivePage4(Player player) {
        int playerCount = gameManager.getRegisteredPlayerCount();
        if (playerCount == 0) playerCount = Math.max(3, Bukkit.getOnlinePlayers().size());
        int investigatorCount = Math.max(1, playerCount - gameManager.getImpostorCount());

        String templateInput = buildMultipleTemplateOptions(getOwnObjectiveNames(), investigatorCount);
        String json = "{"
            + "\"type\":\"minecraft:multi_action\","
            + "\"title\":\"Objective\","
            + "\"inputs\":[" + templateInput + "],"
            + "\"can_close_with_escape\":false,"
            + "\"exit_action\":{\"label\":\"Simpan\",\"width\":300,\"action\":{\"type\":\"dynamic/custom\",\"id\":\"sci:obj_save_template\"}},"
            + "\"actions\":["
            + "{\"label\":\"Mode : Own Objective\",\"tooltip\":\"Tiap Investigator akan mendapat objektifnya masing-masing\",\"action\":{\"type\":\"dynamic/custom\",\"id\":\"sci:obj_toggle_mode\"}},"
            + "{\"label\":\"Set Template\",\"tooltip\":\"Pilih objektif dari daftar\",\"action\":{\"type\":\"dynamic/custom\",\"id\":\"sci:obj_toggle_type\"}}"
            + "]}";
        showDialog(player, json);
    }

    // ---------- PAGE 5 : One Objective + Set Custom ----------
    private void openObjectivePage5(Player player) {
        String json = "{"
            + "\"type\":\"minecraft:multi_action\","
            + "\"title\":\"Objective (Custom)\","
            + "\"inputs\":["
            + "{\"type\":\"minecraft:text_input\",\"key\":\"name\",\"label\":\"Nama Objektif\",\"placeholder\":\"Contoh: Mining Diamond\"},"
            + "{\"type\":\"minecraft:single_option\",\"key\":\"action\",\"label\":\"Aksi\",\"options\":[{\"id\":\"mining\",\"display\":\"Mining Block\"},{\"id\":\"pickup\",\"display\":\"Dapatkan Item\"},{\"id\":\"kill\",\"display\":\"Bunuh Mob\"}],\"default\":0},"
            + "{\"type\":\"minecraft:text_input\",\"key\":\"target\",\"label\":\"Target ID\",\"placeholder\":\"Contoh: diamond_ore\"},"
            + "{\"type\":\"minecraft:number_range\",\"key\":\"amount\",\"label\":\"Jumlah\",\"start\":1,\"end\":999,\"step\":1,\"initial\":1}"
            + "],"
            + "\"can_close_with_escape\":false,"
            + "\"exit_action\":{\"label\":\"Simpan\",\"width\":300,\"action\":{\"type\":\"dynamic/custom\",\"id\":\"sci:obj_save_custom\"}},"
            + "\"actions\":["
            + "{\"label\":\"Mode : One Objective\",\"tooltip\":\"Investigator akan mendapat 1 objektif yang sama\",\"action\":{\"type\":\"dynamic/custom\",\"id\":\"sci:obj_toggle_mode\"}},"
            + "{\"label\":\"Set Custom\",\"tooltip\":\"TBA\",\"action\":{\"type\":\"dynamic/custom\",\"id\":\"sci:obj_toggle_type\"}}"
            + "]}";
        showDialog(player, json);
    }

    // ---------- PAGE 6 : Own Objective + Set Custom ----------
    private void openObjectivePage6(Player player) {
        String json = "{"
            + "\"type\":\"minecraft:multi_action\","
            + "\"title\":\"Objective (Custom)\","
            + "\"inputs\":["
            + "{\"type\":\"minecraft:text_input\",\"key\":\"name\",\"label\":\"Nama Objektif\",\"placeholder\":\"Contoh: Mining Diamond\"},"
            + "{\"type\":\"minecraft:single_option\",\"key\":\"action\",\"label\":\"Aksi\",\"options\":[{\"id\":\"mining\",\"display\":\"Mining Block\"},{\"id\":\"pickup\",\"display\":\"Dapatkan Item\"},{\"id\":\"kill\",\"display\":\"Bunuh Mob\"}],\"default\":0},"
            + "{\"type\":\"minecraft:text_input\",\"key\":\"target\",\"label\":\"Target ID\",\"placeholder\":\"Contoh: diamond_ore\"},"
            + "{\"type\":\"minecraft:number_range\",\"key\":\"amount\",\"label\":\"Jumlah\",\"start\":1,\"end\":999,\"step\":1,\"initial\":1}"
            + "],"
            + "\"can_close_with_escape\":false,"
            + "\"exit_action\":{\"label\":\"Simpan\",\"width\":300,\"action\":{\"type\":\"dynamic/custom\",\"id\":\"sci:obj_save_custom\"}},"
            + "\"actions\":["
            + "{\"label\":\"Mode : Own Objective\",\"tooltip\":\"Tiap Investigator akan mendapat objektifnya masing-masing\",\"action\":{\"type\":\"dynamic/custom\",\"id\":\"sci:obj_toggle_mode\"}},"
            + "{\"label\":\"Set Custom\",\"tooltip\":\"TBA\",\"action\":{\"type\":\"dynamic/custom\",\"id\":\"sci:obj_toggle_type\"}}"
            + "]}";
        showDialog(player, json);
    }

    // ===== END OBJECTIVE DIALOG PAGES =====

    public void openTimerDialog(Player player, boolean useDefaults) {
        int swapTime = useDefaults ? 15 : gameManager.getTurnTimeSeconds();
        int voteTime = useDefaults ? 60 : (gameManager.getVoteManager() != null ? gameManager.getVoteManager().getVoteTimeSeconds() : 60);
        int cooldown = useDefaults ? 30 : gameManager.getMeetingCooldownSeconds();

        String dialogJson = "{"
                + "\"type\":\"minecraft:multi_action\","
                + "\"title\":\"Set Timer\","
                + "\"inputs\":["
                + "{\"type\":\"minecraft:number_range\",\"key\":\"swap\",\"label\":\"Timer Swap\",\"start\":10,\"end\":300,\"step\":1,\"initial\":" + swapTime + "},"
                + "{\"type\":\"minecraft:number_range\",\"key\":\"vote\",\"label\":\"Voting Time\",\"start\":30,\"end\":300,\"step\":1,\"initial\":" + voteTime + "},"
                + "{\"type\":\"minecraft:number_range\",\"key\":\"cooldown\",\"label\":\"Meeting Cooldown\",\"start\":0,\"end\":300,\"step\":1,\"initial\":" + cooldown + "}"
                + "],"
                + "\"can_close_with_escape\":false,"
                + "\"actions\":["
                + "{"
                + "\"label\":\"Kembali ke Default\","
                + "\"action\":{\"type\":\"dynamic/custom\",\"id\":\"sci:timer_default\"}"
                + "},"
                + "{"
                + "\"label\":\"Simpan\","
                + "\"action\":{\"type\":\"dynamic/custom\",\"id\":\"sci:timer_save\"}"
                + "}"
                + "]"
                + "}";

        showDialog(player, dialogJson);
    }

    @SuppressWarnings("UnstableApiUsage")
    @EventHandler
    public void onDialogClick(PlayerCustomClickEvent event) {
        if (!(event.getCommonConnection() instanceof io.papermc.paper.connection.PlayerGameConnection)) return;
        Player player = ((io.papermc.paper.connection.PlayerGameConnection) event.getCommonConnection()).getPlayer();
        Key id = event.getIdentifier();
        if (id == null) return;
        String idStr = id.asString();
        DialogResponseView view = event.getDialogResponseView();

        // ===== TIMER DIALOG =====
        if (idStr.equals("sci:timer_default")) {
            Bukkit.getScheduler().runTask(plugin, () -> openTimerDialog(player, true));
            return;
        }
        if (idStr.equals("sci:timer_save")) {
            if (view == null) return;
            Float swapF = view.getFloat("swap");
            Float voteF = view.getFloat("vote");
            Float cooldownF = view.getFloat("cooldown");
            if (swapF == null || voteF == null || cooldownF == null) return;
            int swap = swapF.intValue();
            int vote = voteF.intValue();
            int cooldown = cooldownF.intValue();
            gameManager.setTurnTimeSeconds(swap);
            if (gameManager.getVoteManager() != null) gameManager.getVoteManager().setVoteTime(vote);
            gameManager.setMeetingCooldownSeconds(cooldown);
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            player.sendMessage(Component.text("Timer diperbarui! Swap: " + swap + "s | Voting: " + vote + "s | Cooldown: " + cooldown + "s").color(NamedTextColor.GREEN));
            return;
        }

        // ===== OBJECTIVE DIALOG =====
        if (!idStr.startsWith("sci:obj_")) return;

        // Read template selection from current view (if applicable)
        if (view != null) {
            if (oneObjectiveMode) {
                Float idxF = null;
                try { idxF = view.getFloat("template_idx"); } catch (Exception ignored) {}
                if (idxF != null) {
                    selectedTemplateIndex = idxF.intValue();
                } else {
                    String idxStr = null;
                    try { idxStr = view.getText("template_idx"); } catch (Exception ignored) {}
                    if (idxStr != null && !idxStr.isEmpty()) {
                        try { selectedTemplateIndex = Integer.parseInt(idxStr); } catch (NumberFormatException ignored) {}
                    }
                }
            } else {
                int playerCount = gameManager.getRegisteredPlayerCount();
                if (playerCount == 0) playerCount = Math.max(3, Bukkit.getOnlinePlayers().size());
                int investigatorCount = Math.max(1, playerCount - gameManager.getImpostorCount());
                
                selectedOwnTemplateIndices.clear();
                for (int i = 0; i < investigatorCount; i++) {
                    Float valF = null;
                    try { valF = view.getFloat("template_idx_" + i); } catch (Exception ignored) {}
                    
                    if (valF != null) {
                        selectedOwnTemplateIndices.add(valF.intValue());
                    } else {
                        String idxStr = null;
                        try { idxStr = view.getText("template_idx_" + i); } catch (Exception ignored) {}
                        if (idxStr != null && !idxStr.isEmpty()) {
                            try { selectedOwnTemplateIndices.add(Integer.parseInt(idxStr)); } catch (NumberFormatException ignored) { selectedOwnTemplateIndices.add(0); }
                        } else {
                            selectedOwnTemplateIndices.add(0);
                        }
                    }
                }
            }
        }

        switch (idStr) {
            case "sci:obj_toggle_mode" -> {
                oneObjectiveMode = !oneObjectiveMode;
                Bukkit.getScheduler().runTask(plugin, () -> openObjectiveDialog(player));
            }
            case "sci:obj_toggle_type" -> {
                // Cycle random -> template -> custom -> random
                switch (objectiveType) {
                    case "random" -> objectiveType = "template";
                    case "template" -> objectiveType = "custom";
                    default -> objectiveType = "random";
                }
                Bukkit.getScheduler().runTask(plugin, () -> openObjectiveDialog(player));
            }
            case "sci:obj_save_template" -> {
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                if (oneObjectiveMode) {
                    List<String> names = getOneObjectiveNames();
                    String selected = (selectedTemplateIndex >= 0 && selectedTemplateIndex < names.size())
                            ? names.get(selectedTemplateIndex) : "?";
                    player.sendMessage(Component.text("Objective disimpan: §e" + selected).color(NamedTextColor.GREEN));
                    gameManager.getObjectiveManager().setFixedOneTemplateIndex(selectedTemplateIndex);
                } else {
                    List<String> names = getOwnObjectiveNames();
                    player.sendMessage(Component.text("Objective disimpan:").color(NamedTextColor.GREEN));
                    for (int idx : selectedOwnTemplateIndices) {
                        String selected = (idx >= 0 && idx < names.size()) ? names.get(idx) : "?";
                        player.sendMessage(Component.text("- " + selected).color(NamedTextColor.YELLOW));
                    }
                    gameManager.getObjectiveManager().setFixedOwnTemplates(selectedOwnTemplateIndices);
                }
            }
            case "sci:obj_save_custom" -> {
                if (view != null) {
                    String name = "";
                    try { name = view.getText("name"); } catch (Exception ignored) {}
                    String action = "mining";
                    try { 
                        float actionIdx = view.getFloat("action");
                        if (actionIdx == 1.0f) action = "pickup";
                        else if (actionIdx == 2.0f) action = "kill";
                    } catch (Exception ignored) {}
                    String target = "";
                    try { target = view.getText("target"); } catch (Exception ignored) {}
                    int amount = 1;
                    try { amount = view.getFloat("amount").intValue(); } catch (Exception ignored) {}
                    
                    if (name.isEmpty() || target.isEmpty()) {
                        player.sendMessage(Component.text("Nama dan Target tidak boleh kosong!").color(NamedTextColor.RED));
                        return;
                    }
                    
                    CustomObjectiveData data = new CustomObjectiveData(name, action, target, amount);
                    if (oneObjectiveMode) {
                        gameManager.getObjectiveManager().setCustomOneObjective(data);
                        player.sendMessage(Component.text("Objective custom disimpan: §e" + name).color(NamedTextColor.GREEN));
                    } else {
                        gameManager.getObjectiveManager().addCustomOwnObjective(data);
                        player.sendMessage(Component.text("Objective custom ditambah: §e" + name).color(NamedTextColor.GREEN));
                        // Re-open dialog to allow adding more
                        Bukkit.getScheduler().runTask(plugin, () -> openObjectiveDialog(player));
                        return; // don't close yet
                    }
                }
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            }
        }
    }
}
