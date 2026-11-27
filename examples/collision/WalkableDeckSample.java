package com.mineplus.examples.collision;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.collision.CollisionSpec;
import com.mineplus.infrastructure.collision.fit.FitResult;
import java.io.IOException;

/**
 * Sample 5 — a two-step deck.
 *
 * <p>A blueprint states the steps explicitly: {@code STEP} for the lower one
 * (half-height top, so it is walkable without jumping) and {@code TOP_SURFACE}
 * for the upper one. The {@code WALKABLE} profile then scores flatness harder
 * than exact volume, which is what a walk surface needs.</p>
 */
public final class WalkableDeckSample {

    private static final String STEPS = """
            {
              "schema": 1,
              "size": [2, 2, 1],
              "regions": [
                {"type": "box", "from": [0, 0, 0], "to": [1, 0, 0], "directive": "STEP"},
                {"type": "box", "from": [0, 1, 0], "to": [1, 1, 0], "directive": "TOP_SURFACE"},
                {"type": "box", "from": [1, 1, 0], "to": [1, 1, 0], "directive": "EMPTY"}
              ]
            }
            """;

    private WalkableDeckSample() {
    }

    public static FitResult bake(PluginContext context, String modelKey) throws IOException {
        CollisionSpec spec = CollisionSpec.builder(modelKey)
                .blueprint(STEPS)
                .profile("WALKABLE")
                .build();
        return context.collisionService().bake(spec, ModelFiles.resolver(context));
    }
}
