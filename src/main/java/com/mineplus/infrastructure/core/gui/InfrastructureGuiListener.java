package com.mineplus.infrastructure.core.gui;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class InfrastructureGuiListener implements Listener {

    private final InfrastructureGuiManager guiManager;

    public InfrastructureGuiListener(InfrastructureGuiManager guiManager) {
        this.guiManager = guiManager;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        guiManager.handleClick(event);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        guiManager.handleDrag(event);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        guiManager.handleClose(event);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        guiManager.handleQuit(event.getPlayer());
    }
}
