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
            dispatch(player, session, "onClick", () -> interactiveGui.onClick(player, session.instanceId(), event));
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
            dispatch(player, session, "onDrag", () -> interactiveGui.onDrag(player, session.instanceId(), event));
        }
    }

    public void handleClose(InventoryCloseEvent event) {
        OpenGuiSession session = openSessions.remove(event.getPlayer().getUniqueId());
        if (session == null) {
            return;
        }

        InfrastructureGui gui = guis.get(session.key());
        if (gui instanceof InteractiveInfrastructureGui interactiveGui && event.getPlayer() instanceof Player player) {
            dispatch(player, session, "onClose", () -> interactiveGui.onClose(player, session.instanceId(), event));
        }
    }

    /**
     * Drops a player's open GUI session without firing {@code onClose}. Called
     * on player quit, where the inventory is gone and no close event carries
     * meaningful state — without this, sessions leaked for offline players.
     */
    public void handleQuit(Player player) {
        openSessions.remove(player.getUniqueId());
    }

    /**
     * Runs a GUI callback with exception isolation: a throwing module GUI is
     * logged and skipped instead of breaking the inventory-event pipeline for
     * every other module.
     */
    private void dispatch(Player player, OpenGuiSession session, String callback, Runnable invocation) {
        try {
            invocation.run();
        } catch (Throwable throwable) {
            org.bukkit.plugin.Plugin owner = org.bukkit.Bukkit.getPluginManager().getPlugin("Mineplus");
            java.util.logging.Logger logger = owner == null
                    ? java.util.logging.Logger.getLogger("Mineplus")
                    : owner.getLogger();
            logger.log(java.util.logging.Level.SEVERE,
                    "GUI '" + session.key() + "' threw in " + callback + " for player " + player.getName()
                            + "; isolating and continuing.", throwable);
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
