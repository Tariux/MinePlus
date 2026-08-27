package com.mineplus.infrastructure.core.multiblock.render;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.MultiBlockLevel;
import com.mineplus.infrastructure.core.multiblock.MultiBlockType;
import com.mineplus.infrastructure.virtual.BbModelImporter;
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

    public UUID render(MultiBlockType type, MultiBlockInstance instance, File pluginDataFolder) {
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
            virtualBlockManager.registerModel(modelKey, model);
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
        return virtualBlockManager.spawnModel(model, placementData);
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
