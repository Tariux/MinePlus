package com.mineplus.examples;

import com.mineplus.infrastructure.DeveloperApi2;
import com.mineplus.infrastructure.core.multiblock.progress.MachineProcess;
import com.mineplus.infrastructure.render.RenderDiagnostics.RenderSnapshot;
import com.mineplus.infrastructure.virtual.PlacementOptions;
import com.mineplus.infrastructure.virtual.VirtualBlockPlacementHelper.PlacementData;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Developer API 2.0 — the vanilla-trick primitives, reached through
 * {@code plugin.context().dev()} on a plain Spigot server (no NMS).
 *
 * <p>Copy the method bodies you need; each one is a complete, working call
 * pattern against a real Core API surface.
 */
public final class DeveloperApi2Example {

    private final DeveloperApi2 dev;

    public DeveloperApi2Example(DeveloperApi2 dev) {
        this.dev = dev;
    }

    /** Fine-grained furniture placement: 22.5-degree yaw lattice, air target, flush offset. */
    public PlacementData placeFurniture(Player player, double reach) {
        PlacementOptions options = PlacementOptions.DEFAULT
                .withYawSnap(22.5f)
                .withFaceOffset(0.25)
                .withAirTargetCheck(true);
        return dev.placementFor(player, reach, options);
    }

    /** Spawns a virtual model with collision cells (meta: barrier, slime, honey, cobweb, ...). */
    public UUID spawnModel(com.mineplus.infrastructure.virtual.VirtualModel model, PlacementData placement) {
        UUID instanceId = dev.spawnVirtualModel(model, placement);
        if (instanceId == null) {
            throw new IllegalStateException("spawn refused: check ModelImportCoordinator logs");
        }
        return instanceId;
    }

    /** Which model is the player standing on? Null means vanilla ground. */
    public String modelNameUnder(Location location) {
        return dev.virtualModelAt(location);
    }

    /** Ray test across world blocks, virtual-model cells and custom mobs with one API. */
    public boolean isCustomMobUnderCrosshair(LivingEntity hit) {
        return hit != null && dev.isCustomMob(hit);
    }

    /** Spawns a registered custom mob and plays its attack clip if one is authored. */
    public void attackDemo(Location spawnPoint, String definitionId) {
        LivingEntity mob = dev.spawnCustomMob(spawnPoint, definitionId);
        if (mob != null) {
            dev.playMobAttack(mob);
        }
    }

    /** Watch render budgets instead of guessing: the Rendering 2.0 census. */
    public String renderBudgetLine() {
        RenderSnapshot snap = dev.renderSnapshot();
        return "rigs=" + snap.riggedMobs()
                + " virtualBlocks=" + snap.virtualBlockInstances()
                + " displays=" + snap.displayEntities()
                + " worlds=" + snap.loadedWorlds();
    }

    /**
     * Multiblock 2.0: start a machine process and advance it from a repeating
     * task; {@code advanceProcesses} returns the ids whose state changed, so
     * displays update event-driven instead of polling every machine.
     */
    public boolean startAndTick(UUID instanceId, String recipeId, int intervalTicks) {
        if (!dev.multiblock().startProcess(instanceId, recipeId)) {
            return false;
        }
        MachineProcess process = dev.multiblock().processOf(instanceId);
        return process != null && !dev.multiblock()
                .advanceProcesses(intervalTicks)
                .isEmpty();
    }
}
