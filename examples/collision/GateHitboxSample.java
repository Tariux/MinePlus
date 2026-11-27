package com.mineplus.examples.collision;

import com.mineplus.infrastructure.PluginContext;
import com.mineplus.infrastructure.collision.CollisionSpec;
import com.mineplus.infrastructure.collision.fit.FitResult;
import java.io.IOException;

/**
 * Sample 6 — a gate frame that blocks on its posts and is clickable elsewhere.
 *
 * <p>{@code FORCE_BLOCK} pins the two posts to a known state so a doorway always
 * lines up, while {@code PASSABLE_HITBOX} keeps the opening free to walk through
 * but still routes a right-click to the model's interaction handler.</p>
 */
public final class GateHitboxSample {

    private static final String FRAME = """
            {
              "schema": 1,
              "size": [3, 3, 1],
              "regions": [
                {"type": "box", "from": [0, 0, 0], "to": [0, 2, 0],
                 "directive": "FORCE_BLOCK:minecraft:stone_bricks"},
                {"type": "box", "from": [2, 0, 0], "to": [2, 2, 0],
                 "directive": "FORCE_BLOCK:minecraft:stone_bricks"},
                {"type": "box", "from": [0, 2, 0], "to": [2, 2, 0],
                 "directive": "FORCE_BLOCK:minecraft:stone_bricks"},
                {"type": "box", "from": [1, 0, 0], "to": [1, 1, 0],
                 "directive": "PASSABLE_HITBOX"}
              ]
            }
            """;

    private GateHitboxSample() {
    }

    public static FitResult bake(PluginContext context, String modelKey) throws IOException {
        CollisionSpec spec = CollisionSpec.builder(modelKey)
                .blueprint(FRAME)
                .profile("FURNITURE")
                .build();
        return context.collisionService().bake(spec, ModelFiles.resolver(context));
    }
}
