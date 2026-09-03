package com.mineplus.infrastructure.virtual.display;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Tuning for the packet-based display transport. Immutable so it can be read from
 * hot paths without synchronisation.
 */
public record DisplayTransportSettings(
        boolean enabled,
        double lodFullRange,          // <= this distance: full update stream
        double lodStaticRange,        // <= this distance: spawned but frozen; beyond: removed
        int lodCheckIntervalTicks,    // how often moved players get their LOD recomputed
        int maxIdlePooledPerWorld,    // idle displays kept per world before real despawn
        int bundleLimit               // client rejects bundles > 4096 packets
) {

    public static DisplayTransportSettings defaults() {
        return new DisplayTransportSettings(true, 20.0, 48.0, 5, 512, 4000);
    }

    public static DisplayTransportSettings parse(FileConfiguration yaml) {
        return parse(yaml, defaults());
    }

    public static DisplayTransportSettings parse(FileConfiguration yaml, DisplayTransportSettings fallback) {
        ConfigurationSection section = yaml == null ? null : yaml.getConfigurationSection("DISPLAY_TRANSPORT");
        if (section == null) {
            return fallback;
        }
        DisplayTransportSettings d = defaults();
        return new DisplayTransportSettings(
                section.getBoolean("ENABLED", fallback.enabled()),
                section.getDouble("LOD.FULL_RANGE", d.lodFullRange()),
                section.getDouble("LOD.STATIC_RANGE", d.lodStaticRange()),
                Math.max(1, section.getInt("LOD.CHECK_INTERVAL_TICKS", d.lodCheckIntervalTicks())),
                Math.max(0, section.getInt("POOL.MAX_IDLE_PER_WORLD", d.maxIdlePooledPerWorld())),
                Math.min(4096, Math.max(2, section.getInt("NETWORK.BUNDLE_LIMIT", d.bundleLimit()))));
    }

    public double lodFullRangeSq()   { return lodFullRange * lodFullRange; }
    public double lodStaticRangeSq() { return lodStaticRange * lodStaticRange; }
}
