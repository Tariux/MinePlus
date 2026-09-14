package com.mineplus.infrastructure.core.multiblock.render;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.MultiBlockLevel;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import com.mineplus.infrastructure.render.RenderBackend;
import com.mineplus.infrastructure.render.RenderKind;
import com.mineplus.infrastructure.render.RenderPlan;
import com.mineplus.infrastructure.render.RenderRouter;
import com.mineplus.infrastructure.virtual.BbModelImporter;
import com.mineplus.infrastructure.virtual.ModelMeta;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import com.mineplus.infrastructure.virtual.VirtualBlockPlacementHelper;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.pack.render.PackBlockRenderer;
import com.mineplus.pack.render.PackModelRenderer;
import com.mineplus.util.DebugLogger;
import java.io.File;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.joml.Quaternionf;

/**
 * Rendering choke point for multiblock instances — the single place a render
 * decision becomes a backend call. The virtual engine renders levels with
 * backend {@code VIRTUAL} (the default, and the automatic fallback whenever
 * the pack subsystem is unavailable or a model has no pack item); the pack
 * renderer renders {@code PACK} levels (collision through the virtual
 * manager's collision-only spawn, visuals through a pack item display);
 * {@code VIRTUAL_PLUS_PACK} renders the virtual model and layers the pack
 * display on top.
 */
public final class ModelRenderingManager {

    private final VirtualBlockManager virtualBlockManager;
    private final RenderRouter renderRouter = new RenderRouter();
    private volatile PackModelRenderer packRenderer;
    private volatile PackBlockRenderer packBlockRenderer;

    public ModelRenderingManager(VirtualBlockManager virtualBlockManager) {
        this.virtualBlockManager = virtualBlockManager;
    }

    /** The single routing choke point: plan + availability + policy -> effective backend/kind. */
    public RenderRouter renderRouter() {
        return renderRouter;
    }

    /** Injected by the pack subsystem on start; null when the subsystem is disabled. */
    public void setPackRenderer(PackModelRenderer packRenderer) {
        this.packRenderer = packRenderer;
    }

    /** Injected alongside {@link #setPackRenderer}: the pack block axis. */
    public void setPackBlockRenderer(PackBlockRenderer packBlockRenderer) {
        this.packBlockRenderer = packBlockRenderer;
    }

    /** True when the pack renderer is available for backend selection. */
    public boolean packRendererActive() {
        PackModelRenderer renderer = packRenderer;
        return renderer != null;
    }

    /**
     * Runs after model definitions reload (both the command path and module
     * reloads route through here) so pack assets whose models just loaded can
     * attach and the pack recompiles.
     */
    public void onModelsReloaded() {
        PackModelRenderer renderer = packRenderer;
        if (renderer != null) {
            renderer.onModelsReloaded();
        }
    }

    /** Model + placement + level resolved the same way {@link #render} resolves them. */
    private record Resolved(VirtualModel model, VirtualBlockPlacementHelper.PlacementData placement, MultiBlockLevel level) {
    }

    public UUID render(MultiBlockType type, MultiBlockInstance instance, File pluginDataFolder) {
        Resolved resolved = resolve(type, instance, pluginDataFolder);
        if (resolved == null) {
            return null;
        }
        return renderResolved(resolved);
    }

    private UUID renderResolved(Resolved resolved) {
        RenderPlan plan = resolved.level().renderPlan("level");
        RenderRouter.ResolvedRoute route = renderRouter.route(plan, packRendererActive());
        if (!route.available()) {
            DebugLogger.warning("render: no backend available for mode " + plan.mode()
                    + " (pack unavailable and degradation disabled).");
            return null;
        }
        switch (route.backend()) {
            case PACK -> {
                UUID id = resolved.level().renderKind() == RenderKind.BLOCK
                        ? renderPackBlock(resolved)
                        : renderPack(resolved);
                if (id != null) {
                    return id;
                }
                if (!degradeAllowed()) {
                    DebugLogger.warning("render: pack backend unavailable for " + plan.mode()
                            + " and degradation is disabled; leaving instance unrendered.");
                    return null;
                }
                // Pack render unavailable (model without a pack item/block,
                // spawn failure): the virtual engine is the declared fallback.
                return virtualBlockManager.spawnModel(resolved.model(), resolved.placement());
            }
            case VIRTUAL_PLUS_PACK -> {
                UUID id = virtualBlockManager.spawnModel(resolved.model(), resolved.placement());
                if (id != null) {
                    attachPackVisual(id, resolved);
                }
                return id;
            }
            default -> {
                return virtualBlockManager.spawnModel(resolved.model(), resolved.placement());
            }
        }
    }

