package com.mineplus.examples.collision;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.collision.CollisionSpec;
import com.mineplus.infrastructure.collision.fit.FitResult;
import java.io.IOException;

/**
 * Sample 1 — a chair fitted from its own model geometry.
 *
 * <p>{@code AUTO} rasterises the model, {@code FURNITURE} weights a solid,
 * non-walkable body, and {@code bake} stores the plan so placement never fits
 * again. Start here for any furniture whose shape you trust.</p>
 */
public final class ChairFurnitureSample {

    private ChairFurnitureSample() {
    }

    public static FitResult bake(PluginContext context, String modelKey) throws IOException {
        CollisionSpec spec = CollisionSpec.builder(modelKey)
                .auto(ModelFiles.read(context, modelKey))
                .profile("FURNITURE")
                .yaw(0)
                .build();
        return context.collisionService().bake(spec, ModelFiles.resolver(context));
    }
}
