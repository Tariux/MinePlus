package com.mineplus.infrastructure.mob;

import com.mineplus.util.DebugLogger;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * The mob behavior engine: a budgeted, distance-gated re-implementation of the
 * reference mod's per-tick AI event handling (per-entity tick event bolt-on)
 * under the Core's service discipline.
 *
 * <p>Execution model, per run (default every 3 ticks):
 * <ol>
 *   <li>Short-circuit when disabled or when no profiles are registered.</li>
 *   <li>For each online player, scan loaded chunks around them (activation
 *       radius) — entities far from any player never cost anything.</li>
 *   <li>Half the chunk set is scanned per run (alternating parity) so chunk
 *       iteration cost amortizes; a long-backed seen-set deduplicates entities
 *       shared between overlapping player radii.</li>
 *   <li>Entities are bucketed by id ({@code entityId % staggerBuckets}), so
 *       each entity is evaluated every {@code staggerBuckets}-th run — the
 *       generalized form of the reference mod's id/game-time parity throttle.</li>
 *   <li>Odds gate: action probability per evaluation is
 *       {@code 1 / (intervalSeconds * checksPerSecond)}, the same
 *       expected-fire semantics as the reference mod's
 *       {@code 1 / (seconds * CHECKS_PER_SECOND)}.</li>
 *   <li>Profile conditions gate evaluation; at most one action fires per
 *       entity event, dispatched in registration order.</li>
 *   <li>A nanotime budget defers remaining entities to the next run, and a
 *       run-scoped edit guard rate-limits world changes.</li>
 * </ol>
 *
 * <p>Threading: the run loop, registration, and unregistration are all
 * main-thread operations; profile maps are concurrent defensively. The engine
 * starts lazily on the first registered profile and stops on {@link #shutdown()}.
 */
public final class MobBehaviorEngine {

    private final Plugin plugin;
    private final Map<EntityType, List<MobProfile>> profilesByType = new ConcurrentHashMap<>();
    private final Map<Plugin, List<MobProfile>> profilesByOwner = new ConcurrentHashMap<>();
    private final BehaviorTelemetry telemetry = new BehaviorTelemetry();
    private final BehaviorContext evaluationContext = new BehaviorContext(this);

    private volatile MobBehaviorSettings settings = MobBehaviorSettings.defaults();
    private volatile BukkitTask runTask;
    private volatile boolean running;
    private int runCounter;

    /** Called by the Core's {@code PluginContext#bootstrap}; one engine per Core. */
    public MobBehaviorEngine(Plugin plugin) {
        this.plugin = plugin;
    }

    /** The active engine settings snapshot. */
    MobBehaviorSettings settings() {
        return settings;
    }

    BehaviorTelemetry telemetry() {
        return telemetry;
    }

    /** Registers a profile; starts the engine on the first registration. */
    void register(Plugin owner, MobProfile profile) {
        if (owner == null || profile == null) {
            throw new IllegalArgumentException("owner and profile are required");
        }
        profilesByOwner.computeIfAbsent(owner, key -> Collections.synchronizedList(new ArrayList<>())).add(profile);
        profilesByType.computeIfAbsent(profile.entityType(), key -> Collections.synchronizedList(new ArrayList<>())).add(profile);
        ensureRunning();
        if (DebugLogger.isEnabled()) {
            plugin.getLogger().info("[mob-behavior] " + owner.getName() + " registered profile for "
                    + profile.entityType() + " (" + profilesByType.size() + " types active)");
        }
    }

    /** Removes one specific profile from {@code owner}'s registrations. */
    void unregister(Plugin owner, MobProfile profile) {
        List<MobProfile> owned = owner == null || profile == null ? null : profilesByOwner.get(owner);
        if (owned == null || !owned.remove(profile)) {
            return;
        }
        List<MobProfile> list = profilesByType.get(profile.entityType());
        if (list != null) {
            list.remove(profile);
            if (list.isEmpty()) {
                profilesByType.remove(profile.entityType(), list);
            }
        }
    }

    /** Removes every profile registered by {@code owner}. */
    void unregisterAll(Plugin owner) {
        List<MobProfile> owned = owner == null ? null : profilesByOwner.remove(owner);
        if (owned == null || owned.isEmpty()) {
            return;
        }
        for (MobProfile profile : owned) {
            List<MobProfile> list = profilesByType.get(profile.entityType());
            if (list != null) {
                list.remove(profile);
                if (list.isEmpty()) {
                    profilesByType.remove(profile.entityType(), list);
                }
            }
        }
        if (DebugLogger.isEnabled()) {
            plugin.getLogger().info("[mob-behavior] " + owner.getName() + " unregistered "
                    + owned.size() + " profile(s)");
        }
    }

    /** {@code true} when {@code owner} currently holds at least one profile. */
    boolean hasProfiles(Plugin owner) {
        List<MobProfile> owned = owner == null ? null : profilesByOwner.get(owner);
        return owned != null && !owned.isEmpty();
    }

    /** Number of distinct entity types with at least one active profile. */
    int profileTypeCount() {
        return profilesByType.size();
    }

    /** Applies new settings (config reload). Safe while running. */
    void updateSettings(MobBehaviorSettings newSettings) {
        this.settings = newSettings == null ? MobBehaviorSettings.defaults() : newSettings;
    }

    /** Cancels the run task. Idempotent; called from the Core's disable path. */
    void shutdown() {
        running = false;
        BukkitTask task = runTask;
        runTask = null;
        if (task != null) {
            task.cancel();
            DebugLogger.info("[mob-behavior] Engine stopped.");
        }
    }

    private synchronized void ensureRunning() {
        if (running || !plugin.isEnabled()) {
            return;
        }
        running = true;
        int interval = Math.max(1, settings.intervalTicks());
        runTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::run, interval, interval);
        DebugLogger.info("[mob-behavior] Engine started (interval " + interval + " ticks).");
    }

    private void run() {
        MobBehaviorSettings snapshot = settings;
        if (!snapshot.enabled() || profilesByType.isEmpty()) {
            return;
        }
        telemetry.runDone();
        long deadline = System.nanoTime() + snapshot.budgetMillisPerRun() * 1_000_000L;
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            return;
        }
        int buckets = Math.max(1, snapshot.staggerBuckets());
        int runIndex = runCounter++;
        int chunkParity = runIndex & 1;
        LongOpenHashSet seen = new LongOpenHashSet(64);
        RunGuard guard = new RunGuard(snapshot.maxBlockEditsPerRun());

        outer:
        for (Player player : players) {
            var world = player.getWorld();
            int baseChunkX = player.getLocation().getBlockX() >> 4;
            int baseChunkZ = player.getLocation().getBlockZ() >> 4;
            int radius = snapshot.scanRadiusChunks();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int chunkX = baseChunkX + dx;
                    int chunkZ = baseChunkZ + dz;
                    // Alternate chunk parity per run: each chunk pays its
                    // getEntities() cost every other run, coverage stays total.
                    if (((chunkX + chunkZ) & 1) != chunkParity) {
                        continue;
                    }
                    if (!world.isChunkLoaded(chunkX, chunkZ)) {
                        continue;
                    }
                    Chunk chunk = world.getChunkAt(chunkX, chunkZ);
                    for (Entity entity : chunk.getEntities()) {
                        if (System.nanoTime() >= deadline) {
                            telemetry.budgetDeferred();
                            break outer;
                        }
                        if (!seen.add(entity.getEntityId())) {
                            continue;
                        }
                        if (!(entity instanceof LivingEntity living) || living.isDead() || !living.isValid()) {
                            continue;
                        }
                        EntityType type = living.getType();
                        List<MobProfile> candidates = profilesByType.get(type);
                        if (candidates == null || candidates.isEmpty()) {
                            continue;
                        }
                        if ((entity.getEntityId() % buckets) != (runIndex % buckets)) {
                            continue;
                        }
                        evaluate(living, type, candidates, ThreadLocalRandom.current(), guard, deadline);
                    }
                }
            }
        }
    }

    private void evaluate(LivingEntity entity, EntityType type, List<MobProfile> candidates,
                          ThreadLocalRandom random, RunGuard guard, long deadline) {
        telemetry.candidateSeen();
        double checksPerSecond = settings.checksPerSecond();
        for (MobProfile profile : candidates) {
            if (System.nanoTime() >= deadline) {
                telemetry.budgetDeferred();
                return;
            }
            evaluationContext.refresh(entity, random, guard);
            if (!conditionsPass(profile)) {
                continue;
            }
            for (MobProfile.ActionEntry entry : profile.actions()) {
                double seconds = Math.max(0.25, entry.intervalSeconds());
                double odds = Math.min(1.0, 1.0 / (seconds * checksPerSecond));
                if (random.nextDouble() > odds) {
                    continue;
                }
                try {
                    if (entry.action().execute(evaluationContext)) {
                        telemetry.eventFired(entry.action().id());
                    }
                } catch (Throwable throwable) {
                    // One broken action must never kill the run loop: isolate,
                    // log, and keep the engine alive.
                    plugin.getLogger().warning("[mob-behavior] action '" + entry.action().id()
                            + "' threw on " + type + ": " + throwable);
                }
                return;
            }
        }
    }

    /**
     * Forces one direct firing pass on a specific entity: every action
     * registered by {@code owner} for the entity's type runs immediately with
     * the odds gate bypassed, unlike the regular one-action-per-evaluation
     * loop. Profile conditions still gate the actions unless
     * {@code ignoreConditions} is set, so the report shows why an action
     * would not fire naturally. Works even when the loop is disabled by
     * settings; every action still runs through the edit budget. Main thread.
     */
    BehaviorFireResult forceFire(Plugin owner, LivingEntity entity, boolean ignoreConditions) {
        if (owner == null || entity == null || entity.isDead() || !entity.isValid()) {
            return BehaviorFireResult.EMPTY;
        }
        List<MobProfile> candidates = profilesByType.get(entity.getType());
        List<MobProfile> owned = profilesByOwner.get(owner);
        if (candidates == null || candidates.isEmpty() || owned == null || owned.isEmpty()) {
            return BehaviorFireResult.EMPTY;
        }
        BehaviorFireResult.Builder result = new BehaviorFireResult.Builder();
        // Fresh per-call budget: a forced pass never inherits lifetime edits.
        RunGuard guard = new RunGuard(settings.maxBlockEditsPerRun());
        // Same reusable evaluation context the run loop uses; forceFire is
        // main-thread-only (enforced by the API), and the context binds one
        // entity per call anyway.
        evaluationContext.refresh(entity, ThreadLocalRandom.current(), guard);
        for (MobProfile profile : candidates) {
            if (!owned.contains(profile)) {
                continue;
            }
            boolean conditionsOk = ignoreConditions || conditionsPass(profile);
            for (MobProfile.ActionEntry entry : profile.actions()) {
                if (!conditionsOk) {
                    result.add(entry.action().id(), BehaviorFireResult.Status.CONDITION_FAILED);
                    continue;
                }
                boolean fired = false;
                try {
                    fired = entry.action().execute(evaluationContext);
                } catch (Throwable throwable) {
                    plugin.getLogger().warning("[mob-behavior] force-fire action '" + entry.action().id()
                            + "' threw on " + entity.getType() + ": " + throwable);
                }
                if (fired) {
                    telemetry.eventFired(entry.action().id());
                    result.add(entry.action().id(), BehaviorFireResult.Status.FIRED);
                } else {
                    result.add(entry.action().id(), BehaviorFireResult.Status.DECLINED);
                }
            }
        }
        return result.build();
    }

    private boolean conditionsPass(MobProfile profile) {
        List<Predicate<BehaviorContext>> conditions = profile.conditions();
        for (int i = 0; i < conditions.size(); i++) {
            try {
                if (!conditions.get(i).test(evaluationContext)) {
                    return false;
                }
            } catch (Throwable throwable) {
                plugin.getLogger().warning("[mob-behavior] profile condition " + i + " threw: " + throwable);
                return false;
            }
        }
        return true;
    }

    /** Per-run edit guard enforcing {@code MobBehaviorSettings#maxBlockEditsPerRun}. */
    private static final class RunGuard implements BehaviorContext.EditRunGuard {
        private final int limit;
        private int used;

        RunGuard(int limit) {
            this.limit = limit;
        }

        @Override
        public boolean tryConsume(MobBehaviorEngine engine) {
            if (used >= limit) {
                return false;
            }
            used++;
            return true;
        }
    }
}
