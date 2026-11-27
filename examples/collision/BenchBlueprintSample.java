package com.mineplus.examples.collision;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.collision.CollisionSpec;
import com.mineplus.infrastructure.collision.fit.FitResult;
import java.io.IOException;

/**
 * Sample 2 — a bench whose seat is a walkable surface but whose back blocks.
 *
 * <p>{@code HYBRID} keeps the model's own volume for the legs and back, then
 * lets the blueprint override the seat row with {@code TOP_SURFACE} so the
 * fitter is forced to pick a block with a flat top there.</p>
 */
public final class BenchBlueprintSample {

    private static final String SEAT = """
            {
              "schema": 1,
              "size": [3, 1, 1],
              "regions": [
                {"type": "fill", "from": [0, 0, 0], "to": [2, 0, 0], "directive": "TOP_SURFACE"},
                {"type": "box",  "from": [0, 0, 0], "to": [2, 0, 0], "directive": "TOP_SURFACE"}
              ]
            }
            """;

    private BenchBlueprintSample() {
    }

    public static FitResult bake(PluginContext context, String modelKey) throws IOException {
        CollisionSpec spec = CollisionSpec.builder(modelKey)
                .hybrid(ModelFiles.read(context, modelKey), SEAT)
                .profile("WALKABLE")
                .resolution(16)
                .build();
        return context.collisionService().bake(spec, ModelFiles.resolver(context));
    }
}