    /** Whether the active policy permits falling back to the virtual engine. */
    private boolean degradeAllowed() {
        return renderRouter.policy().allowDegradeToVirtual();
    }

    /**
     * Pack-backend item render: feasibility first (a registered pack item must
     * render the model), then the shared collision lattice, then the pack
     * display keyed by the collision instance id.
     */
    private UUID renderPack(Resolved resolved) {
        PackModelRenderer renderer = packRenderer;
        if (renderer == null || !renderer.hasItemForModel(resolved.model().name())) {
            return null;
        }
        UUID instanceId = virtualBlockManager.spawnCollisionModel(resolved.model(), resolved.placement());
        if (instanceId == null) {
            return null;
        }
        if (!attachPackVisual(instanceId, resolved)) {
            // Never leave a collision-only ghost: release it so the caller
            // falls back to a full virtual render.
            virtualBlockManager.removeModel(instanceId);
            return null;
        }
        return instanceId;
    }

    /**
     * Pack-backend block render: feasibility first (a registered pack block
     * must render the model), then the shared collision lattice, then one
     * {@code BlockDisplay} carrying the allocated carrier state.
     */
    private UUID renderPackBlock(Resolved resolved) {
        PackBlockRenderer renderer = packBlockRenderer;
        if (renderer == null || !renderer.hasBlockForModel(resolved.model().name())) {
            return null;
        }
        UUID instanceId = virtualBlockManager.spawnCollisionModel(resolved.model(), resolved.placement());
        if (instanceId == null) {
            return null;
        }
        if (!attachPackVisual(instanceId, resolved)) {
            // Never leave a collision-only ghost: release it so the caller
            // falls back to a full virtual render.
            virtualBlockManager.removeModel(instanceId);
            return null;
        }
        return instanceId;
    }

    /** Attaches the pack visual the level's kind selects; true when a display spawned. */
    private boolean attachPackVisual(UUID instanceId, Resolved resolved) {
        if (resolved.level().renderKind() == RenderKind.BLOCK) {
            PackBlockRenderer renderer = packBlockRenderer;
            if (renderer == null) {
                return false;
            }
            if (renderer.attach(instanceId, resolved.model(), resolved.placement())) {
                return true;
            }
            renderRouter.recordAttachFailure();
            DebugLogger.warning("render: pack block display attach failed for instance " + instanceId
                    + "; falling back to virtual rendering.");
            return false;
        }
        PackModelRenderer renderer = packRenderer;
        if (renderer == null) {
            return false;
        }
        if (renderer.attach(instanceId, resolved.model(), resolved.placement())) {
            return true;
        }
        renderRouter.recordAttachFailure();
        DebugLogger.warning("render: pack display attach failed for instance " + instanceId
                + "; falling back to virtual rendering.");
        return false;
    }

    /**
     * Inspects (and optionally clears) the blocks occupying the prospective spawn area
     * of an instance's model — the exact cells {@link #render} would fill.
     *
     * @param clear true to remove non-air occupants (creative/admin policy); false to
     *              only report whether the area is free (standard-player policy)
     * @return the inspection result, or {@code null} when the model (or its world/level)
     *         could not be resolved — a load failure, not an occupancy verdict
     */
    public VirtualBlockManager.SpawnAreaResult prepareArea(
            MultiBlockType type, MultiBlockInstance instance, File pluginDataFolder, boolean clear) {
        Resolved resolved = resolve(type, instance, pluginDataFolder);
        if (resolved == null) {
            return null;
        }
        return virtualBlockManager.prepareSpawnArea(resolved.model(), resolved.placement(), clear);
    }

    public void remove(MultiBlockInstance instance) {
        if (instance.renderedModelId() != null) {
            removeById(instance.renderedModelId());
        }
    }

    /** Removes a rendered instance from whichever backend owns its parts. */
    public void removeById(UUID renderedModelId) {
        if (renderedModelId == null) {
            return;
        }
        PackModelRenderer renderer = packRenderer;
        if (renderer != null) {
            renderer.remove(renderedModelId);
        }
        PackBlockRenderer blockRenderer = packBlockRenderer;
        if (blockRenderer != null) {
            blockRenderer.remove(renderedModelId);
        }
        virtualBlockManager.removeModel(renderedModelId);
    }

