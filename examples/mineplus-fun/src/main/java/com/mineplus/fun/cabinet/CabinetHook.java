package com.mineplus.fun.cabinet;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockHook;
import com.mineplus.infrastructure.core.multiblock.lifecycle.MultiBlockLifecycleManager;
import com.mineplus.infrastructure.core.util.Cooldowns;
import org.bukkit.entity.Player;

/**
 * Right-click behavior: a closed cabinet swaps to the open model and shows the
 * storage menu; an open cabinet swaps back to the closed model (and closes any
 * viewer's menu, which persists their items). The interact-pair cooldown is the
 * standard main/off-hand dedupe.
 */
public final class CabinetHook implements MultiBlockHook {

    private static final long INTERACT_COOLDOWN_MS = 1000L;

    private final PluginContext context;
    private final MultiBlockLifecycleManager lifecycleManager;
    private final Cooldowns interactCooldowns = new Cooldowns();

    public CabinetHook(PluginContext context, MultiBlockLifecycleManager lifecycleManager) {
        this.context = context;
        this.lifecycleManager = lifecycleManager;
    }

    @Override
    public void onInteract(MultiBlockInstance instance, Player actor) {
        if (!interactCooldowns.tryAcquire(instance.id(), INTERACT_COOLDOWN_MS)) {
            return;
        }

        if (instance.level() >= CabinetKeys.LEVEL_OPEN) {
            lifecycleManager.setLevel(instance.id(), CabinetKeys.LEVEL_CLOSED);
            // Closing the menu (if this actor had it open) runs the GUI's capture
            // and close hook; the level is already CLOSED there, so no double swap.
            actor.closeInventory();
            return;
        }

        if (context.infrastructureApi().openGui(CabinetKeys.GUI_KEY, actor, instance)) {
            lifecycleManager.setLevel(instance.id(), CabinetKeys.LEVEL_OPEN);
        }
    }

    @Override
    public void onBreak(MultiBlockInstance instance, Player actor) {
        CabinetStore.dropAll(instance);
    }

    @Override
    public void onRemove(MultiBlockInstance instance, Player actor) {
        CabinetStore.dropAll(instance);
    }
}
