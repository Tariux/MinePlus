package com.mineplus.infrastructure;

import com.mineplus.infrastructure.mob.custom.CustomMobManager;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.infrastructure.virtual.VirtualBlockPlacementHelper;
import com.mineplus.infrastructure.world.HitTestApi;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;

import java.util.List;
import java.util.UUID;

/**
 * Developer API 2.0: the module-facing surface for the vanilla-trick primitives.
 * Everything here works on a plain Spigot server — no NMS — because each system
 * is built on vanilla mechanics (hidden blocks, display entities, cooldowns)
 * that the Core owns end to end.
 *
 * <p>Modules reach it via {@code MineplusPlugin#context()#dev()}.
 */
public final class DeveloperApi2 {

    private final PluginContext context;

    public DeveloperApi2(PluginContext context) {
        this.context = context;
    }

    /** Unified ray testing: world blocks, virtual-model cells, custom mobs. */
    public HitTestApi hitTest() {
        return context.hitTest();
    }

    /**
     * Spawns a virtual model at a location. Collision cells come from the model's
     * {@code collisionMaterial} meta (barrier by default; slime, honey, cobweb,
     * powder snow, soul sand and bubble column give vanilla physics for free).
     * Returns the instance id, or {@code null} when the spawn was refused.
     */
    public UUID spawnVirtualModel(VirtualModel model, VirtualBlockPlacementHelper.PlacementData placement) {
        return context.virtualBlockManager().spawnModel(model, placement);
    }

    /** Removes a virtual-model instance and all of its world blocks. */
    public void removeVirtualModel(UUID instanceId) {
        context.virtualBlockManager().removeModel(instanceId);
    }

    /** The model instance id at a world position, or {@code null} for vanilla ground. */
    public String virtualModelAt(Location location) {
        VirtualBlockManager.ActiveVirtualBlock active =
                context.virtualBlockManager().getVirtualBlockAt(location);
        return active == null ? null : active.modelName();
    }

    /** Lists the models currently placed in a world. */
    public List<VirtualBlockManager.ActiveVirtualBlock> activeVirtualModels() {
        return List.copyOf(context.virtualBlockManager().activeBlocksView().values());
    }

    /** Spawns a registered custom mob; returns the living entity or {@code null}. */
    public LivingEntity spawnCustomMob(Location location, String definitionId) {
        return context.customMobs().spawn(location, definitionId);
    }

    /** {@code true} when the entity is a Core custom mob. */
    public boolean isCustomMob(org.bukkit.entity.Entity entity) {
        return context.customMobs().isCustomMob(entity);
    }

    /** Plays the mob's {@code attack} clip; {@code false} when none is authored. */
    public boolean playMobAttack(LivingEntity entity) {
        return context.customMobs().playAttack(entity);
    }

    /** Plays the mob's {@code hurt} clip; {@code false} when none is authored. */
    public boolean playMobHurt(LivingEntity entity) {
        return context.customMobs().playHurt(entity);
    }

    /** The custom-mob manager, for behaviors and profiles beyond this facade. */
    public CustomMobManager customMobs() {
        return context.customMobManager();
    }

    /**
     * Placement with options: finer yaw lattices (45/22.5), a face-normal offset,
     * and an air-target check for solid furniture. {@code null} options means
     * {@link com.mineplus.infrastructure.virtual.PlacementOptions#DEFAULT}.
     */
    public VirtualBlockPlacementHelper.PlacementData placementFor(
            org.bukkit.entity.Player player, double maxDistance,
            com.mineplus.infrastructure.virtual.PlacementOptions options) {
        return VirtualBlockPlacementHelper.getPlacementData(player, maxDistance, options);
    }

    /** Rendering 2.0 census: rigs, virtual blocks, live display entities, worlds. */
    public com.mineplus.infrastructure.render.RenderDiagnostics.RenderSnapshot renderSnapshot() {
        return com.mineplus.infrastructure.render.RenderDiagnostics.snapshot(context);
    }

    /** Multiblock 2.0 facade: machines, processes and progress for module authors. */
    public com.mineplus.infrastructure.core.multiblock.MultiblockApi multiblock() {
        return new com.mineplus.infrastructure.core.multiblock.MultiblockApi(
                context.infrastructureEngine().registry(),
                context.infrastructureEngine().processManager());
    }
}
