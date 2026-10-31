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
        int bundleLimit,              // client rejects bundles > 4096 packets
        int bundleWeightLimit,        // payload ceiling per bundle after packet weighting
        int flushBudgetMs,            // per-tick send budget; 0 = flush everything
        boolean viewerStateEnabled,   // honor per-observer render state (forced visibility)
        boolean stancesEnabled,       // observer-only equipment stance channel
        boolean stanceResendOnInventoryClose
) {

    /** Keeps the historical six-argument shape working (weighting and budget at defaults). */
    public DisplayTransportSettings(boolean enabled, double lodFullRange, double lodStaticRange,
                                    int lodCheckIntervalTicks, int maxIdlePooledPerWorld, int bundleLimit) {
        this(enabled, lodFullRange, lodStaticRange, lodCheckIntervalTicks,
                maxIdlePooledPerWorld, bundleLimit, 4096, 2, true, false, true);
    }

    /** Keeps the eight-component shape working (viewer state at defaults). */
    public DisplayTransportSettings(boolean enabled, double lodFullRange, double lodStaticRange,
                                    int lodCheckIntervalTicks, int maxIdlePooledPerWorld, int bundleLimit,
                                    int bundleWeightLimit, int flushBudgetMs) {
        this(enabled, lodFullRange, lodStaticRange, lodCheckIntervalTicks, maxIdlePooledPerWorld,
                bundleLimit, bundleWeightLimit, flushBudgetMs, true, false, true);
    }

    public static DisplayTransportSettings defaults() {
        return new DisplayTransportSettings(true, 20.0, 48.0, 5, 512, 4000, 4096, 2, true, false, true);
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
                Math.min(4096, Math.max(2, section.getInt("NETWORK.BUNDLE_LIMIT", d.bundleLimit()))),
                Math.min(65536, Math.max(1, section.getInt("NETWORK.BUNDLE_WEIGHT_LIMIT", d.bundleWeightLimit()))),
                Math.min(50, Math.max(0, section.getInt("NETWORK.FLUSH_BUDGET_MS", d.flushBudgetMs()))),
                section.getBoolean("VIEWER_STATE.ENABLED", d.viewerStateEnabled()),
                section.getBoolean("STANCES.ENABLED", d.stancesEnabled()),
                section.getBoolean("STANCES.RESEND_ON_INVENTORY_CLOSE", d.stanceResendOnInventoryClose()));
    }

    public double lodFullRangeSq()   { return lodFullRange * lodFullRange; }
    public double lodStaticRangeSq() { return lodStaticRange * lodStaticRange; }
}
