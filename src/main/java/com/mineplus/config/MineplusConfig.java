package com.mineplus.config;

import com.mineplus.infrastructure.virtual.ModelMeta;
import com.mineplus.infrastructure.virtual.VirtualRenderingSettings;
import com.mineplus.infrastructure.virtual.VoxelOccupancyCalculator;
import org.bukkit.configuration.file.FileConfiguration;

public class MineplusConfig {

    private final boolean additionalDebugLogs;
    private final VirtualRenderingSettings virtualRendering;

    public MineplusConfig() {
        this(false);
    }

    public MineplusConfig(boolean additionalDebugLogs) {
        this.additionalDebugLogs = additionalDebugLogs;
        this.virtualRendering = VirtualRenderingSettings.defaults();
    }

    public MineplusConfig(boolean additionalDebugLogs, VirtualRenderingSettings virtualRendering) {
        this.additionalDebugLogs = additionalDebugLogs;
        this.virtualRendering = virtualRendering == null
                ? VirtualRenderingSettings.defaults()
                : virtualRendering;
    }

    public boolean isAdditionalDebugLogs() {
        return additionalDebugLogs;
    }

    public VirtualRenderingSettings getVirtualRendering() {
        return virtualRendering;
    }

    public static VirtualRenderingSettings parseVirtualRendering(
            FileConfiguration yaml, VirtualRenderingSettings fallback) {
        if (!yaml.isConfigurationSection("VIRTUAL_RENDERING")) {
            return fallback;
        }
        var section = yaml.getConfigurationSection("VIRTUAL_RENDERING");
        VirtualRenderingSettings defaults = VirtualRenderingSettings.defaults();
        return new VirtualRenderingSettings(
                ModelMeta.CollisionMode.fromKey(section.getString("COLLISION_MODE"), defaults.collisionMode()),
                (float) section.getDouble("COLLISION_EPSILON", defaults.collisionEpsilon()),
                VirtualRenderingSettings.NonAirPolicy.fromKey(
                        section.getString("COLLISION_NON_AIR_POLICY"), defaults.collisionNonAirPolicy()),
                section.getBoolean("ROTATION_SNAP", defaults.rotationSnap()),
                (float) section.getDouble("ROTATION_SNAP_THRESHOLD_DEGREES",
                        defaults.rotationSnapThresholdDegrees()),
                section.getBoolean("PER_FACE_RENDERING", defaults.perFaceRendering()),
                ModelMeta.OriginMode.fromKey(section.getString("ORIGIN_MODE"), defaults.originMode())
        );
    }
}
