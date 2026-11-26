package com.mineplus.machine;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Routes right-clicks on placed brake levers to the feature's toggle.
 */
final class BrakeListener implements Listener {

    private static final double REACH = 5.0;

    private final MineplusMachinePlugin feature;

    BrakeListener(MineplusMachinePlugin feature) {
        this.feature = feature;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        MultiBlockInstance instance = feature.resolveLookedAt(event.getPlayer());
        if (instance != null) {
            event.setCancelled(feature.toggleBrake(instance));
        }
    }
}
