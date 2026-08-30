package com.mineplus.infrastructure.core.multiblock.render;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.MultiBlockLevel;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import com.mineplus.infrastructure.virtual.BbModelImporter;
import com.mineplus.infrastructure.virtual.ModelMeta;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import com.mineplus.infrastructure.virtual.VirtualBlockPlacementHelper;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.util.DebugLogger;
import java.io.File;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.joml.Quaternionf;

public final class ModelRenderingManager {

    private final VirtualBlockManager virtualBlockManager;

    public ModelRenderingManager(VirtualBlockManager virtualBlockManager) {
        this.virtualBlockManager = virtualBlockManager;
    }

    /** Model + placement resolved the same way {@link #render} resolves them. */
    private record Resolved(VirtualModel model, VirtualBlockPlacementHelper.PlacementData placement) {
    }

    public UUID render(MultiBlockType type, MultiBlockInstance instance, File pluginDataFolder) {
        Resolved resolved = resolve(type, instance, pluginDataFolder);
        if (resolved == null) {
            return null;
        }
        return virtualBlockManager.spawnModel(resolved.model(), resolved.placement());
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
            virtualBlockManager.removeModel(instance.renderedModelId());
        }
    }

    public UUID swapModel(MultiBlockType type, MultiBlockInstance instance, File pluginDataFolder) {
        remove(instance);
        UUID newModelId = render(type, instance, pluginDataFolder);
        return newModelId;
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
            virtualBlockManager.registerModel(modelKey, model, ModelMeta.load(modelFile));
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
        return new Resolved(model, placementData);
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
