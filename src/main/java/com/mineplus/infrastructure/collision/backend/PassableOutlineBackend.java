package com.mineplus.infrastructure.collision.backend;

import com.mineplus.infrastructure.collision.fit.BackendType;
import com.mineplus.infrastructure.collision.runtime.PlacedFurniture;
import com.mineplus.infrastructure.collision.runtime.PlacedPart;

/**
 * A pass-through outline: visible selection box, clicks, no collision.
 *
 * <p>Vanilla cannot give a block collision without also giving it a hitbox, and
 * placing a non-colliding block state (torch, flower) breaks physics whenever
 * its support disappears. This backend therefore uses an {@link
 * org.bukkit.entity.Interaction} with the same size — the trade-off being that
 * it cannot be broken as a block. Model interaction routing turns the click
 * into the model's own event, so the loss is invisible to players.
 */
public final class PassableOutlineBackend extends InteractionBackend {

    public PassableOutlineBackend() {
        super(BackendType.PASSABLE_OUTLINE);
    }

    @Override
    public int cost(PlacedPart part) {
        return 3;
    }

    @Override
    public void apply(BackendContext context, PlacedFurniture furniture, PlacedPart part) {
        super.apply(context, furniture, part);
    }
}