    public UUID swapModel(MultiBlockType type, MultiBlockInstance instance, File pluginDataFolder) {
        remove(instance);
        UUID newModelId = render(type, instance, pluginDataFolder);
        return newModelId;
    }

    /**
     * Backend-aware restore: dedupe and world guards live in the virtual
     * manager; PACK levels restore only the collision lattice here and attach
     * the pack display to the returned id. Returns null when restore is not
     * possible (the caller falls back to {@link #render}).
     */
    public UUID restore(MultiBlockType type, MultiBlockInstance instance, File pluginDataFolder) {
        Resolved resolved = resolve(type, instance, pluginDataFolder);
        if (resolved == null) {
            return null;
        }
        RenderRouter.ResolvedRoute route = renderRouter.route(resolved.level().renderPlan("level"), packRendererActive());
        if (!route.available()) {
            return null;
        }
        if (route.backend() == RenderBackend.PACK) {
            UUID id = virtualBlockManager.restoreCollisionForState(
                    instance.coordinate(), instance.modelKey(), instance.rotation());
            if (id != null) {
                if (attachPackVisual(id, resolved)) {
                    return id;
                }
                // Pack visual unavailable: release the collision-only restore
                // so the caller falls back to a full virtual render.
                virtualBlockManager.removeModel(id);
            }
            return null;
        }
        return virtualBlockManager.restoreForState(
                instance.coordinate(), instance.modelKey(), instance.rotation());
    }

    /** Session-start ghost sweep across every backend's display entities. */
    public int sweepGhostDisplays() {
        int swept = virtualBlockManager.sweepGhostDisplays();
        PackModelRenderer renderer = packRenderer;
        if (renderer != null) {
            swept += renderer.sweepGhosts();
        }
        PackBlockRenderer blockRenderer = packBlockRenderer;
        if (blockRenderer != null) {
            swept += blockRenderer.sweepGhosts();
        }
        return swept;
    }

    public VirtualBlockManager virtualBlockManager() {
        return virtualBlockManager;
    }

    private Resolved resolve(MultiBlockType type, MultiBlockInstance instance, File pluginDataFolder) {
        World world = Bukkit.getWorld(instance.coordinate().worldName());
        if (world == null) {
            DebugLogger.warning("render: World not loaded for instance " + instance.id() + " at " + instance.coordinate().worldName());
            return null;
        }

        MultiBlockLevel level = type.level(instance.level());
        if (level == null || level.modelPath().isBlank()) {
            DebugLogger.warning("render: No level " + instance.level() + " for type '" + type.id() + "'.");
            return null;
        }

        String modelKey = buildModelKey(type.id(), instance.level());
        instance.setModelKey(modelKey);
        File modelFile = resolveModelFile(pluginDataFolder, level.modelPath());
        VirtualModel model = virtualBlockManager.getModel(modelKey);
        if (model == null) {
            DebugLogger.info("render: Model key '" + modelKey + "' not preloaded — parsing from file " + modelFile.getAbsolutePath());
            model = BbModelImporter.parse(modelKey, modelFile);
            if (model == null || model.cubes().isEmpty()) {
                DebugLogger.severe("render: Failed to load or parse model file " + modelFile.getAbsolutePath() + " for key '" + modelKey + "'.");
                return null;
            }
            // The resolved file must travel with the registration: texel baking
            // resolves texture PNGs relative to the model file's folder.
            virtualBlockManager.registerModel(modelKey, model, ModelMeta.load(modelFile), modelFile);
        }

        Quaternionf rotation = instance.rotation();
        Location origin = new Location(
                world,
                instance.coordinate().x(),
                instance.coordinate().y(),
                instance.coordinate().z()
        );
        VirtualBlockPlacementHelper.PlacementData placementData =
                new VirtualBlockPlacementHelper.PlacementData(origin, BlockFace.UP, rotation);
        return new Resolved(model, placementData, level);
    }

    private File resolveModelFile(File pluginDataFolder, String modelPath) {
        File candidate = new File(modelPath);
        if (candidate.isAbsolute()) {
            return candidate;
        }
        return new File(pluginDataFolder, modelPath);
    }

    private String buildModelKey(String typeId, int level) {
        return (typeId + "_lvl_" + level).toLowerCase(Locale.ROOT);
    }
}
