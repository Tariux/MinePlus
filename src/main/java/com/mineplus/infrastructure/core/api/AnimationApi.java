package com.mineplus.infrastructure.core.api;

import com.mineplus.infrastructure.virtual.animation.AnimationPlayback;
import com.mineplus.infrastructure.virtual.animation.AnimationSelector;
import com.mineplus.infrastructure.virtual.animation.AnimationState;
import java.util.List;
import java.util.UUID;

/**
 * Selector-based animation control for rendered instances — the "external hook
 * interface" of the animation engine.
 *
 * <p>All methods accept a multiblock instance id (resolved to its rendered
 * model) or — for raw debug-spawned models — the rendered model id itself.
 * Clip and bone names come from the {@code .bbmodel} file (animation names and
 * outliner group names).
 */
public interface AnimationApi {

    /**
     * Plays an animation defined in the model file. Replays from the given
     * start time; respects the clip's loop mode unless overridden.
     *
     * @return {@code true} if the instance exists and the clip was found
     */
    boolean playAnimation(UUID instanceId, String animation, AnimationPlayback playback);

    /** Shorthand for {@link #playAnimation(UUID, String, AnimationPlayback)} with defaults. */
    boolean playAnimation(UUID instanceId, String animation);

    /**
     * Stops a playing animation and returns affected bones to their rest pose.
     *
     * @return {@code true} if a controller was removed
     */
    boolean stopAnimation(UUID instanceId, String animation);

    /** Freezes a playing animation at its current time. */
    boolean pauseAnimation(UUID instanceId, String animation);

    /** Resumes a paused animation. */
    boolean resumeAnimation(UUID instanceId, String animation);

    /**
     * One-shot trigger matching the selector: clips restart from {@code t=0}
     * forced to play once; a bone selector plays only the matched bones' tracks.
     * This is the granular "trigger a specific part's movement" entry point.
     *
     * @return {@code true} if at least one clip was triggered
     */
    boolean triggerAnimation(UUID instanceId, AnimationSelector selector);

    /**
     * Enables or disables whatever the selector addresses: animation selectors
     * pause/resume controllers, bone selectors gate the matched bones inside
     * every controller animating them, {@code all()} addresses everything.
     *
     * @return {@code true} if anything matched
     */
    boolean setAnimationEnabled(UUID instanceId, AnimationSelector selector, boolean enabled);

    /** Clip names available on the instance's model. */
    List<String> getAnimations(UUID instanceId);

    /** Bone (outliner group) names available on the instance's model. */
    List<String> getBones(UUID instanceId);

    /** Playback snapshot of one animation, or {@code null} when not playing. */
    AnimationState getAnimationState(UUID instanceId, String animation);
}
