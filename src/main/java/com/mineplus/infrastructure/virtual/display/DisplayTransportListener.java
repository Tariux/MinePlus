package com.mineplus.infrastructure.virtual.display;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Feeds player session events into the {@link DisplayTransport} so viewer LOD
 * state tracks joins, quits, world changes and movement.
 */
public final class DisplayTransportListener implements Listener {

    private final DisplayTransport transport;

    public DisplayTransportListener(DisplayTransport transport) {
        this.transport = transport;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        transport.handleJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        transport.handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null) {
            return;
        }
        transport.markMoved(event.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getTo() == null) {
            return;
        }
        boolean crossWorld = event.getFrom().getWorld() != event.getTo().getWorld();
        if (crossWorld) {
            transport.handleWorldChange(event.getPlayer());
        } else {
            transport.markMoved(event.getPlayer(), true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        transport.handleWorldChange(event.getPlayer());
    }
}
