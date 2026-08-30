package com.mineplus.infrastructure.virtual.animation;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.util.DebugLogger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.plugin.java.JavaPlugin;
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
    private int taskId = -1;
    private final Map<UUID, AnimatedInstance> instances = new HashMap<>();

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
        if (taskId != -1) {
            stop();
            start();
        }
    }

    public void start() {
        if (!settings.enabled() || taskId != -1) {
            return;
        }
        taskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(
                plugin,
                this::tick,
                Math.max(1, settings.tickIntervalTicks()),
                Math.max(1, settings.tickIntervalTicks())
        );
        DebugLogger.info("ModelAnimationManager: animation tick scheduled (interval="
                + settings.tickIntervalTicks() + " ticks).");
    }

    public void stop() {
        if (taskId != -1) {
            plugin.getServer().getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        instances.clear();
    }

    private void tick() {
        Map<UUID, VirtualBlockManager.ActiveVirtualBlock> view = virtualBlockManager.activeBlocksView();
        instances.keySet().retainAll(view.keySet());

        for (Map.Entry<UUID, VirtualBlockManager.ActiveVirtualBlock> entry : view.entrySet()) {
            VirtualBlockManager.ActiveVirtualBlock block = entry.getValue();
            if (block.animationBindings().isEmpty()) {
                continue;
            }
            AnimatedInstance state = instances.get(entry.getKey());
            if (state == null) {
                state = createInstance(entry.getKey(), block);
                if (state == null) {
                    continue;
                }
                instances.put(entry.getKey(), state);
            }
            stepInstance(entry.getKey(), state);
        }
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
            pushPose(state);
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

    private void pushPose(AnimatedInstance state) {
        VirtualModel model = virtualBlockManager.getModel(state.modelKey);
        if (model == null) {
            return;
        }

        int boneCount = model.bones().size();
        AnimationEvaluator.BoneDelta[] deltas = new AnimationEvaluator.BoneDelta[boneCount];
        for (AnimationController controller : state.controllers.values()) {
            for (Map.Entry<String, AnimationClip.BoneAnimation> entry : controller.clip().animators().entrySet()) {
                int boneIndex = model.boneIndex(entry.getKey());
                if (boneIndex < 0 || !controller.isBoneEnabled(boneIndex)) {
                    continue;
                }
                if (deltas[boneIndex] == null) {
                    deltas[boneIndex] = new AnimationEvaluator.BoneDelta();
                }
                AnimationEvaluator.accumulate(entry.getValue(), controller.time(), deltas[boneIndex]);
            }
        }

        Matrix4f[] pose = new Matrix4f[boneCount];
        for (int i = 0; i < boneCount; i++) {
            if (deltas[i] == null) {
                deltas[i] = new AnimationEvaluator.BoneDelta();
            }
        }
        AnimationEvaluator.composePose(model, deltas, pose);

        if (!state.interpolationApplied) {
            state.interpolationApplied = true;
            applyInterpolation(state);
        }

        Matrix4f out = new Matrix4f();
        for (AnimationBinding binding : state.bindings) {
            out.identity()
                    .translate(state.pivotCorrection)
                    .rotate(state.globalRotation);
            // Reload guard: bindings capture the spawn-time bone indices; a
            // reloaded model could theoretically have fewer bones.
            Matrix4f delta = binding.boneIndex() >= 0 && binding.boneIndex() < pose.length
                    ? pose[binding.boneIndex()] : null;
            if (delta != null) {
                out.mul(delta);
            }
            out.mul(binding.restLocal());
            BlockDisplay display = (BlockDisplay) Bukkit.getEntity(binding.entityId());
            if (display != null && display.isValid()) {
                display.setTransformationMatrix(out);
            }
        }
    }

    private void applyInterpolation(AnimatedInstance state) {
        int ticks = settings.effectiveInterpolationTicks();
        for (AnimationBinding binding : state.bindings) {
            BlockDisplay display = (BlockDisplay) Bukkit.getEntity(binding.entityId());
            if (display != null && display.isValid()) {
                display.setInterpolationDuration(ticks);
                display.setInterpolationDelay(0);
            }
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
