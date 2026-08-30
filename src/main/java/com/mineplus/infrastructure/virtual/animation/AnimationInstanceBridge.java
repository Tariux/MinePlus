package com.mineplus.infrastructure.virtual.animation;

import com.mineplus.infrastructure.core.multiblock.MultiBlockInstance;
import java.util.List;
import java.util.UUID;

/**
 * Bridge between the animation runtime and the multiblock lifecycle layer.
 * Implemented by {@code MultiBlockLifecycleManager}; wired by the engine after
 * construction so neither manager needs the other as a constructor dependency.
 */
public interface AnimationInstanceBridge {

    /** Resolves the multiblock instance owning a rendered model id, or null. */
    MultiBlockInstance instanceForRenderedModel(UUID renderedModelId);

    /**
     * Autoplay declarations of the instance's current level
     * ({@code levels.<n>.animations} in the multiblock JSON); empty when absent.
     */
    List<String> declaredAnimations(MultiBlockInstance instance);

    /** Dispatches {@code onAnimationStart} on the type's hook (isolated). */
    void onAnimationStart(MultiBlockInstance instance, String animation);

    /** Dispatches {@code onAnimationComplete} on the type's hook (isolated). */
    void onAnimationComplete(MultiBlockInstance instance, String animation);
}
