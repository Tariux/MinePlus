package com.mineplus.fun.gear;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockRedstoneEvent;

/**
 * Immediate reaction to redstone changes: any current change anywhere in the
 * world requests one debounced grid evaluation on the next server tick, so a
 * flipped lever spins its gear train without waiting for the periodic pass.
 */
final class GearRedstoneListener implements Listener {

    private final Runnable evaluator;

    GearRedstoneListener(Runnable evaluator) {
        this.evaluator = evaluator;
    }

    @EventHandler
    public void onRedstoneChange(BlockRedstoneEvent event) {
        if (event.getNewCurrent() != event.getOldCurrent()) {
            evaluator.run();
        }
    }
}
