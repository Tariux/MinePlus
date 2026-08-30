package com.mineplus.infrastructure.virtual.animation;

import java.util.UUID;
import org.joml.Matrix4f;

/**
 * One spawned display entity bound to its animating bone. {@code restLocal} is
 * the entity's model-space matrix without the placement rotation — the runtime
 * composes {@code T(pivotFix)·R·boneDelta(t)·restLocal} every update.
 */
public record AnimationBinding(
        int boneIndex,
        UUID entityId,
        Matrix4f restLocal
) {

    public AnimationBinding {
        boneIndex = Math.max(-1, boneIndex);
        restLocal = new Matrix4f(restLocal);
    }
}
