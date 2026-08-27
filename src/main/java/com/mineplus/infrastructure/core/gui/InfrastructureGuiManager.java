package com.mineplus.infrastructure.core.gui;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.util.StringNormalizer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;

public final class InfrastructureGuiManager {

    private final Map<String, InfrastructureGui> guis;
    private final Map<UUID, OpenGuiSession> openSessions;

    public InfrastructureGuiManager() {
        this.guis = new LinkedHashMap<>();
        this.openSessions = new LinkedHashMap<>();
    }

    public void register(String key, InfrastructureGui gui) {
        guis.put(normalize(key), gui);
    }

    public boolean open(String key, Player player, MultiBlockInstance instance) {
        InfrastructureGui gui = guis.get(normalize(key));
        if (gui == null) {
            return false;
        }
        gui.open(player, instance);

        Inventory topInventory = player.getOpenInventory() == null ? null : player.getOpenInventory().getTopInventory();
        if (topInventory != null) {
            openSessions.put(player.getUniqueId(), new OpenGuiSession(normalize(key), instance.id(), topInventory));
        }
        return true;
    }

    public boolean contains(String key) {
        return guis.containsKey(normalize(key));
    }

    public void handleClick(InventoryClickEvent event) {
        OpenGuiSession session = sessionFor(event.getWhoClicked().getUniqueId());
        if (session == null) {
            return;
        }

        if (event.getView().getTopInventory() != session.inventory()) {
            return;
        }

        InfrastructureGui gui = guis.get(session.key());
        if (gui instanceof InteractiveInfrastructureGui interactiveGui && event.getWhoClicked() instanceof Player player) {
            interactiveGui.onClick(player, session.instanceId(), event);
        }
    }

    public void handleDrag(InventoryDragEvent event) {
        OpenGuiSession session = sessionFor(event.getWhoClicked().getUniqueId());
        if (session == null) {
            return;
        }

        if (event.getView().getTopInventory() != session.inventory()) {
            return;
        }

        InfrastructureGui gui = guis.get(session.key());
        if (gui instanceof InteractiveInfrastructureGui interactiveGui && event.getWhoClicked() instanceof Player player) {
            interactiveGui.onDrag(player, session.instanceId(), event);
        }
    }

    public void handleClose(InventoryCloseEvent event) {
        OpenGuiSession session = openSessions.remove(event.getPlayer().getUniqueId());
        if (session == null) {
            return;
        }

        InfrastructureGui gui = guis.get(session.key());
        if (gui instanceof InteractiveInfrastructureGui interactiveGui && event.getPlayer() instanceof Player player) {
            interactiveGui.onClose(player, session.instanceId(), event);
        }
    }

    private String normalize(String value) {
        return StringNormalizer.normalize(value);
    }

    private OpenGuiSession sessionFor(UUID playerId) {
        return openSessions.get(playerId);
    }

    private record OpenGuiSession(String key, UUID instanceId, Inventory inventory) {
    }
}
