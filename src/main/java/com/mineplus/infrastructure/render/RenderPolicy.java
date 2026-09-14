package com.mineplus.infrastructure.render;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Cross-cutting routing policy for the unified render engine (the
 * {@code RENDERING.POLICY} section of {@code settings.mp.yml}). Where a
 * {@link RenderPlan} says <em>what</em> a level wants, the policy says how the
 * router behaves when that request cannot be honored.
 *
 * <p>Defaults preserve the pre-policy behavior exactly: pack requests degrade
 * to the virtual engine when the pack subsystem is unavailable or a pack
 * display fails to attach, routing is only logged under
 * {@code ADDITIONAL_DEBUG_LOGS}, and telemetry counters are always tracked
 * (they are cheap and feed {@code /mineplus render stats}).</p>
 *
 * @param allowDegradeToVirtual when a pack/hybrid request cannot be served
 *                              (subsystem unavailable, display attach failure),
 *                              fall back to the virtual engine. When false, the
 *                              content is left unrendered instead.
 * @param logRouting            log each routing decision across backend/kind
 *                              switches through {@link com.mineplus.util.DebugLogger}
 *                              (gated by {@code ADDITIONAL_DEBUG_LOGS}).
 * @param trackTelemetry        maintain the route counters exposed by
 *                              {@link RenderRouter#snapshot()}.
 */
public record RenderPolicy(
        boolean allowDegradeToVirtual,
        boolean logRouting,
        boolean trackTelemetry
) {

    public static RenderPolicy defaults() {
        return new RenderPolicy(true, false, true);
    }

    public static RenderPolicy parse(FileConfiguration yaml, RenderPolicy fallback) {
        if (yaml == null || !yaml.isConfigurationSection("RENDERING")) {
            return fallback;
        }
        ConfigurationSection rendering = yaml.getConfigurationSection("RENDERING");
        if (rendering == null) {
            return fallback;
        }
        ConfigurationSection policy = rendering.getConfigurationSection("POLICY");
        if (policy == null) {
            return fallback;
        }
        RenderPolicy defaults = defaults();
        return new RenderPolicy(
                policy.getBoolean("ALLOW_DEGRADE_TO_VIRTUAL", defaults.allowDegradeToVirtual()),
                policy.getBoolean("LOG_ROUTING", defaults.logRouting()),
                policy.getBoolean("TRACK_TELEMETRY", defaults.trackTelemetry())
        );
    }
}
