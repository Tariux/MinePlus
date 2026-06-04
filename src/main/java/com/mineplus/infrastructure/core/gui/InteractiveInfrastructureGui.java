package com.mineplus.infrastructure.core.gui;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public interface InteractiveInfrastructureGui extends InfrastructureGui {

    default void onClick(Player player, UUID instanceId, InventoryClickEvent event) {
    }

    default void onDrag(Player player, UUID instanceId, InventoryDragEvent event) {
    }

    default void onClose(Player player, UUID instanceId, InventoryCloseEvent event) {
    }
}
