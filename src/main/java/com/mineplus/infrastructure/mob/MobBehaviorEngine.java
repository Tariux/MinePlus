package com.mineplus.infrastructure.mob;

import com.mineplus.infrastructure.mob.journey.JourneyRunner;
import com.mineplus.infrastructure.mob.journey.StructureLookup;
import com.mineplus.util.DebugLogger;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
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
 *   <li><b>Activity claims:</b> an action holding an exclusive activity over
 *       an entity re-fires on every stagger (no odds gate) and every other
 *       action skips that entity — long-running scenarios stay coherent.</li>
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
    /** Profiles bound to a custom identity ({@code namespace:path}), never consulted for vanilla entities. */
    private final Map<String, List<MobProfile>> profilesByCustomId = new ConcurrentHashMap<>();
    private final Map<Plugin, List<MobProfile>> profilesByOwner = new ConcurrentHashMap<>();
    /**
     * Resolver that reveals a Mineplus custom identity. Installed by the custom
     * mob manager; while unset, only vanilla type dispatch exists. A resolved id
     * selects that id's profiles <em>instead of</em> the type's, so a custom mob
     * can never inherit vanilla behavior.
     */
    private volatile MobIdentityResolver identityResolver;
    private final Map<UUID, ActiveActivity> activities = new ConcurrentHashMap<>();
    private final BehaviorTelemetry telemetry = new BehaviorTelemetry();
    private final BehaviorContext evaluationContext = new BehaviorContext(this);
    /** Long-distance walking state, owned by the engine so one runner serves every module. */
    private final JourneyRunner journeys;
    /** Cached, cost-gated structure discovery (the village a pilgrim is looking for). */
    private final StructureLookup structures = new StructureLookup();
    /** Formations and roles that shape a crew around its formation point. */
    private final com.mineplus.infrastructure.mob.group.SquadManager squads =
            new com.mineplus.infrastructure.mob.group.SquadManager();
    /** Groups of mobs that move as one (a pilgrim crew, an escort). */
    private final com.mineplus.infrastructure.mob.group.MobCrewService crews =
            new com.mineplus.infrastructure.mob.group.MobCrewService(squads);
    /** Multi-stage scenarios attached to individual mobs (find, march, raid). */
    private final com.mineplus.infrastructure.mob.pipeline.MobPipelineEngine pipelines;
    private final java.util.concurrent.atomic.AtomicLong registrationSequence =
            new java.util.concurrent.atomic.AtomicLong();

    private volatile MobBehaviorSettings settings = MobBehaviorSettings.defaults();
    private volatile com.mineplus.infrastructure.perf.PerfRegistry perf;
    private volatile BukkitTask runTask;
    private volatile boolean running;
    private int runCounter;
    /** Entities advanced by this run's sustained sweep, so the proximity sweep skips them. */
    private final java.util.Set<UUID> sustainedThisRun = new java.util.HashSet<>();

    /**
     * Fixed dispatch order for profiles sharing one entity type: higher
     * priority first, then registration order. Because the engine re-sorts on
     * every registration, a module toggling a feature can never shuffle another
     * module's profile into a different position than it had before.
     */
    private static final java.util.Comparator<MobProfile> DISPATCH_ORDER = (first, second) ->
            first.priority() != second.priority()
                    ? Integer.compare(second.priority(), first.priority())
                    : Long.compare(first.sequence(), second.sequence());

    /**
     * Runs a claim may go unmatched to a loaded entity before it is treated as
     * abandoned. A journey crosses chunks that unload behind the mob, and the
     * engine runs every few ticks — a short limit silently deleted long
     * marches after a couple of seconds in unloaded terrain.
     */
    private static final int CLAIM_UNRESOLVED_LIMIT = 400;

    /**
     * An exclusive, self-renewing activity one action holds over an entity:
     * while it is active, the claiming action re-fires on every engine
     * stagger (no odds gate — a running activity is a process, not a random
     * event) and every other action skips the entity entirely. Long-running
     * scenarios (journeys, camps, escorts) use this so showcases cannot
     * interleave and pull one mob in two directions.
     */
    private static final class ActiveActivity {

        private final String actionId;
        private final long expiresAtGameTick;
        private final Location anchor;
        private final double anchorRadius;
        /** The priority of the profile that owns the claim; arbitrates takeovers. */
        private final int priority;
        /** Consecutive runs the entity could not be resolved (unloaded, not gone). */
        private int unresolvedRuns;

        ActiveActivity(String actionId, long expiresAtGameTick, Location anchor, double anchorRadius,
                       int priority) {
            this.actionId = actionId;
            this.expiresAtGameTick = expiresAtGameTick;
            this.anchor = anchor;
            this.anchorRadius = anchorRadius;
            this.priority = priority;
        }

        String actionId() {
            return actionId;
        }

        int priority() {
            return priority;
        }

        Location anchor() {
            return anchor;
        }

        double anchorRadius() {
            return anchorRadius;
        }

        boolean expired(long nowGameTick) {
            return expiresAtGameTick >= 0 && nowGameTick >= expiresAtGameTick;
        }
    }

    /** Called by the Core's {@code PluginContext#bootstrap}; one engine per Core. */
    public MobBehaviorEngine(Plugin plugin) {
        this.plugin = plugin;
        this.journeys = new JourneyRunner(plugin);
        this.pipelines = new com.mineplus.infrastructure.mob.pipeline.MobPipelineEngine(this);
    }

    /** The active engine settings snapshot. */
    MobBehaviorSettings settings() {
        return settings;
    }

    BehaviorTelemetry telemetry() {
        return telemetry;
    }

    /** The Core plugin that owns the scheduler and chunk tickets. */
    Plugin plugin() {
        return plugin;
    }

    /**
     * Installs (or clears) the custom-identity resolver. While installed, an
     * entity carrying a custom id is evaluated against that id's profiles only;
     * its vanilla type's profiles are never consulted. {@code null} restores
     * pure vanilla-type dispatch for every entity.
     */
    public void setIdentityResolver(MobIdentityResolver resolver) {
        this.identityResolver = resolver;
    }

    /**
     * The profiles that apply to {@code entity}: a custom identity's profiles
     * when the entity carries one, otherwise the profiles registered for its
     * vanilla {@link EntityType}. The two sets never mix.
     */
    private List<MobProfile> candidatesFor(LivingEntity entity) {
        MobIdentityResolver resolver = identityResolver;
        if (resolver != null) {
            String customId;
            try {
                customId = resolver.customMobId(entity);
            } catch (Throwable throwable) {
                // A broken resolver must not stall the whole sweep.
                customId = null;
            }
            if (customId != null) {
                List<MobProfile> custom = profilesByCustomId.get(customId);
                return custom == null ? List.of() : custom;
            }
        }
        List<MobProfile> typed = profilesByType.get(entity.getType());
        return typed == null ? List.of() : typed;
    }

    private boolean hasAnyProfiles() {
        return !profilesByType.isEmpty() || !profilesByCustomId.isEmpty();
    }

    /** The engine's shared long-walk runner. */
    public JourneyRunner journeys() {
        return journeys;
    }

    /** The engine's shared structure-discovery cache. */
    public StructureLookup structures() {
        return structures;
    }

    /** Multi-stage scenario runner (the pipeline API's execution half). */
    public com.mineplus.infrastructure.mob.pipeline.MobPipelineEngine pipelines() {
        return pipelines;
    }

    // ------------------------------------------------------------------
    // Pipeline bridge: the pipeline package works on public surface only
    // ------------------------------------------------------------------

    /** Claims the entity for a pipeline activity (see {@link #claimActivity}). */
    public void claimEntity(LivingEntity entity, String activityId, Location anchor, double anchorRadius,
                            long durationTicks, int priority) {
        claimActivity(entity, activityId, anchor, anchorRadius, durationTicks, priority);
    }

    /** Releases the entity's activity when {@code activityId} owns it. */
    public boolean releaseEntityActivity(LivingEntity entity, String activityId) {
        return releaseActivity(entity, activityId);
    }

    /** The action id currently owning the entity, or {@code null}. */
    public String entityActivityId(LivingEntity entity) {
        return activityIdOf(entity);
    }

    /** The engine's shared crew (group-movement) registry. */
    public com.mineplus.infrastructure.mob.group.MobCrewService crews() {
        return crews;
    }

    /** The engine's formation/role registry for crews. */
    public com.mineplus.infrastructure.mob.group.SquadManager squads() {
        return squads;
    }

    /**
     * Grants a bounded world-work allowance for a scenario that outlives one
     * evaluation (a build spread over scheduled ticks, the summon at its end).
     * Both budgets are capped by the engine's per-run limits —
     * {@link MobBehaviorSettings#maxBlockEditsPerRun()} and
     * {@link MobBehaviorSettings#maxEntitySpawnsPerRun()} — so no module can ask
     * its way out of the subsystem's limits.
     */
    public MobEditSession openEditSession(int editBudget, int spawnBudget) {
        int editCap = Math.max(1, settings.maxBlockEditsPerRun());
        int spawnCap = Math.max(1, settings.maxEntitySpawnsPerRun());
        return new MobEditSession(Math.min(Math.max(0, editBudget), editCap),
                Math.min(Math.max(0, spawnBudget), spawnCap), telemetry);
    }

    /** Registers a profile; starts the engine on the first registration. */
    void register(Plugin owner, MobProfile profile) {
        if (owner == null || profile == null) {
            throw new IllegalArgumentException("owner and profile are required");
        }
        profile.stampSequence(registrationSequence.incrementAndGet());
        List<MobProfile> owned = ownedList(owner);
        owned.add(profile);
        List<MobProfile> bucket = profile.isCustom()
                ? customList(profile.customId())
                : typedList(profile.entityType());
        synchronized (bucket) {
            bucket.add(profile);
            bucket.sort(DISPATCH_ORDER);
        }
        ensureRunning();
        if (DebugLogger.isEnabled()) {
            plugin.getLogger().info("[mob-behavior] " + owner.getName() + " registered profile for "
                    + (profile.isCustom() ? profile.customId() + " (" + profile.entityType() + ")" : profile.entityType())
                    + " (" + profilesByType.size() + " types, " + profilesByCustomId.size()
                    + " custom ids active)");
        }
    }

    private List<MobProfile> ownedList(Plugin owner) {
        return profilesByOwner.computeIfAbsent(owner, key -> Collections.synchronizedList(new ArrayList<>()));
    }

    private List<MobProfile> typedList(EntityType type) {
        return profilesByType.computeIfAbsent(type, key -> Collections.synchronizedList(new ArrayList<>()));
    }

    private List<MobProfile> customList(String customId) {
        return profilesByCustomId.computeIfAbsent(customId,
                key -> Collections.synchronizedList(new ArrayList<>()));
    }

    /** Removes one specific profile from {@code owner}'s registrations. */
    void unregister(Plugin owner, MobProfile profile) {
        List<MobProfile> owned = owner == null || profile == null ? null : profilesByOwner.get(owner);
        if (owned == null || !owned.remove(profile)) {
            return;
        }
        removeFromBucket(profile);
    }

    /** Removes {@code profile} from whichever dispatch bucket registered it. */
    private void removeFromBucket(MobProfile profile) {
        Map<?, List<MobProfile>> index = profile.isCustom() ? profilesByCustomId : profilesByType;
        Object key = profile.isCustom() ? profile.customId() : profile.entityType();
        List<MobProfile> list = index.get(key);
        if (list == null) {
            return;
        }
        synchronized (list) {
            list.remove(profile);
        }
        if (list.isEmpty()) {
            index.remove(key, list);
        }
    }

    /** Removes every profile registered by {@code owner}. */
    void unregisterAll(Plugin owner) {
        List<MobProfile> owned = owner == null ? null : profilesByOwner.remove(owner);
        if (owned == null || owned.isEmpty()) {
            return;
        }
        for (MobProfile profile : owned) {
            removeFromBucket(profile);
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

    /** Number of distinct custom identities with at least one active profile. */
    int profileCustomIdCount() {
        return profilesByCustomId.size();
    }

    /** Telemetry sink for run durations (may be {@code null}). */
    public void setPerfRegistry(com.mineplus.infrastructure.perf.PerfRegistry perf) {
        this.perf = perf;
    }

    /** Applies new settings (config reload). Safe while running. */
    void updateSettings(MobBehaviorSettings newSettings) {
        MobBehaviorSettings previous = this.settings;
        this.settings = newSettings == null ? MobBehaviorSettings.defaults() : newSettings;
        BukkitTask task = runTask;
        if (task != null && previous.intervalTicks() != this.settings.intervalTicks()) {
            // The cadence is a schedule property, not a loop property: re-arm,
            // otherwise a reload leaves the engine on the old interval forever.
            running = false;
            task.cancel();
            runTask = null;
            if (this.settings.enabled() && hasAnyProfiles()) {
                ensureRunning();
            }
        }
    }

    // ------------------------------------------------------------------
    // Activity claims (exclusive ownership of one entity's behavior)
    // ------------------------------------------------------------------

    /**
     * Claims or renews the entity's exclusive activity for one action id.
     * A live claim owned by a different action id is never overridden — unless
     * the claimant outranks the owner, which is how a scenario with a higher
     * priority takes a mob over from one that claimed it first (a guardian
     * taking a villager out of a pilgrimage because it is under attack).
     * The claiming action renews its own claim each execution. Negative
     * {@code durationTicks} means "until released" — modules normally renew
     * a bounded claim each fire so a crashed scenario self-expires.
     */
    void claimActivity(LivingEntity entity, String actionId, Location anchor, double anchorRadius,
                       long durationTicks, int priority) {
        if (entity == null || entity.isDead() || !entity.isValid()
                || actionId == null || actionId.isBlank()) {
            return;
        }
        ActiveActivity current = activities.get(entity.getUniqueId());
        if (current != null && !current.actionId().equals(actionId)
                && !activityExpired(current, entity) && current.priority() >= priority) {
            return; // another activity owns the entity and outranks the claimant
        }
        long now = entity.getWorld().getGameTime();
        long expiry = durationTicks > 0 ? now + durationTicks : -1L;
        activities.put(entity.getUniqueId(),
                new ActiveActivity(actionId, expiry, anchor, Math.max(0.0, anchorRadius), priority));
    }

    /** Drops the entity's claim when owned by {@code actionId}; {@code true} when dropped. */
    boolean releaseActivity(LivingEntity entity, String actionId) {
        if (entity == null || actionId == null) {
            return false;
        }
        ActiveActivity current = activities.get(entity.getUniqueId());
        if (current == null || !current.actionId().equals(actionId)) {
            return false;
        }
        return activities.remove(entity.getUniqueId(), current);
    }

    /** The action id holding the entity's live claim, or {@code null}. */
    String activityIdOf(LivingEntity entity) {
        ActiveActivity activity = activityOf(entity);
        return activity == null ? null : activity.actionId();
    }

    /** The entity's live claim, pruned when expired or the entity is gone. */
    private ActiveActivity activityOf(LivingEntity entity) {
        if (entity == null) {
            return null;
        }
        if (entity.isDead() || !entity.isValid()) {
            activities.remove(entity.getUniqueId());
            return null;
        }
        ActiveActivity activity = activities.get(entity.getUniqueId());
        if (activity != null && activityExpired(activity, entity)) {
            activities.remove(entity.getUniqueId(), activity);
            return null;
        }
        return activity;
    }

    private boolean activityExpired(ActiveActivity activity, LivingEntity entity) {
        return activity.expired(entity.getWorld().getGameTime());
    }

    /** The anchor of the entity's live claim, or {@code null}. Package view for the context. */
    Location activityAnchorOf(LivingEntity entity) {
        ActiveActivity activity = activityOf(entity);
        return activity == null ? null : activity.anchor();
    }

    /** The entity's activity anchor radius, or {@code -1.0} when unclaimed. */
    double activityAnchorRadiusOf(LivingEntity entity) {
        ActiveActivity activity = activityOf(entity);
        return activity == null ? -1.0 : activity.anchorRadius();
    }

    /**
     * Frees claims whose entity is gone or whose duration ran out.
     *
     * <p>An unresolved UUID is ambiguous: the entity is dead and despawned, or
     * it simply walked outside anyone's view distance and its chunk unloaded.
     * Dropping the claim on the first miss would delete a journey the moment a
     * pilgrim villager walked out of render distance, so a miss only counts —
     * the claim survives until it has gone unresolved for
     * {@link #CLAIM_UNRESOLVED_LIMIT} runs, which a despawn cannot recover from
     * and an unloaded chunk does as soon as it loads again.
     */
    private void pruneActivities() {
        if (activities.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, ActiveActivity> entry : activities.entrySet()) {
            ActiveActivity claim = entry.getValue();
            Entity entity = Bukkit.getEntity(entry.getKey());
            if (entity == null) {
                if (++claim.unresolvedRuns >= CLAIM_UNRESOLVED_LIMIT) {
                    activities.remove(entry.getKey());
                }
                continue;
            }
            claim.unresolvedRuns = 0;
            if (!(entity instanceof LivingEntity living) || living.isDead() || !living.isValid()
                    || activityExpired(claim, living)) {
                activities.remove(entry.getKey());
            }
        }
    }

    /** Cancels the run task. Idempotent; called from the Core's disable path. */
    void shutdown() {
        running = false;
        BukkitTask task = runTask;
        runTask = null;
        pipelines.cancelAll();
        activities.clear();
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

    /** Timed wrapper: reports run duration and run count to the perf registry. */
    private void run() {
        long started = System.nanoTime();
        try {
            runInternal();
        } finally {
            com.mineplus.infrastructure.perf.PerfRegistry registry = perf;
            if (registry != null && settings.enabled() && hasAnyProfiles()) {
                registry.setGauge(com.mineplus.infrastructure.perf.PerfRegistry.Gauge.MOB_ENGINE_RUN_MILLIS,
                        (System.nanoTime() - started) / 1_000_000L);
                registry.add(com.mineplus.infrastructure.perf.PerfRegistry.Counter.MOB_ENGINE_RUNS, 1);
            }
        }
    }

    private void runInternal() {
        MobBehaviorSettings snapshot = settings;
        if (!snapshot.enabled() || !hasAnyProfiles()) {
            return;
        }
        pruneActivities();
        telemetry.runDone();
        journeys.step();
        crews.step();
        long deadline = System.nanoTime() + snapshot.budgetMillisPerRun() * 1_000_000L;
        // Scenarios first: a pipeline is a process, and a process that only
        // advances while a player stands next to it never finishes.
        pipelines.tick(deadline, snapshot.maxPipelinesPerRun());
        int buckets = Math.max(1, snapshot.staggerBuckets());
        int runIndex = runCounter++;
        RunGuard guard = new RunGuard(snapshot.maxBlockEditsPerRun(), snapshot.maxEntitySpawnsPerRun());

        // Running scenarios first: a claimed entity is a process, and a process
        // that only advances while a player stands next to it never finishes.
        sustainedThisRun.clear();
        advanceSustained(snapshot, guard, deadline);

        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            return;
        }
        int chunkParity = runIndex & 1;
        // Only chunks of one parity are swept per run, so a chunk is seen on
        // runs runIndex, runIndex+2, ... — whose scan ordinal is runIndex >> 1.
        // Staggering against the run index itself would silently starve every
        // entity whose bucket disagrees with its chunk's parity.
        int bucketTarget = (int) Math.floorMod(runIndex >> 1, buckets);
        LongOpenHashSet seen = new LongOpenHashSet(64);

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
                        List<MobProfile> candidates = candidatesFor(living);
                        if (candidates.isEmpty()) {
                            continue;
                        }
                        if (sustainedThisRun.contains(living.getUniqueId())) {
                            continue;
                        }
                        if (Math.floorMod(entity.getEntityId(), buckets) != bucketTarget) {
                            continue;
                        }
                        evaluate(living, type, candidates, snapshot, guard, deadline);
                    }
                }
            }
        }
    }

    /**
     * Advances every activity-claimed entity once, wherever it is. Claims are
     * bounded by {@code MAX_SUSTAINED_PER_RUN} and share the run's time budget,
     * so a hundred journeys still cost one controlled slice of the tick.
     */
    private void advanceSustained(MobBehaviorSettings snapshot, RunGuard guard, long deadline) {
        if (activities.isEmpty()) {
            return;
        }
        int cap = Math.max(0, snapshot.maxSustainedPerRun());
        int processed = 0;
        for (Map.Entry<UUID, ActiveActivity> entry : activities.entrySet()) {
            if (processed >= cap) {
                telemetry.budgetDeferred();
                return;
            }
            if (System.nanoTime() >= deadline) {
                telemetry.budgetDeferred();
                return;
            }
            // An unloaded entity resolves to nothing: its claim survives (see
            // pruneActivities) and the scenario resumes when the chunk returns.
            if (!(Bukkit.getEntity(entry.getKey()) instanceof LivingEntity living)
                    || living.isDead() || !living.isValid()) {
                continue;
            }
            List<MobProfile> candidates = candidatesFor(living);
            if (candidates.isEmpty()) {
                continue;
            }
            // Pipelines already advanced this entity in this run.
            if (pipelines.owns(living)) {
                continue;
            }
            processed++;
            sustainedThisRun.add(living.getUniqueId());
            evaluate(living, living.getType(), candidates, snapshot, guard, deadline);
        }
    }

    private void evaluate(LivingEntity entity, EntityType type, List<MobProfile> candidates,
                          MobBehaviorSettings snapshot, RunGuard guard, long deadline) {
        telemetry.candidateSeen();
        // A pipeline owns the entity as a whole scenario. Ordinary actions must
        // not pull it mid-march; the pipeline engine has already advanced it.
        if (pipelines.owns(entity)) {
            return;
        }
        evaluationContext.refresh(entity, ThreadLocalRandom.current(), guard);
        double checksPerSecond = snapshot.checksPerSecond();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        // A claimed entity belongs to one running activity: the owning action
        // re-fires on every stagger (no odds roll) and nothing else touches
        // the entity. If the owning action vanished (its profile was
        // unregistered), the claim is dropped so the entity returns to normal.
        ActiveActivity activity = activityOf(entity);
        if (activity != null) {
            if (System.nanoTime() >= deadline) {
                telemetry.budgetDeferred();
                return;
            }
            // A scenario with a higher priority may take the entity over: the
            // guardian of a villager under attack outranks the pilgrimage it
            // was walking. The challenger plays the odds like any other action
            // and keeps the mob only by claiming it.
            //
            // Everything short of a claim leaves the running scenario alone:
            // a claim is a process, and it advances on every pass. A challenger
            // that rolled no turn, declined, or acted without claiming (a
            // lamplighter planting a torch next to a marching pilgrim) has not
            // taken the mob over and must not cost the scenario this pass —
            // skipping it was what made a claimed journey creep or stand still
            // while higher-priority ambient actions went about their business.
            MobProfile challenger = challengerFor(activity, candidates);
            if (challenger != null) {
                for (MobProfile.ActionEntry entry : challenger.actions()) {
                    if (System.nanoTime() >= deadline) {
                        telemetry.budgetDeferred();
                        return;
                    }
                    double seconds = Math.max(0.25, entry.intervalSeconds());
                    double odds = Math.min(1.0, 1.0 / (seconds * checksPerSecond));
                    if (random.nextDouble() > odds) {
                        continue;
                    }
                    evaluationContext.bindProfile(challenger);
                    evaluationContext.bindAction(entry.action().id());
                    boolean fired;
                    try {
                        fired = entry.action().execute(evaluationContext);
                    } catch (Throwable throwable) {
                        plugin.getLogger().warning("[mob-behavior] action '" + entry.action().id()
                                + "' threw on " + type + ": " + throwable);
                        fired = false;
                    }
                    if (fired) {
                        telemetry.eventFired(entry.action().id());
                    }
                    ActiveActivity current = activityOf(entity);
                    if (current == null || !current.actionId().equals(activity.actionId())) {
                        // The challenger claimed the mob (or ended the claim):
                        // the previous scenario no longer owns this pass.
                        return;
                    }
                }
            }
            boolean found = false;
            for (MobProfile profile : candidates) {
                if (!conditionsPass(profile)) {
                    continue;
                }
                for (MobProfile.ActionEntry entry : profile.actions()) {
                    if (!entry.action().id().equals(activity.actionId())) {
                        continue;
                    }
                    found = true;
                    evaluationContext.bindProfile(profile);
                    evaluationContext.bindAction(entry.action().id());
                    try {
                        if (entry.action().execute(evaluationContext)) {
                            telemetry.eventFired(entry.action().id());
                        }
                    } catch (Throwable throwable) {
                        plugin.getLogger().warning("[mob-behavior] activity '" + entry.action().id()
                                + "' threw on " + type + ": " + throwable);
                    }
                    return;
                }
            }
            if (!found) {
                // The owning profile was unregistered: the claim is stale.
                activities.remove(entity.getUniqueId());
            }
            return;
        }

        for (MobProfile profile : candidates) {
            if (System.nanoTime() >= deadline) {
                telemetry.budgetDeferred();
                return;
            }
            if (!conditionsPass(profile)) {
                continue;
            }
            evaluationContext.bindProfile(profile);
            for (MobProfile.ActionEntry entry : profile.actions()) {
                double seconds = Math.max(0.25, entry.intervalSeconds());
                double odds = entry.action().continuous() ? 1.0 : Math.min(1.0, 1.0 / (seconds * checksPerSecond));
                if (random.nextDouble() > odds) {
                    continue;
                }
                evaluationContext.bindAction(entry.action().id());
                boolean fired;
                try {
                    fired = entry.action().execute(evaluationContext);
                } catch (Throwable throwable) {
                    // One broken action must never kill the run loop: isolate,
                    // log, and keep the engine alive.
                    plugin.getLogger().warning("[mob-behavior] action '" + entry.action().id()
                            + "' threw on " + type + ": " + throwable);
                    continue;
                }
                if (fired) {
                    telemetry.eventFired(entry.action().id());
                    return;
                }
                // Declined: the mob had nothing to do on this spot (no plant to
                // eat, no space to build). Keeping the roll spent would starve
                // every other profile of the same type — a four-profile
                // villager would only ever run the first one that rolled.
            }
        }
    }

    /**
     * The profile allowed to try to take a claimed entity over: the
     * highest-priority one whose conditions pass and whose priority beats the
     * claim's owner. {@code candidates} is already sorted by priority, so the
     * first match is the strongest.
     */
    private MobProfile challengerFor(ActiveActivity activity, List<MobProfile> candidates) {
        for (MobProfile profile : candidates) {
            if (profile.priority() <= activity.priority()) {
                break; // the list is ordered, so nothing below can outrank it
            }
            if (conditionsPass(profile)) {
                return profile;
            }
        }
        return null;
    }

    /**
     * Forces one direct firing pass on a specific entity: every action
     * registered by {@code owner} for the entity's type runs immediately with
     * the odds gate bypassed, unlike the regular one-action-per-evaluation
     * loop. Activity claims do not gate a forced pass (forcing is an explicit
     * test hook). Profile conditions still gate the actions unless
     * {@code ignoreConditions} is set, so the report shows why an action
     * would not fire naturally. Works even when the loop is disabled by
     * settings; every action still runs through the edit budget. Main thread.
     */
    BehaviorFireResult forceFire(Plugin owner, LivingEntity entity, boolean ignoreConditions) {
        if (owner == null || entity == null || entity.isDead() || !entity.isValid()) {
            return BehaviorFireResult.EMPTY;
        }
        List<MobProfile> candidates = candidatesFor(entity);
        List<MobProfile> owned = profilesByOwner.get(owner);
        if (candidates.isEmpty() || owned == null || owned.isEmpty()) {
            return BehaviorFireResult.EMPTY;
        }
        BehaviorFireResult.Builder result = new BehaviorFireResult.Builder();
        // Fresh per-call budget: a forced pass never inherits lifetime edits.
        RunGuard guard = new RunGuard(settings.maxBlockEditsPerRun(), settings.maxEntitySpawnsPerRun());
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
                evaluationContext.bindAction(entry.action().id());
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

    /** Per-run budget guard for world edits and entity spawns. */
    private static final class RunGuard implements BehaviorContext.EditRunGuard {
        private final int editLimit;
        private final int spawnLimit;
        private int edits;
        private int spawns;

        RunGuard(int editLimit, int spawnLimit) {
            this.editLimit = editLimit;
            this.spawnLimit = spawnLimit;
        }

        @Override
        public boolean tryConsume(MobBehaviorEngine engine) {
            if (edits >= editLimit) {
                return false;
            }
            edits++;
            return true;
        }

        @Override
        public boolean tryConsumeSpawn(MobBehaviorEngine engine) {
            if (spawns >= spawnLimit) {
                return false;
            }
            spawns++;
            return true;
        }

        @Override
        public int remainingEdits() {
            return Math.max(0, editLimit - edits);
        }
    }
}