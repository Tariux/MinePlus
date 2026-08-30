package com.mineplus.fun.gear;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockHook;
import org.bukkit.entity.Player;

/**
 * Re-evaluates the gear grid on every lifecycle change that can alter the
 * power topology: placement (a gear joins a live train), model reload
 * (respawn/upgrade resets animation controllers), and break/remove (a train
 * loses a member). The evaluation request is debounced by the feature.
 */
final class GearHook implements MultiBlockHook {

    private final Runnable evaluator;

    GearHook(Runnable evaluator) {
        this.evaluator = evaluator;
    }

    @Override
    public void onPlace(MultiBlockInstance instance, Player actor) {
        evaluator.run();
    }

    @Override
    public void onModelReload(MultiBlockInstance instance) {
        evaluator.run();
    }

    @Override
    public void onBreak(MultiBlockInstance instance, Player actor) {
        evaluator.run();
    }

    @Override
    public void onRemove(MultiBlockInstance instance, Player actor) {
        evaluator.run();
    }
}
