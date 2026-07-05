package com.czipo.sharedControlImpostor;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

/**
 * Handles voting panel inventory clicks separately from the main GameListener
 * to ensure proper vote handling without interference.
 */
public class VoteListener implements Listener {

    private final SharedControlImpostor plugin;
    private final GameManager gameManager;

    public VoteListener(SharedControlImpostor plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        // Check if the inventory title matches our voting panel using Component comparison
        String title = PlainTextComponentSerializer.plainText().serialize(event.getView().title());
        if (title.contains("VOTING")) {
            // This is a voting panel click - delegate to VoteManager
            gameManager.getVoteManager().handleVoteClick(event);
            return;
        }
    }


}
