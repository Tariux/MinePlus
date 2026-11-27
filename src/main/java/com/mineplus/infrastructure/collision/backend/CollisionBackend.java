package com.mineplus.infrastructure.collision.backend;

import com.mineplus.infrastructure.collision.runtime.PlacedFurniture;
import com.mineplus.infrastructure.collision.runtime.PlacedPart;
import java.util.Collection;
import org.bukkit.entity.Player;

/**
 * One way of materialising fitted collision cells in the world.
 *
 * <p>Every method runs on the owning world's thread. Implementations must be
 * idempotent enough for startup reconciliation: applying an already applied
 * part repairs it instead of stacking a second entity.
 */
public interface CollisionBackend {

    /** Backend kind this implementation serves. */
    com.mineplus.infrastructure.collision.fit.BackendType type();

    /** True when this backend can carry the given part at all. */
    boolean supports(PlacedPart part);

    /** Relative cost; the planner already picked the backend, this is telemetry. */
    int cost(PlacedPart part);

    /** Creates the world state for one part. */
    void apply(BackendContext context, PlacedFurniture furniture, PlacedPart part);

    /** Removes the world state of one part; missing state is not an error. */
    void remove(BackendContext context, PlacedFurniture furniture, PlacedPart part);

    /**
     * Re-sends or repairs parts for viewers that just arrived (join, teleport,
     * chunk resend). Only ghost-style backends need this.
     */
    default void refresh(BackendContext context, PlacedFurniture furniture,
            Collection<Player> viewers) {
    }
}
