package com.mineplus.infrastructure.virtual.animation;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.infrastructure.virtual.display.DisplayTransport;
import com.mineplus.infrastructure.virtual.display.pool.PooledDisplay;
import com.mineplus.util.DebugLogger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
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
    /**
     * Animated-instance state. Concurrent because hooks (play/stop/pause via
     * {@code AnimationApi}) can arrive from any thread while the tick runs on the
     * global scheduler (Folia) or the main thread; the map itself must never race.
     */
    private final Map<UUID, AnimatedInstance> instances = new ConcurrentHashMap<>();

    // JOML object reuse (zero allocation in the animation loop); grown on demand
    // for models with more bones than the initial capacity.
    private static final int SCRATCH_INITIAL_BONES = 256;
    private static final ThreadLocal<Matrix4f[]> SCRATCH_MATRICES = ThreadLocal.withInitial(() -> new Matrix4f[SCRATCH_INITIAL_BONES]);
    private static final ThreadLocal<AnimationEvaluator.BoneDelta[]> SCRATCH_DELTAS = ThreadLocal.withInitial(() -> new AnimationEvaluator.BoneDelta[SCRATCH_INITIAL_BONES]);
    private static final ThreadLocal<Matrix4f> SCRATCH_OUT = ThreadLocal.withInitial(Matrix4f::new);

    /** Immutable per-tick snapshot of one viewer: position plus normalized look direction. */
    private record ViewerPos(double x, double y, double z, float lookX, float lookY, float lookZ) {
    }

    private static final class AnimatedInstance {
        final String modelKey;
        final List<AnimationBinding> bindings;
        final Quaternionf globalRotation;
        final Vector3f pivotCorrection;
        boolean autoplayResolved;
        boolean interpolationApplied;
        boolean dirty = true;
        final Map<String, AnimationController> controllers = new LinkedHashMap<>();

        AnimatedInstance(String modelKey, List<AnimationBinding> bindings,
                         Quaternionf globalRotation, Vector3f pivotCorrection) {
            this.modelKey = modelKey;
            this.bindings = List.copyOf(bindings);
            this.globalRotation = new Quaternionf(globalRotation);
            this.pivotCorrection = new Vector3f(pivotCorrection);
        }
    }

    public ModelAnimationManager(JavaPlugin plugin, VirtualBlockManager virtualBlockManager) {
        this.plugin = plugin;
        this.virtualBlockManager = virtualBlockManager;
    }

    public void bindBridge(AnimationInstanceBridge bridge) {
        this.bridge = bridge;
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
        int interval = Math.max(1, settings.tickIntervalTicks());
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            foliaTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> tick(), interval, interval);
        } catch (ClassNotFoundException e) {
            tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
        }
        DebugLogger.info("ModelAnimationManager: animation tick scheduled (interval="
                + settings.tickIntervalTicks() + " ticks).");
    }

    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        if (foliaTask != null) {
            foliaTask.cancel();
            foliaTask = null;
        }
        instances.clear();
    }

    private void tick() {
        Map<UUID, VirtualBlockManager.ActiveVirtualBlock> view = virtualBlockManager.activeBlocksView();
        instances.keySet().retainAll(view.keySet());

        DisplayTransport transport = virtualBlockManager.displayTransport();
        boolean transportRunning = transport != null && transport.isRunning();
        // LOD gates only apply through the transport; the legacy spawned-entity
        // path is ranged by vanilla tracking and always animates.
        double maxDistSq = transportRunning ? transport.settings().lodFullRangeSq() : 0.0;
        // Per-tick viewer snapshot, built lazily on the first animated instance:
        // one position read per player per tick instead of one per player per instance.
        Map<World, List<ViewerPos>> viewersByWorld = null;

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
                stepInstance(entry.getKey(), state);
            }
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

    private void stepInstance(UUID renderedModelId, AnimatedInstance state) {
        if (!state.autoplayResolved) {
            state.autoplayResolved = true;
            if (settings.autoplay()) {
                startAutoplay(renderedModelId, state);
            }
        }

        boolean advanced = false;
        List<AnimationController> completed = null;
        float deltaTime = settings.tickIntervalTicks() / 20.0f;
        for (AnimationController controller : state.controllers.values()) {
            if (!controller.playing()) {
                continue;
            }
            advanced = true;
            if (controller.advance(deltaTime * controller.speed())) {
                if (completed == null) {
                    completed = new ArrayList<>();
                }
                completed.add(controller);
            }
        }
        if (completed != null) {
            for (AnimationController controller : completed) {
                String name = controller.clip().name();
                // ONCE returns affected bones to rest (controller removed);
                // HOLD freezes on the final frame (controller kept, clamped).
                if (controller.effectiveLoop() == LoopMode.ONCE) {
                    state.controllers.remove(AnimationClip.normalize(name));
                }
                state.dirty = true;
                fireComplete(renderedModelId, name);
            }
        }

        if (advanced || state.dirty) {
            pushPose(renderedModelId, state);
            state.dirty = false;
        }
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
        AnimationController controller = new AnimationController(clip);
        controller.setTime(playback.startTime());
        controller.setSpeed(playback.speed());
        controller.setLoopOverride(playback.loopOverride());
        state.controllers.put(AnimationClip.normalize(clip.name()), controller);
        state.dirty = true;
        fireStart(renderedModelId, clip.name());
        return controller;
    }

    private void pushPose(UUID renderedModelId, AnimatedInstance state) {
        VirtualModel model = virtualBlockManager.getModel(state.modelKey);
        int boneCount = model == null ? 1 : Math.max(1, model.bones().size());

        Matrix4f[] pose = SCRATCH_MATRICES.get();
        AnimationEvaluator.BoneDelta[] deltas = SCRATCH_DELTAS.get();
        if (boneCount > pose.length) {
            pose = new Matrix4f[boneCount];
            deltas = new AnimationEvaluator.BoneDelta[boneCount];
            SCRATCH_MATRICES.set(pose);
            SCRATCH_DELTAS.set(deltas);
        }

        for (int i = 0; i < boneCount; i++) {
            if (deltas[i] == null) deltas[i] = new AnimationEvaluator.BoneDelta();
            else deltas[i].reset();
            if (pose[i] == null) pose[i] = new Matrix4f();
            else pose[i].identity();
        }

        if (model != null) {
            for (AnimationController controller : state.controllers.values()) {
                for (Map.Entry<String, AnimationClip.BoneAnimation> entry : controller.clip().animators().entrySet()) {
                    int boneIndex = model.boneIndex(entry.getKey());
                    if (boneIndex >= 0 && boneIndex < boneCount && controller.isBoneEnabled(boneIndex)) {
                        AnimationEvaluator.accumulate(entry.getValue(), controller.time(), deltas[boneIndex]);
                    }
                }
            }
            AnimationEvaluator.composePose(model, deltas, pose);
        }

        if (!state.interpolationApplied) {
            state.interpolationApplied = true;
            applyInterpolation(state);
        }

        Matrix4f out = SCRATCH_OUT.get();
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
        }

        // Ship what was just pushed: updateTransform only dirties the pooled
        // displays for animated instances, so without this broadcast their pose
        // deltas never reach any viewer.
        DisplayTransport transport = virtualBlockManager.displayTransport();
        if (transport != null && transport.isRunning()) {
            transport.flushDeltas(renderedModelId);
        }
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
                playback == null ? AnimationPlayback.defaults() : playback);
        return true;
    }

    public boolean stop(UUID renderedModelId, String animation) {
        AnimatedInstance state = instance(renderedModelId);
        if (state == null) {
            return false;
        }
        boolean removed = state.controllers.remove(AnimationClip.normalize(animation)) != null;
        if (removed) {
            state.dirty = true;
        }
        return removed;
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
