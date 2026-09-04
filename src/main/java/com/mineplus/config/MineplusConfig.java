package com.mineplus.config;

import com.mineplus.infrastructure.virtual.ModelMeta;
import com.mineplus.infrastructure.virtual.VirtualRenderingSettings;
import com.mineplus.infrastructure.virtual.animation.AnimationSettings;
import com.mineplus.infrastructure.virtual.display.DisplayTransportSettings;
import com.mineplus.infrastructure.virtual.texel.TexelBakingSettings;
import org.bukkit.configuration.file.FileConfiguration;

public class MineplusConfig {

    private final boolean additionalDebugLogs;
    private final VirtualRenderingSettings virtualRendering;
    private final AnimationSettings animation;
    private final TexelBakingSettings texelBaking;
    private final DisplayTransportSettings displayTransport;
    private final int updateCheckResourceId;

    public MineplusConfig() {
        this(false);
    }

    public MineplusConfig(boolean additionalDebugLogs) {
        this(additionalDebugLogs, VirtualRenderingSettings.defaults(),
                AnimationSettings.defaults(), TexelBakingSettings.defaults(),
                DisplayTransportSettings.defaults(), 0);
    }

    public MineplusConfig(boolean additionalDebugLogs, VirtualRenderingSettings virtualRendering) {
        this(additionalDebugLogs, virtualRendering,
                AnimationSettings.defaults(), TexelBakingSettings.defaults(),
                0);
    }

    public MineplusConfig(
            boolean additionalDebugLogs,
            VirtualRenderingSettings virtualRendering,
            int updateCheckResourceId
    ) {
        this(additionalDebugLogs, virtualRendering,
                AnimationSettings.defaults(), TexelBakingSettings.defaults(),
                DisplayTransportSettings.defaults(), updateCheckResourceId);
    }

    public MineplusConfig(
            boolean additionalDebugLogs,
            VirtualRenderingSettings virtualRendering,
            AnimationSettings animation,
            int updateCheckResourceId
    ) {
        this(additionalDebugLogs, virtualRendering, animation,
                TexelBakingSettings.defaults(), DisplayTransportSettings.defaults(),
                updateCheckResourceId);
    }

    public MineplusConfig(
            boolean additionalDebugLogs,
            VirtualRenderingSettings virtualRendering,
            AnimationSettings animation,
            TexelBakingSettings texelBaking,
            int updateCheckResourceId
    ) {
        this(additionalDebugLogs, virtualRendering, animation, texelBaking,
                DisplayTransportSettings.defaults(), updateCheckResourceId);
    }

    public MineplusConfig(
            boolean additionalDebugLogs,
            VirtualRenderingSettings virtualRendering,
            AnimationSettings animation,
            TexelBakingSettings texelBaking,
            DisplayTransportSettings displayTransport,
            int updateCheckResourceId
    ) {
        this.additionalDebugLogs = additionalDebugLogs;
        this.virtualRendering = virtualRendering == null
                ? VirtualRenderingSettings.defaults()
                : virtualRendering;
        this.animation = animation == null ? AnimationSettings.defaults() : animation;
        this.texelBaking = texelBaking == null ? TexelBakingSettings.defaults() : texelBaking;
        this.displayTransport = displayTransport == null
                ? DisplayTransportSettings.defaults() : displayTransport;
        this.updateCheckResourceId = Math.max(0, updateCheckResourceId);
    }

    public boolean isAdditionalDebugLogs() {
        return additionalDebugLogs;
    }

    public VirtualRenderingSettings getVirtualRendering() {
        return virtualRendering;
    }

    public AnimationSettings getAnimation() {
        return animation;
    }

    public TexelBakingSettings getTexelBaking() {
        return texelBaking;
    }

    public DisplayTransportSettings getDisplayTransport() {
        return displayTransport;
    }

    public int getUpdateCheckResourceId() {
        return updateCheckResourceId;
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

    public static AnimationSettings parseAnimation(FileConfiguration yaml, AnimationSettings fallback) {
        if (!yaml.isConfigurationSection("ANIMATION")) {
            return fallback;
        }
        var section = yaml.getConfigurationSection("ANIMATION");
        AnimationSettings defaults = AnimationSettings.defaults();
        return new AnimationSettings(
                section.getBoolean("ENABLED", defaults.enabled()),
                section.getInt("TICK_INTERVAL_TICKS", defaults.tickIntervalTicks()),
                section.getInt("INTERPOLATION_TICKS", defaults.interpolationTicks()),
                section.getBoolean("AUTOPLAY", defaults.autoplay())
        );
    }

    public static TexelBakingSettings parseTexelBaking(FileConfiguration yaml, TexelBakingSettings fallback) {
        return TexelBakingSettings.parse(yaml, fallback);
    }

    public static DisplayTransportSettings parseDisplayTransport(
            FileConfiguration yaml, DisplayTransportSettings fallback) {
        return DisplayTransportSettings.parse(yaml, fallback);
    }
}
