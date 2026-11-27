package com.mineplus.examples.collision;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.collision.CollisionSpec;
import com.mineplus.infrastructure.collision.fit.FitResult;
import com.mineplus.infrastructure.collision.source.DisplayTransform;
import java.io.IOException;

/**
 * Sample 4 — a machine scaled down to 0.75.
 *
 * <p>The display transform is part of the spec, so occupancy is rasterised at
 * the scale the player actually sees. Without it the fit would describe the
 * unscaled model and the collision would be too large.</p>
 */
public final class CrateScaledSample {

    private CrateScaledSample() {
    }

    public static FitResult bake(PluginContext context, String modelKey) throws IOException {
        CollisionSpec spec = CollisionSpec.builder(modelKey)
                .auto(ModelFiles.read(context, modelKey))
                .display(DisplayTransform.scaled(0.75))
                .profile("DEFAULT")
                .build();
        return context.collisionService().bake(spec, ModelFiles.resolver(context));
    }
}
