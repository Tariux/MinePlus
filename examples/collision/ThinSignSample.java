package com.mineplus.examples.collision;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.collision.CollisionSpec;
import com.mineplus.infrastructure.collision.fit.FitResult;
import java.io.IOException;

/**
 * Sample 3 — a thin wall sign.
 *
 * <p>{@code FILL} with a one-block box and the {@code THIN} profile keeps the
 * collision at plate thickness; the fitter is told extra volume is expensive,
 * so it prefers a state whose shape is a plane rather than a full cube.</p>
 */
public final class ThinSignSample {

    private ThinSignSample() {
    }

    public static FitResult bake(PluginContext context, String modelKey) throws IOException {
        CollisionSpec spec = CollisionSpec.builder(modelKey)
                .fill(1, 1, 1)
                .profile("THIN")
                .thinPlanesAsSlabs(true)
                .build();
        return context.collisionService().bake(spec, ModelFiles.resolver(context));
    }
}
