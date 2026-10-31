package com.mineplus.infrastructure.virtual.animation;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.infrastructure.virtual.display.DisplayTransport;
import com.mineplus.infrastructure.virtual.display.pool.PooledDisplay;
import com.mineplus.util.DebugLogger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Animation runtime: advances clip controllers and pushes composed display
 * matrices every tick.
 *
 * <p>Driven off {@link VirtualBlockManager#activeBlocksView()}: every spawn path
 * (place, upgrade swap, deferred render, restore, debug spawn) lands there, and
 * every removal path drops out of it, so controllers self-attach on first sight
 * and self-clean when the rendered instance disappears — no per-lifecycle
 * wiring. Autoplay (JSON-declared or meta-declared clips) starts the first time
 * a rendered instance is seen; explicit API calls are never overridden because
 * a stopped clip keeps a tombstone entry until the rendered id changes.
 *
 * <p>Per update each bound display receives
 * {@code T(pivotFix)·R_placement·boneDelta(t)·restLocal} via
 * {@code setTransformationMatrix}; with client interpolation enabled the motion
 * renders at the client's frame rate.
 */
public final class ModelAnimationManager {

    private final JavaPlugin plugin;
    private final VirtualBlockManager virtualBlockManager;
    private AnimationSettings settings = AnimationSettings.defaults();
    private AnimationInstanceBridge bridge;
    private BukkitTask tickTask;
    private io.papermc.paper.threadedregions.scheduler.ScheduledTask foliaTask;
    private volatile com.mineplus.infrastructure.perf.PerfRegistry perf;
    /** Ticks between the currently scheduled pose pushes (adaptive valve may raise it). */
    private int scheduledInterval;
    /**
     * Animated-instance state. Concurrent because hooks (play/stop/pause via
     * {@code AnimationApi}) can arrive from any thread while the tick runs on the
     * global scheduler (Folia) or the main thread; the map itself must never race.
     */
    private final Map<UUID, AnimatedInstance> instances = new ConcurrentHashMap<>();

    // Async pose pipeline (ITEM 1): one bounded worker thread evaluates poses; the
    // main thread applies finished frames under MAX_APPLY_PER_TICK and carries the
    // remainder. The generation guard discards results that a reload/settings
    // change superseded while the worker was busy.
    private final AtomicLong poseGeneration = new AtomicLong();
    private final AtomicReference<PoseFrame> completedFrame = new AtomicReference<>();
    private final ArrayDeque<PoseFrame> applyQueue = new ArrayDeque<>();
    private final Set<UUID> pendingApply = new HashSet<>();
    private volatile ThreadPoolExecutor poseExecutor;
    /** One worker failure degrades the session to the synchronous pipeline. */
    private volatile boolean poseDegraded;

    // Reusable submission batch: the manager never resubmits while a batch is in
    // flight, so the worker can read these arrays without a copy.
    private UUID[] jobIds = new UUID[16];
    private AnimatedInstance[] jobStates = new AnimatedInstance[16];
    private VirtualModel[] jobModels = new VirtualModel[16];
    private AutoStateSource autoStateSource;

    // JOML object reuse (zero allocation in the animation loop); grown on demand
    // for models with more bones than the initial capacity.
    private static final int SCRATCH_INITIAL_BONES = 256;
    private static final ThreadLocal<Matrix4f[]> SCRATCH_MATRICES = ThreadLocal.withInitial(() -> new Matrix4f[SCRATCH_INITIAL_BONES]);
    private static final ThreadLocal<Matrix4f> SCRATCH_OUT = ThreadLocal.withInitial(Matrix4f::new);

    /** Automatic state selection priority; explicit calls (priority 0) always win. */
    private static final int AUTO_PRIORITY = -1;
    private static final String AUTO_ATTACK = "attack";
    private static final String AUTO_AIRBORNE = "airborne";
    private static final String AUTO_SWIM = "swim";
    private static final String AUTO_WALK = "walk";
    private static final String AUTO_IDLE = "idle";

    /** Immutable per-tick snapshot of one viewer: position plus normalized look direction. */
    private record ViewerPos(double x, double y, double z, float lookX, float lookY, float lookZ) {
    }

    private record PoseEntry(UUID renderedModelId, AnimatedInstance state) {
    }

    /** A finished off-thread evaluation, consumed entry by entry under the apply cap. */
    private static final class PoseFrame {
        final long generation;
        final List<PoseEntry> entries;
        int cursor;

        PoseFrame(long generation, List<PoseEntry> entries) {
            this.generation = generation;
            this.entries = entries;
        }
    }

    private static final int MAX_QUEUED_FRAMES = 2;

    private static final class AnimatedInstance {
        final String modelKey;
        final List<AnimationBinding> bindings;
        final Quaternionf globalRotation;
        final Vector3f pivotCorrection;
        /** Composed binding matrices, written by the pose worker and read by the main thread. */
        final Matrix4f[] poseMatrices;
        /** Reused controller snapshots (no per-tick allocation in steady state). */
        ControllerSample[] sampleBuffer = new ControllerSample[0];
        int sampleCount;
        boolean autoplayResolved;
        boolean interpolationApplied;
        boolean dirty = true;
        final Map<String, AnimationController> controllers = new LinkedHashMap<>();

        // Automatic state selection (entity-driven); untouched when AUTO_STATE is off.
        String autoState;
        String pendingAutoState;
        boolean autoDisabled;
        double lastEntityX;
        double lastEntityZ;
        boolean hasEntityPosition;
        float limbSwing;
        float swingSpeed;

        AnimatedInstance(String modelKey, List<AnimationBinding> bindings,
                         Quaternionf globalRotation, Vector3f pivotCorrection) {
            this.modelKey = modelKey;
            this.bindings = List.copyOf(bindings);
            this.globalRotation = new Quaternionf(globalRotation);
            this.pivotCorrection = new Vector3f(pivotCorrection);
            this.poseMatrices = new Matrix4f[this.bindings.size()];
            for (int i = 0; i < poseMatrices.length; i++) {
                poseMatrices[i] = new Matrix4f();
            }
        }
    }

    public ModelAnimationManager(JavaPlugin plugin, VirtualBlockManager virtualBlockManager) {
        this.plugin = plugin;
        this.virtualBlockManager = virtualBlockManager;
    }

    public void bindBridge(AnimationInstanceBridge bridge) {
        this.bridge = bridge;
    }

    /** Telemetry sink for pose slice cost and the adaptive valve (may be {@code null}). */
    public void setPerfRegistry(com.mineplus.infrastructure.perf.PerfRegistry perf) {
        this.perf = perf;
    }

    /**
     * Supplies the entity that drives automatic state selection. When unset the
     * bridge's {@link AnimationInstanceBridge#entityFor(UUID)} is consulted.
     */
    public void setAutoStateSource(AutoStateSource source) {
        this.autoStateSource = source;
    }

    public AnimationSettings settings() {
        return settings;
    }

    public void updateSettings(AnimationSettings settings) {
        this.settings = settings == null ? AnimationSettings.defaults() : settings;
        if (tickTask != null || foliaTask != null) {
            stop();
            start();
        }
    }

    public void start() {
        if (!settings.enabled() || tickTask != null || foliaTask != null) {
            return;
        }
        schedule(Math.max(1, settings.tickIntervalTicks()));
    }

    private void schedule(int interval) {
        scheduledInterval = Math.max(1, interval);
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            foliaTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> tick(), scheduledInterval, scheduledInterval);
        } catch (ClassNotFoundException e) {
            tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, scheduledInterval, scheduledInterval);
        }
        DebugLogger.info("ModelAnimationManager: animation tick scheduled (interval="
                + scheduledInterval + " ticks).");
    }

    public void stop() {
        cancelSchedule();
        scheduledInterval = 0;
        instances.clear();
        poseGeneration.incrementAndGet();
        completedFrame.set(null);
        applyQueue.clear();
        pendingApply.clear();
        ThreadPoolExecutor pool = poseExecutor;
        if (pool != null) {
            pool.shutdownNow();
            poseExecutor = null;
        }
    }

    private void tick() {
        long tickStart = System.nanoTime();
        Map<UUID, VirtualBlockManager.ActiveVirtualBlock> view = virtualBlockManager.activeBlocksView();
        if (instances.keySet().retainAll(view.keySet())) {
            pendingApply.retainAll(instances.keySet());
        }

        boolean async = asyncActive();
        // One evaluation batch at a time: skipping submission while the worker is
        // busy is also what makes the per-instance sample buffers safe to reuse.
        boolean workerIdle = async && poseWorkerIdle();
        int dropped = async ? drainCompletedFrames() : 0;
        int applied = async ? applyBudgetedFrames(settings.maxApplyPerTick()) : 0;
        if (async && !applyQueue.isEmpty()) {
            // The per-tick cap deferred work; the rest keeps its order next tick.
            DebugLogger.debug("Animation pose apply cap reached (" + settings.maxApplyPerTick()
                    + "); " + applyQueue.size() + " frame(s) carried over.");
        }

        DisplayTransport transport = virtualBlockManager.displayTransport();
        boolean transportRunning = transport != null && transport.isRunning();
        // LOD gates only apply through the transport; the legacy spawned-entity
        // path is ranged by vanilla tracking and always animates.
        double maxDistSq = transportRunning ? transport.settings().lodFullRangeSq() : 0.0;
        // Per-tick viewer snapshot, built lazily on the first animated instance:
        // one position read per player per tick instead of one per player per instance.
        Map<World, List<ViewerPos>> viewersByWorld = null;
        int evaluations = 0;
        int animationsActive = 0;
        int jobCount = 0;

        for (Map.Entry<UUID, VirtualBlockManager.ActiveVirtualBlock> entry : view.entrySet()) {
            VirtualBlockManager.ActiveVirtualBlock block = entry.getValue();
            if (block.animationBindings().isEmpty()) {
                continue;
            }

            if (transportRunning) {
                if (viewersByWorld == null) {
                    viewersByWorld = snapshotViewers();
                }
                if (!hasNearbyViewers(block.origin(), viewersByWorld, maxDistSq)) continue;
            }

            AnimatedInstance state = instances.computeIfAbsent(entry.getKey(), k -> createInstance(k, block));
            if (state != null) {
                if (async) {
                    boolean shouldPush = advanceInstance(entry.getKey(), state);
                    evaluations++;
                    animationsActive += playingControllers(state);
                    if (shouldPush && workerIdle && !pendingApply.contains(entry.getKey())) {
                        jobCount = addJob(jobCount, entry.getKey(), state);
                    }
                } else {
                    if (stepInstance(entry.getKey(), state) > 0) {
                        applied++;
                    }
                    evaluations++;
                    animationsActive += playingControllers(state);
                }
            }
        }

        if (async) {
            submitPoseJobs(jobCount);
            if (instances.isEmpty() && applyQueue.isEmpty() && completedFrame.get() == null) {
                // Mirror the transport lifecycle: an idle runtime holds no thread.
                ThreadPoolExecutor pool = poseExecutor;
                if (pool == null || (pool.getActiveCount() == 0 && pool.getQueue().isEmpty())) {
                    stopPoseWorker();
                }
            }
        }

        com.mineplus.infrastructure.perf.PerfRegistry registry = perf;
        if (registry != null) {
            // The pose slice is the main-thread cost (controller advance + apply);
            // off-thread evaluation never counts against the tick, but dropped
            // frames (generation/queue overflow) are reported.
            registry.recordPoseSlice(System.nanoTime() - tickStart, evaluations, applied, dropped);
            registry.setGauge(com.mineplus.infrastructure.perf.PerfRegistry.Gauge.ANIMATIONS_ACTIVE,
                    animationsActive);
            registry.setGauge(com.mineplus.infrastructure.perf.PerfRegistry.Gauge.POSE_DEGRADED,
                    poseDegraded ? 1 : 0);
        }
        maybeRescheduleForAdaptive(registry);
    }

    /**
     * {@code true} while the async pipeline should drive poses. A worker failure
     * flips the session to {@link PosePipeline#TICK} permanently (frames already
     * queued are discarded; the next tick re-pushes through the tick path).
     */
    private boolean asyncActive() {
        if (!settings.pipeline().asynchronous()) {
            return false;
        }
        if (!poseDegraded) {
            return true;
        }
        completedFrame.set(null);
        applyQueue.clear();
        pendingApply.clear();
        stopPoseWorker();
        return false;
    }

    private static int playingControllers(AnimatedInstance state) {
        int playing = 0;
        for (AnimationController controller : state.controllers.values()) {
            if (controller.playing()) {
                playing++;
            }
        }
        return playing;
    }

    /**
     * Applies the adaptive valve: when the registry decides the effective tick
     * interval must change, the scheduled task is replaced (never restarted from
     * scratch — instances and their controllers are untouched).
     */
    private void maybeRescheduleForAdaptive(com.mineplus.infrastructure.perf.PerfRegistry registry) {
        if (registry == null || !registry.adaptiveEnabled()) {
            return;
        }
        int wanted = registry.effectiveTickInterval(settings.tickIntervalTicks());
        if (wanted == scheduledInterval || wanted <= 0) {
            return;
        }
        cancelSchedule();
        schedule(wanted);
        DebugLogger.info("ModelAnimationManager: adaptive interval now " + wanted + " ticks.");
    }

    private void cancelSchedule() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (foliaTask != null) {
            foliaTask.cancel();
            foliaTask = null;
        }
    }

    /** Snapshots every online viewer's position and look direction, grouped by world. */
    private static Map<World, List<ViewerPos>> snapshotViewers() {
        Map<World, List<ViewerPos>> viewersByWorld = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location location = player.getLocation();
            double yawRad = Math.toRadians(location.getYaw());
            double pitchRad = Math.toRadians(location.getPitch());
            double cosPitch = Math.cos(pitchRad);
            // Bukkit look direction: x = -cos(pitch)*sin(yaw), y = -sin(pitch), z = cos(pitch)*cos(yaw)
            viewersByWorld
                    .computeIfAbsent(location.getWorld(), k -> new ArrayList<>())
                    .add(new ViewerPos(
                            location.getX(), location.getY(), location.getZ(),
                            (float) (-cosPitch * Math.sin(yawRad)),
                            (float) (-Math.sin(pitchRad)),
                            (float) (cosPitch * Math.cos(yawRad))));
        }
        return viewersByWorld;
    }

    /**
     * Frustum/view check: an instance animates when at least one viewer is inside
     * the transport's full-LOD range AND looking toward it (within the ~70° cone
     * a dot product of 0.3 describes).
     */
    private static boolean hasNearbyViewers(
            Location origin, Map<World, List<ViewerPos>> viewersByWorld, double maxDistSq) {
        List<ViewerPos> viewers = viewersByWorld.get(origin.getWorld());
        if (viewers == null || viewers.isEmpty()) {
            return false;
        }
        double ox = origin.getX();
        double oy = origin.getY();
        double oz = origin.getZ();
        for (ViewerPos viewer : viewers) {
            double dx = ox - viewer.x();
            double dy = oy - viewer.y();
            double dz = oz - viewer.z();
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > maxDistSq) {
                continue;
            }
            double dist = Math.sqrt(distSq);
            if (dist < 1.0e-4) {
                return true; // viewer is inside the model
            }
            // dot(lookDir, dirToModel) > 0.3 -> inside the ~70 degree cone of vision
            double dot = (viewer.lookX() * dx + viewer.lookY() * dy + viewer.lookZ() * dz) / dist;
            if (dot > 0.3) {
                return true;
            }
        }
        return false;
    }

    private AnimatedInstance createInstance(UUID renderedModelId, VirtualBlockManager.ActiveVirtualBlock block) {
        VirtualModel model = virtualBlockManager.getModel(block.modelName());
        if (model == null || model.bones().isEmpty()) {
            return null;
        }
        return new AnimatedInstance(
                block.modelName(),
                block.animationBindings(),
                block.rotation(),
                block.pivotCorrection()
        );
    }

    private int stepInstance(UUID renderedModelId, AnimatedInstance state) {
        if (!advanceInstance(renderedModelId, state)) {
            return 0;
        }
        int applied = pushPose(renderedModelId, state);
        state.dirty = false;
        return applied;
    }

    /**
     * Advances clip time, fade clocks and automatic state on the calling (main)
     * thread. Returns whether the instance needs a pose push this tick.
     */
    private boolean advanceInstance(UUID renderedModelId, AnimatedInstance state) {
        if (!state.autoplayResolved) {
            state.autoplayResolved = true;
            if (settings.autoplay()) {
                startAutoplay(renderedModelId, state);
            }
        }

        // The adaptive valve may raise the interval; the clip must still advance
        // by real elapsed time, so the delta follows the scheduled interval.
        float deltaTicks = Math.max(1, scheduledInterval);
        float deltaTime = deltaTicks / 20.0f;
        if (settings.autoState()) {
            advanceAutoState(renderedModelId, state, deltaTime);
        }

        boolean advanced = false;
        List<AnimationController> completed = null;
        List<String> expired = null;
        for (Map.Entry<String, AnimationController> entry : state.controllers.entrySet()) {
            AnimationController controller = entry.getValue();
            if (controller.stopping()) {
                controller.advanceFade(deltaTicks);
                advanced = true;
                if (controller.fadeComplete()) {
                    if (expired == null) {
                        expired = new ArrayList<>();
                    }
                    expired.add(entry.getKey());
                }
                continue;
            }
            if (!controller.playing()) {
                continue;
            }
            advanced = true;
            controller.advanceFade(deltaTicks);
            if (controller.advance(deltaTime * controller.speed())) {
                if (completed == null) {
                    completed = new ArrayList<>();
                }
                completed.add(controller);
            }
        }
        if (expired != null) {
            for (String key : expired) {
                state.controllers.remove(key);
            }
            state.dirty = true;
        }
        if (completed != null) {
            for (AnimationController controller : completed) {
                String name = controller.clip().name();
                // ONCE returns affected bones to rest (controller removed, or faded
                // out first when the controller declares a fade); HOLD freezes on
                // the final frame (controller kept, clamped).
                if (controller.effectiveLoop() == LoopMode.ONCE) {
                    if (controller.fadeOutTicks() > 0) {
                        controller.beginStop();
                    } else {
                        state.controllers.remove(AnimationClip.normalize(name));
                    }
                }
                state.dirty = true;
                fireComplete(renderedModelId, name);
            }
        }
        return advanced || state.dirty;
    }

    // ------------------------------------------------------- automatic states

    /**
     * Derives the automatic state (attack &gt; airborne &gt; swim &gt; walk &gt;
     * idle) from the model's owning entity and starts the matching clip. The
     * automatic layer always runs at {@link #AUTO_PRIORITY}, so a clip started
     * explicitly through {@code AnimationApi} (priority 0) is never overridden.
     */
    private void advanceAutoState(UUID renderedModelId, AnimatedInstance state, float deltaTime) {
        if (state.autoDisabled) {
            return;
        }
        LivingEntity entity = autoEntity(renderedModelId);
        if (entity == null || !entity.isValid()) {
            state.pendingAutoState = null;
            return;
        }
        trackStride(state, entity);

        if (state.pendingAutoState != null) {
            if (autoSwitchDue(state)) {
                startAutoState(renderedModelId, state, state.pendingAutoState);
            }
            return;
        }

        String desired = deriveAutoState(entity, state);
        if (desired.equals(state.autoState)) {
            syncWalkPhase(state);
            return;
        }
        if (settings.transition() == TransitionMode.IMMEDIATE || autoSwitchDue(state)) {
            startAutoState(renderedModelId, state, desired);
        } else {
            state.pendingAutoState = desired;
        }
    }

    private LivingEntity autoEntity(UUID renderedModelId) {
        AutoStateSource source = autoStateSource;
        if (source != null) {
            return source.entityFor(renderedModelId);
        }
        return bridge == null ? null : bridge.entityFor(renderedModelId);
    }

    /**
     * Accumulates the entity's horizontal stride (vanilla {@code limbSwing}) and
     * the normalized per-tick speed that scales the walk phase.
     */
    private static void trackStride(AnimatedInstance state, LivingEntity entity) {
        double x = entity.getX();
        double z = entity.getZ();
        if (state.hasEntityPosition) {
            double dx = x - state.lastEntityX;
            double dz = z - state.lastEntityZ;
            float moved = (float) Math.sqrt(dx * dx + dz * dz);
            if (moved < 8.0f) { // teleports are not strides
                state.limbSwing += moved;
                state.swingSpeed += (Math.min(1.0f, moved / 0.2f) - state.swingSpeed) * 0.4f;
            }
        } else {
            state.hasEntityPosition = true;
        }
        state.lastEntityX = x;
        state.lastEntityZ = z;
    }

    private static String deriveAutoState(LivingEntity entity, AnimatedInstance state) {
        if (entity instanceof org.bukkit.entity.Mob mob) {
            LivingEntity target = mob.getTarget();
            if (target != null && target.isValid() && !target.isDead()) {
                return AUTO_ATTACK;
            }
        }
        if (!entity.isOnGround()) {
            return AUTO_AIRBORNE;
        }
        if (entity.isInWater()) {
            return AUTO_SWIM;
        }
        return state.swingSpeed > 0.02f ? AUTO_WALK : AUTO_IDLE;
    }

    /** EME walk phase: {@code limbSwing · 0.6662 · swingSpeed / 2π} cycles. */
    private static void syncWalkPhase(AnimatedInstance state) {
        if (!AUTO_WALK.equals(state.autoState)) {
            return;
        }
        AnimationController walk = state.controllers.get(AnimationClip.normalize(AUTO_WALK));
        if (walk == null || !walk.automatic()) {
            return;
        }
        float length = walk.clip().length();
        if (length <= 0.0f) {
            return;
        }
        double cycles = state.limbSwing * 0.6662 * Math.max(0.001, state.swingSpeed)
                / (Math.PI * 2.0);
        walk.setTime((float) (cycles % 1.0) * length);
    }

    /** {@code true} when the current automatic clip has finished (or wrapped) its pass. */
    private static boolean autoSwitchDue(AnimatedInstance state) {
        if (state.autoState == null) {
            return true;
        }
        AnimationController current = state.controllers.get(AnimationClip.normalize(state.autoState));
        return current == null || !current.automatic() || current.wrapped()
                || current.stopping() || !current.playing();
    }

    private void startAutoState(UUID renderedModelId, AnimatedInstance state, String name) {
        VirtualModel model = virtualBlockManager.getModel(state.modelKey);
        state.pendingAutoState = null;
        AnimationClip clip = model == null ? null : resolveClip(model, name);
        if (clip == null) {
            // No clip authored for this state: stop the previous automatic layer
            // and remember the state so it is not retried every tick.
            if (state.autoState != null && !state.autoState.equals(name)) {
                stopAutomatic(state);
            }
            state.autoState = name;
            state.dirty = true;
            return;
        }
        if (name.equals(state.autoState)
                && state.controllers.containsKey(AnimationClip.normalize(name))) {
            return;
        }
        stopAutomatic(state);
        startController(renderedModelId, state, clip, AnimationPlayback.defaults(),
                AUTO_PRIORITY, settings.transitionTicks(), settings.transitionTicks(), true);
        state.autoState = name;
    }

    private static void stopAutomatic(AnimatedInstance state) {
        List<String> remove = null;
        for (Map.Entry<String, AnimationController> entry : state.controllers.entrySet()) {
            AnimationController controller = entry.getValue();
            if (!controller.automatic() || controller.stopping()) {
                continue;
            }
            if (controller.fadeOutTicks() > 0) {
                controller.beginStop();
            } else {
                if (remove == null) {
                    remove = new ArrayList<>();
                }
                remove.add(entry.getKey());
            }
        }
        if (remove != null) {
            for (String key : remove) {
                state.controllers.remove(key);
            }
        }
    }

    /**
     * Removes the automatic layer and stops state selection for this rendered
     * model, handing full control back to explicit {@code AnimationApi} calls.
     */
    public void clearAutoState(UUID renderedModelId) {
        AnimatedInstance state = instances.get(renderedModelId);
        if (state == null) {
            return;
        }
        state.autoDisabled = true;
        state.pendingAutoState = null;
        state.autoState = null;
        stopAutomatic(state);
        state.dirty = true;
    }

    // ------------------------------------------------------- async pose pipeline

    /** Moves a finished off-thread frame into the apply queue and drops stale work. */
    private int drainCompletedFrames() {
        PoseFrame frame = completedFrame.getAndSet(null);
        int dropped = 0;
        if (frame != null) {
            if (frame.generation == poseGeneration.get()) {
                for (PoseEntry entry : frame.entries) {
                    pendingApply.add(entry.renderedModelId());
                }
                applyQueue.addLast(frame);
            } else {
                dropped += frame.entries.size() - frame.cursor;
            }
        }
        while (applyQueue.size() > MAX_QUEUED_FRAMES) {
            PoseFrame oldest = applyQueue.removeFirst();
            for (int i = oldest.cursor; i < oldest.entries.size(); i++) {
                pendingApply.remove(oldest.entries.get(i).renderedModelId());
                dropped++;
            }
        }
        return dropped;
    }

    /** Applies queued frames in order, stopping at the per-tick pose write cap. */
    private int applyBudgetedFrames(int budget) {
        if (applyQueue.isEmpty() || budget <= 0) {
            return 0;
        }
        int applied = 0;
        while (!applyQueue.isEmpty() && applied < budget) {
            PoseFrame frame = applyQueue.peekFirst();
            applied += applyFrame(frame, budget - applied);
            if (frame.cursor >= frame.entries.size()) {
                applyQueue.removeFirst();
            } else {
                break; // cap reached; the rest of this frame carries over
            }
        }
        return applied;
    }

    private int applyFrame(PoseFrame frame, int remaining) {
        DisplayTransport transport = virtualBlockManager.displayTransport();
        boolean transportRunning = transport != null && transport.isRunning();
        // ASYNC_SEND gathers every pose write of the pass into one flush then
        // ships each instance's deltas once; ASYNC_COMPUTE flushes per instance.
        List<UUID> deferredFlush = settings.pipeline().deferredFlush() && transportRunning
                ? new ArrayList<>() : null;
        int applied = 0;
        while (frame.cursor < frame.entries.size() && applied < remaining) {
            PoseEntry entry = frame.entries.get(frame.cursor++);
            UUID id = entry.renderedModelId();
            pendingApply.remove(id);
            if (instances.get(id) != entry.state()) {
                continue; // instance replaced or removed since evaluation
            }
            // The cap counts instances, not bindings: one pose push is one unit.
            pushComputedPose(id, entry.state(), deferredFlush != null);
            applied++;
            if (deferredFlush != null) {
                deferredFlush.add(id);
            }
        }
        if (deferredFlush != null && transport != null) {
            for (UUID id : deferredFlush) {
                transport.flushDeltas(id);
            }
        }
        return applied;
    }

    /** Writes a worker-composed pose to the bound displays (main thread only). */
    private void pushComputedPose(UUID renderedModelId, AnimatedInstance state, boolean deferFlush) {
        if (!state.interpolationApplied) {
            state.interpolationApplied = true;
            applyInterpolation(state);
        }
        for (int i = 0; i < state.bindings.size(); i++) {
            applyPoseMatrix(renderedModelId, state.bindings.get(i), state.poseMatrices[i]);
        }
        state.dirty = false;
        if (!deferFlush) {
            DisplayTransport transport = virtualBlockManager.displayTransport();
            if (transport != null && transport.isRunning()) {
                transport.flushDeltas(renderedModelId);
            }
        }
    }

    /** Rewrites the instance's reusable controller snapshots (main thread). */
    private static void sampleControllers(AnimatedInstance state) {
        int size = state.controllers.size();
        if (state.sampleBuffer.length < size) {
            state.sampleBuffer = new ControllerSample[Math.max(size, 4)];
        }
        int i = 0;
        for (AnimationController controller : state.controllers.values()) {
            ControllerSample sample = state.sampleBuffer[i];
            if (sample == null) {
                state.sampleBuffer[i] = sample = new ControllerSample();
            }
            sample.set(controller);
            i++;
        }
        state.sampleCount = i;
    }

    /** Captures one instance's job slot and its controller snapshot. */
    private int addJob(int count, UUID renderedModelId, AnimatedInstance state) {
        if (count == jobIds.length) {
            int size = count * 2;
            jobIds = java.util.Arrays.copyOf(jobIds, size);
            jobStates = java.util.Arrays.copyOf(jobStates, size);
            jobModels = java.util.Arrays.copyOf(jobModels, size);
        }
        jobIds[count] = renderedModelId;
        jobStates[count] = state;
        jobModels[count] = virtualBlockManager.getModel(state.modelKey);
        sampleControllers(state);
        return count + 1;
    }

    private boolean poseWorkerIdle() {
        ThreadPoolExecutor pool = poseExecutor;
        return pool == null || (pool.getActiveCount() == 0 && pool.getQueue().isEmpty());
    }

    /** Schedules one evaluation batch; at most one frame is ever in flight. */
    private void submitPoseJobs(int count) {
        if (count == 0) {
            return;
        }
        ThreadPoolExecutor pool = posePool();
        if (pool.getActiveCount() > 0 || !pool.getQueue().isEmpty()) {
            return; // previous evaluation still running; dirty state retries next tick
        }
        long generation = poseGeneration.get();
        // The arrays are not touched again until this batch completes (submission
        // is skipped while the worker is busy), so no defensive copy is needed.
        UUID[] ids = jobIds;
        AnimatedInstance[] states = jobStates;
        VirtualModel[] models = jobModels;
        try {
            pool.execute(() -> computePoseFrame(generation, ids, states, models, count));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            DebugLogger.debug("Animation pose compute skipped: " + e.getMessage());
        }
    }

    private ThreadPoolExecutor posePool() {
        ThreadPoolExecutor pool = poseExecutor;
        if (pool == null) {
            synchronized (this) {
                pool = poseExecutor;
                if (pool == null) {
                    pool = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                            new ArrayBlockingQueue<>(1),
                            runnable -> {
                                Thread thread = new Thread(runnable, "Mineplus-Pose");
                                thread.setDaemon(true);
                                return thread;
                            },
                            new ThreadPoolExecutor.DiscardPolicy());
                    poseExecutor = pool;
                }
            }
        }
        return pool;
    }

    private void computePoseFrame(long generation, UUID[] ids, AnimatedInstance[] states,
                                  VirtualModel[] models, int count) {
        PoseFrame frame;
        try {
            frame = evaluateFrame(generation, ids, states, models, count);
        } catch (Throwable failure) {
            if (!poseDegraded) {
                poseDegraded = true;
                DebugLogger.warning("Animation pose worker failed ("
                        + failure.getClass().getSimpleName() + ": " + failure.getMessage()
                        + "); falling back to the TICK pipeline for this session.");
            }
            frame = new PoseFrame(-1L, List.of());   // generation -1 is never applied
        }
        completedFrame.set(frame);
    }

    private void stopPoseWorker() {
        ThreadPoolExecutor pool = poseExecutor;
        if (pool != null) {
            poseExecutor = null;
            pool.shutdownNow();
        }
    }

    /** {@code true} when a worker failure switched this session to the TICK pipeline. */
    public boolean posePipelineDegraded() {
        return poseDegraded;
    }

    /** Off-thread: samples the captured controller snapshots and composes each binding's matrix. */
    private PoseFrame evaluateFrame(long generation, UUID[] ids, AnimatedInstance[] states,
                                    VirtualModel[] models, int count) {
        List<PoseEntry> entries = new ArrayList<>(count);
        Matrix4f[] pose = SCRATCH_MATRICES.get();

        for (int job = 0; job < count; job++) {
            AnimatedInstance state = states[job];
            VirtualModel model = models[job];
            int boneCount = model == null ? 1 : Math.max(1, model.bones().size());
            if (boneCount > pose.length) {
                pose = new Matrix4f[Math.max(boneCount, pose.length * 2)];
                SCRATCH_MATRICES.set(pose);
            }
            for (int i = 0; i < boneCount; i++) {
                if (pose[i] == null) pose[i] = new Matrix4f();
            }

            if (model != null) {
                PoseSolver.compose(model, state.sampleBuffer, state.sampleCount, pose);
            } else {
                for (int i = 0; i < boneCount; i++) {
                    pose[i].identity();
                }
            }

            for (int i = 0; i < state.bindings.size(); i++) {
                AnimationBinding binding = state.bindings.get(i);
                Matrix4f out = state.poseMatrices[i];
                out.identity()
                        .translate(state.pivotCorrection)
                        .rotate(state.globalRotation);
                // Reload guard: bindings capture spawn-time bone indices.
                if (binding.boneIndex() >= 0 && binding.boneIndex() < boneCount) {
                    out.mul(pose[binding.boneIndex()]);
                }
                out.mul(binding.restLocal());
            }
            entries.add(new PoseEntry(ids[job], state));
        }
        return new PoseFrame(generation, entries);
    }

    private void startAutoplay(UUID renderedModelId, AnimatedInstance state) {
        VirtualModel model = virtualBlockManager.getModel(state.modelKey);
        if (model == null) {
            return;
        }
        List<String> wanted = null;
        MultiBlockInstance instance = bridge != null
                ? bridge.instanceForRenderedModel(renderedModelId) : null;
        if (bridge != null && instance != null) {
            List<String> declared = bridge.declaredAnimations(instance);
            if (declared != null && !declared.isEmpty()) {
                wanted = declared;
            }
        }
        if (wanted == null) {
            List<String> metaAutoplay = virtualBlockManager.getModelMeta(state.modelKey).autoplay();
            if (!metaAutoplay.isEmpty()) {
                wanted = metaAutoplay;
            }
        }
        if (wanted == null) {
            return;
        }
        for (String name : wanted) {
            AnimationClip clip = resolveClip(model, name);
            if (clip == null) {
                DebugLogger.warning("Animation autoplay: clip '" + name
                        + "' not found in model '" + state.modelKey + "'.");
                continue;
            }
            startController(renderedModelId, state, clip, AnimationPlayback.defaults());
        }
    }

    private AnimationController startController(
            UUID renderedModelId,
            AnimatedInstance state,
            AnimationClip clip,
            AnimationPlayback playback
    ) {
        return startController(renderedModelId, state, clip, playback, 0, 0, 0, false);
    }

    private AnimationController startController(
            UUID renderedModelId,
            AnimatedInstance state,
            AnimationClip clip,
            AnimationPlayback playback,
            int priority,
            int fadeInTicks,
            int fadeOutTicks,
            boolean automatic
    ) {
        AnimationController controller = new AnimationController(clip);
        controller.setTime(playback.startTime());
        controller.setSpeed(playback.speed());
        controller.setLoopOverride(playback.loopOverride());
        controller.setPriority(priority);
        controller.setFades(fadeInTicks, fadeOutTicks);
        controller.setAutomatic(automatic);
        state.controllers.put(AnimationClip.normalize(clip.name()), controller);
        state.dirty = true;
        fireStart(renderedModelId, clip.name());
        return controller;
    }

    private int pushPose(UUID renderedModelId, AnimatedInstance state) {
        VirtualModel model = virtualBlockManager.getModel(state.modelKey);
        int boneCount = model == null ? 1 : Math.max(1, model.bones().size());

        Matrix4f[] pose = SCRATCH_MATRICES.get();
        if (boneCount > pose.length) {
            pose = new Matrix4f[Math.max(boneCount, pose.length * 2)];
            SCRATCH_MATRICES.set(pose);
        }
        for (int i = 0; i < boneCount; i++) {
            if (pose[i] == null) pose[i] = new Matrix4f();
        }

        sampleControllers(state);
        if (model != null) {
            PoseSolver.compose(model, state.sampleBuffer, state.sampleCount, pose);
        } else {
            for (int i = 0; i < boneCount; i++) {
                pose[i].identity();
            }
        }

        if (!state.interpolationApplied) {
            state.interpolationApplied = true;
            applyInterpolation(state);
        }

        Matrix4f out = SCRATCH_OUT.get();
        int applied = 0;
        for (AnimationBinding binding : state.bindings) {
            out.identity()
                    .translate(state.pivotCorrection)
                    .rotate(state.globalRotation);
            // Reload guard: bindings capture the spawn-time bone indices; a
            // reloaded model could theoretically have fewer bones.
            if (binding.boneIndex() >= 0 && binding.boneIndex() < boneCount) {
                out.mul(pose[binding.boneIndex()]);
            }
            out.mul(binding.restLocal());
            applyPoseMatrix(renderedModelId, binding, out);
            applied++;
        }

        // Ship what was just pushed: updateTransform only dirties the pooled
        // displays for animated instances, so without this broadcast their pose
        // deltas never reach any viewer.
        DisplayTransport transport = virtualBlockManager.displayTransport();
        if (transport != null && transport.isRunning()) {
            transport.flushDeltas(renderedModelId);
        }
        return applied;
    }

    /**
     * Pushes one composed bone matrix to the bound display. Through the display
     * transport the write is dirty-tracked per viewer (only FULL-tier viewers
     * receive animation frames; STATIC viewers are frozen by design), bundled
     * into one packet per player per tick; the legacy path writes the entity
     * directly and vanilla tracking streams it.
     */
    private void applyPoseMatrix(UUID renderedModelId, AnimationBinding binding, Matrix4f matrix) {
        DisplayTransport transport = virtualBlockManager.displayTransport();
        if (transport != null && transport.isRunning()) {
            PooledDisplay pooled = transport.pool().byUniqueId(binding.entityId());
            if (pooled != null && pooled.isInUse()) {
                transport.updateTransform(renderedModelId, pooled, matrix,
                        settings.effectiveInterpolationTicks());
                return;
            }
        }
        BlockDisplay display = (BlockDisplay) Bukkit.getEntity(binding.entityId());
        if (display != null && display.isValid()) {
            display.setTransformationMatrix(matrix);
        }
    }

    private void applyInterpolation(AnimatedInstance state) {
        int ticks = settings.effectiveInterpolationTicks();
        for (AnimationBinding binding : state.bindings) {
            applyToDisplay(binding.entityId(), display -> {
                display.setInterpolationDuration(ticks);
                display.setInterpolationDelay(0);
            });
        }
    }

    /** Display write helper routing through the transport pool or the legacy entity. */
    private void applyToDisplay(UUID displayId, java.util.function.Consumer<BlockDisplay> write) {
        DisplayTransport transport = virtualBlockManager.displayTransport();
        if (transport != null && transport.isRunning()) {
            PooledDisplay pooled = transport.pool().byUniqueId(displayId);
            if (pooled != null && pooled.isInUse()) {
                write.accept(pooled.asBlockDisplay());
                return;
            }
        }
        BlockDisplay display = (BlockDisplay) Bukkit.getEntity(displayId);
        if (display != null && display.isValid()) {
            write.accept(display);
        }
    }

    // ---- rendered-model-keyed control surface (used by AnimationApi) ----

    public boolean play(UUID renderedModelId, String animation, AnimationPlayback playback) {
        return play(renderedModelId, animation, playback, 0, 0, 0);
    }

    /** Plays a clip at an explicit priority (see {@link AnimationApi#playAnimation(UUID, String, AnimationPlayback, int)}). */
    public boolean play(UUID renderedModelId, String animation, AnimationPlayback playback, int priority) {
        return play(renderedModelId, animation, playback, priority, 0, 0);
    }

    /**
     * Plays a clip at an explicit priority with fade in/out windows. Higher
     * priorities win per bone; equal priorities still compose additively, so a
     * call with priority {@code 0} and no fades behaves exactly like the
     * historical {@code playAnimation}.
     */
    public boolean play(UUID renderedModelId, String animation, AnimationPlayback playback,
                        int priority, int fadeInTicks, int fadeOutTicks) {
        if (!settings.enabled()) {
            return false;
        }
        AnimatedInstance state = instance(renderedModelId);
        if (state == null) {
            return false;
        }
        AnimationClip clip = resolveClip(virtualBlockManager.getModel(state.modelKey), animation);
        if (clip == null) {
            return false;
        }
        startController(renderedModelId, state, clip,
                playback == null ? AnimationPlayback.defaults() : playback,
                priority, fadeInTicks, fadeOutTicks, false);
        return true;
    }

    public boolean stop(UUID renderedModelId, String animation) {
        AnimatedInstance state = instance(renderedModelId);
        if (state == null) {
            return false;
        }
        String key = AnimationClip.normalize(animation);
        AnimationController controller = state.controllers.get(key);
        if (controller == null) {
            return false;
        }
        if (controller.fadeOutTicks() > 0) {
            controller.beginStop();
        } else {
            state.controllers.remove(key);
        }
        state.dirty = true;
        return true;
    }

    public boolean pause(UUID renderedModelId, String animation) {
        return mutateController(renderedModelId, animation, controller -> controller.setPlaying(false));
    }

    public boolean resume(UUID renderedModelId, String animation) {
        return mutateController(renderedModelId, animation, controller -> controller.setPlaying(true));
    }

    private interface ControllerMutation {
        void apply(AnimationController controller);
    }

    private boolean mutateController(UUID renderedModelId, String animation, ControllerMutation mutation) {
        AnimatedInstance state = instance(renderedModelId);
        if (state == null) {
            return false;
        }
        AnimationController controller = state.controllers.get(AnimationClip.normalize(animation));
        if (controller == null) {
            return false;
        }
        mutation.apply(controller);
        state.dirty = true;
        return true;
    }

    /**
     * One-shot playback matching the selector: matched clips restart from
     * {@code t=0} with their loop mode forced to {@link LoopMode#ONCE}; a bone
     * selector restricts playback to the matched bones' tracks.
     */
    public boolean trigger(UUID renderedModelId, AnimationSelector selector) {
        if (!settings.enabled() || selector == null) {
            return false;
        }
        AnimatedInstance state = instance(renderedModelId);
        if (state == null) {
            return false;
        }
        VirtualModel model = virtualBlockManager.getModel(state.modelKey);
        if (model == null) {
            return false;
        }

        List<AnimationClip> targets = new ArrayList<>();
        List<Integer> boneFilter = null;
        switch (selector.kind()) {
            case ANIMATION -> {
                AnimationClip clip = resolveClip(model, selector.target());
                if (clip != null) {
                    targets.add(clip);
                }
            }
            case BONE -> {
                boneFilter = new ArrayList<>();
                for (int i = 0; i < model.bones().size(); i++) {
                    if (matches(model.bones().get(i).name(), selector.target())) {
                        boneFilter.add(i);
                    }
                }
                if (boneFilter.isEmpty()) {
                    return false;
                }
                for (AnimationClip clip : model.animations()) {
                    for (String boneName : clip.animators().keySet()) {
                        if (boneFilter.contains(model.boneIndex(boneName))) {
                            targets.add(clip);
                            break;
                        }
                    }
                }
            }
            default -> targets.addAll(model.animations());
        }
        if (targets.isEmpty()) {
            return false;
        }

        for (AnimationClip clip : targets) {
            AnimationController controller =
                    startController(renderedModelId, state, clip, AnimationPlayback.defaults());
            controller.setTime(0.0f);
            controller.setLoopOverride(LoopMode.ONCE);
            if (boneFilter != null) {
                for (int i = 0; i < model.bones().size(); i++) {
                    controller.setBoneEnabled(i, boneFilter.contains(i));
                }
            }
        }
        return true;
    }

    /**
     * Enables or disables whatever the selector addresses: animation selectors
     * pause/resume the matched controllers, bone selectors gate the matched
     * bones' contribution inside every controller animating them.
     */
    public boolean setEnabled(UUID renderedModelId, AnimationSelector selector, boolean enabled) {
        if (selector == null) {
            return false;
        }
        AnimatedInstance state = instance(renderedModelId);
        if (state == null) {
            return false;
        }
        VirtualModel model = virtualBlockManager.getModel(state.modelKey);
        if (model == null) {
            return false;
        }

        boolean affected = false;
        switch (selector.kind()) {
            case ANIMATION -> {
                AnimationController controller = state.controllers.get(
                        AnimationClip.normalize(selector.target()));
                if (controller != null) {
                    controller.setPlaying(enabled);
                    affected = true;
                }
            }
            case BONE -> {
                for (AnimationController controller : state.controllers.values()) {
                    for (String boneName : controller.clip().animators().keySet()) {
                        int boneIndex = model.boneIndex(boneName);
                        if (boneIndex >= 0 && matches(model.bones().get(boneIndex).name(), selector.target())) {
                            controller.setBoneEnabled(boneIndex, enabled);
                            affected = true;
                        }
                    }
                }
            }
            default -> {
                for (AnimationController controller : state.controllers.values()) {
                    controller.setPlaying(enabled);
                }
                affected = !state.controllers.isEmpty();
            }
        }
        if (affected) {
            state.dirty = true;
        }
        return affected;
    }

    public List<String> animations(UUID renderedModelId) {
        AnimatedInstance state = instance(renderedModelId);
        if (state == null) {
            return List.of();
        }
        VirtualModel model = virtualBlockManager.getModel(state.modelKey);
        if (model == null) {
            return List.of();
        }
        return model.animations().stream().map(AnimationClip::name).toList();
    }

    public AnimationState state(UUID renderedModelId, String animation) {
        AnimatedInstance state = instance(renderedModelId);
        if (state == null) {
            return null;
        }
        AnimationController controller = state.controllers.get(AnimationClip.normalize(animation));
        return controller == null ? null : controller.snapshot();
    }

    public List<String> bones(UUID renderedModelId) {
        AnimatedInstance state = instance(renderedModelId);
        if (state == null) {
            return List.of();
        }
        VirtualModel model = virtualBlockManager.getModel(state.modelKey);
        if (model == null) {
            return List.of();
        }
        return model.bones().stream().map(VirtualBone::name).toList();
    }

    private AnimatedInstance instance(UUID renderedModelId) {
        AnimatedInstance state = instances.get(renderedModelId);
        if (state != null) {
            return state;
        }
        VirtualBlockManager.ActiveVirtualBlock block =
                virtualBlockManager.activeBlocksView().get(renderedModelId);
        if (block == null || block.animationBindings().isEmpty()) {
            return null;
        }
        state = createInstance(renderedModelId, block);
        if (state != null) {
            state.autoplayResolved = true;
            instances.put(renderedModelId, state);
        }
        return state;
    }

    private AnimationClip resolveClip(VirtualModel model, String name) {
        if (model == null || name == null) {
            return null;
        }
        String normalized = AnimationClip.normalize(name);
        for (AnimationClip clip : model.animations()) {
            if (AnimationClip.normalize(clip.name()).equals(normalized)) {
                return clip;
            }
        }
        return null;
    }

    private boolean matches(String boneName, String target) {
        return boneName != null && target != null
                && boneName.trim().equalsIgnoreCase(target.trim());
    }

    private void fireStart(UUID renderedModelId, String animation) {
        if (bridge == null) {
            return;
        }
        MultiBlockInstance instance = bridge.instanceForRenderedModel(renderedModelId);
        if (instance != null) {
            bridge.onAnimationStart(instance, animation);
        }
    }

    private void fireComplete(UUID renderedModelId, String animation) {
        if (bridge == null) {
            return;
        }
        MultiBlockInstance instance = bridge.instanceForRenderedModel(renderedModelId);
        if (instance != null) {
            bridge.onAnimationComplete(instance, animation);
        }
    }
}
