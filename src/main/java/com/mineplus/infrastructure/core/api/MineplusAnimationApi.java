package com.mineplus.infrastructure.core.api;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import com.mineplus.infrastructure.core.multiblock.registry.MultiBlockRegistry;
import com.mineplus.infrastructure.virtual.animation.AnimationPlayback;
import com.mineplus.infrastructure.virtual.animation.AnimationSelector;
import com.mineplus.infrastructure.virtual.animation.AnimationState;
import com.mineplus.infrastructure.virtual.animation.ModelAnimationManager;
import java.util.List;
import java.util.UUID;

public final class MineplusAnimationApi implements AnimationApi {

    private final MultiBlockRegistry registry;
    private final ModelAnimationManager animationManager;

    public MineplusAnimationApi(MultiBlockRegistry registry, ModelAnimationManager animationManager) {
        this.registry = registry;
        this.animationManager = animationManager;
    }

    @Override
    public boolean playAnimation(UUID instanceId, String animation, AnimationPlayback playback) {
        UUID renderedModelId = resolve(instanceId);
        return renderedModelId != null && animationManager.play(renderedModelId, animation, playback);
    }

    @Override
    public boolean playAnimation(UUID instanceId, String animation) {
        return playAnimation(instanceId, animation, AnimationPlayback.defaults());
    }

    @Override
    public boolean stopAnimation(UUID instanceId, String animation) {
        UUID renderedModelId = resolve(instanceId);
        return renderedModelId != null && animationManager.stop(renderedModelId, animation);
    }

    @Override
    public boolean pauseAnimation(UUID instanceId, String animation) {
        UUID renderedModelId = resolve(instanceId);
        return renderedModelId != null && animationManager.pause(renderedModelId, animation);
    }

    @Override
    public boolean resumeAnimation(UUID instanceId, String animation) {
        UUID renderedModelId = resolve(instanceId);
        return renderedModelId != null && animationManager.resume(renderedModelId, animation);
    }

    @Override
    public boolean triggerAnimation(UUID instanceId, AnimationSelector selector) {
        UUID renderedModelId = resolve(instanceId);
        return renderedModelId != null && animationManager.trigger(renderedModelId, selector);
    }

    @Override
    public boolean setAnimationEnabled(UUID instanceId, AnimationSelector selector, boolean enabled) {
        UUID renderedModelId = resolve(instanceId);
        return renderedModelId != null && animationManager.setEnabled(renderedModelId, selector, enabled);
    }

    @Override
    public List<String> getAnimations(UUID instanceId) {
        UUID renderedModelId = resolve(instanceId);
        return renderedModelId == null ? List.of() : animationManager.animations(renderedModelId);
    }

    @Override
    public List<String> getBones(UUID instanceId) {
        UUID renderedModelId = resolve(instanceId);
        return renderedModelId == null ? List.of() : animationManager.bones(renderedModelId);
    }

    @Override
    public AnimationState getAnimationState(UUID instanceId, String animation) {
        UUID renderedModelId = resolve(instanceId);
        return renderedModelId == null ? null : animationManager.state(renderedModelId, animation);
    }

    private UUID resolve(UUID instanceId) {
        if (instanceId == null) {
            return null;
        }
        MultiBlockInstance instance = registry.getInstance(instanceId);
        if (instance != null && instance.renderedModelId() != null) {
            return instance.renderedModelId();
        }
        // Raw debug-spawned models have no multiblock instance: accept the
        // rendered model id directly.
        return instanceId;
    }
}
