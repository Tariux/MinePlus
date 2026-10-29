package com.mineplus.infrastructure.mob;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Runtime tuning for the mob behavior engine ({@code MOB_BEHAVIOR} section of
 * settings.mp.yml). All values bound the engine's cost on the server tick:
 * the run interval and stagger spread evaluations over time, the scan radius
 * gates how far from players behavior processing reaches, the per-run budget
 * caps processing time, and the edit cap rate-limits world changes.
 *
 * @param enabled             master switch; {@code false} freezes the engine
 *                            (registered profiles stay intact)
 * @param intervalTicks       server ticks between engine runs
 * @param staggerBuckets      entity buckets: each entity is evaluated every
 *                            {@code staggerBuckets}-th run, spreading load
 * @param scanRadiusChunks    chunk radius around each online player that is
 *                            considered active (chunk-center scan area)
 * @param budgetMillisPerRun  soft processing budget per run in milliseconds;
 *                            the engine defers remaining entities to the next
 *                            run once exceeded
 * @param maxBlockEditsPerRun hard ceiling of world block changes per run
 * @param maxEntitySpawnsPerRun hard ceiling of entities spawned per run — mob
 *                            actions summon minions and golems, and an
 *                            unbounded spawn is the one way this subsystem can
 *                            flood the tick
 * @param maxSustainedPerRun    cap on activity-claimed entities the sustained
 *                              sweep advances per run (journeys and camps tick
 *                              even when no player is near them)
 * @param maxPipelinesPerRun    cap on multi-stage pipeline scenarios advanced
 *                              per run, so a village of raiders cannot spend the
 *                              whole tick on their own pathing
 */
public record MobBehaviorSettings(
        boolean enabled,
        int intervalTicks,
        int staggerBuckets,
        int scanRadiusChunks,
        long budgetMillisPerRun,
        int maxBlockEditsPerRun,
        int maxEntitySpawnsPerRun,
        int maxSustainedPerRun,
        int maxPipelinesPerRun
) {

    public static MobBehaviorSettings defaults() {
        return new MobBehaviorSettings(true, 3, 4, 2, 5L, 96, 8, 64, 64);
    }

    /**
     * Entity evaluation opportunities per second. Two things thin the raw run
     * rate: only half the chunks are swept per run (alternating parity), and
     * only one stagger bucket per run is eligible — so an entity is seen once
     * every {@code 2 * intervalTicks * staggerBuckets} ticks. The odds gate has
     * to divide by the same number or every declared interval would silently be
     * twice as long as configured.
     */
    public double checksPerSecond() {
        int interval = Math.max(1, intervalTicks);
        int buckets = Math.max(1, staggerBuckets);
        return 20.0 / (2.0 * interval * buckets);
    }

    public static MobBehaviorSettings parse(FileConfiguration yaml, MobBehaviorSettings fallback) {
        if (yaml == null || !yaml.isConfigurationSection("MOB_BEHAVIOR")) {
            return fallback;
        }
        var section = yaml.getConfigurationSection("MOB_BEHAVIOR");
        MobBehaviorSettings defaults = defaults();
        return new MobBehaviorSettings(
                section.getBoolean("ENABLED", defaults.enabled()),
                clamp(section.getInt("INTERVAL_TICKS", defaults.intervalTicks()), 1, 40),
                clamp(section.getInt("STAGGER_BUCKETS", defaults.staggerBuckets()), 1, 40),
                clamp(section.getInt("SCAN_RADIUS_CHUNKS", defaults.scanRadiusChunks()), 1, 8),
                Math.max(0L, section.getLong("BUDGET_MILLIS_PER_RUN", defaults.budgetMillisPerRun())),
                clamp(section.getInt("MAX_BLOCK_EDITS_PER_RUN", defaults.maxBlockEditsPerRun()), 0, 4096),
                clamp(section.getInt("MAX_ENTITY_SPAWNS_PER_RUN", defaults.maxEntitySpawnsPerRun()), 0, 256),
                clamp(section.getInt("MAX_SUSTAINED_PER_RUN", defaults.maxSustainedPerRun()), 0, 512),
                clamp(section.getInt("MAX_PIPELINES_PER_RUN", defaults.maxPipelinesPerRun()), 0, 512)
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
