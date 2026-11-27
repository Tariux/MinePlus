package com.mineplus.infrastructure.collision.backend;

import com.mineplus.infrastructure.collision.fit.BackendType;
import com.mineplus.infrastructure.collision.runtime.PlacedFurniture;
import com.mineplus.infrastructure.collision.runtime.PlacedPart;
import java.util.Collection;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

/**
 * Client-side-only blocks sent with {@code Player#sendBlockChange}.
 *
 * <p>Nothing exists on the server: the player collides with what the client
 * renders while the server still sees air. That makes ghost blocks free of
 * chunk pollution and impossible to break, drop or duplicate — and it also
 * means they must never be used under a player's feet (the server would think
 * the player floats and may trigger anti-cheat or fall damage handling). The
 * fitter enforces that rule; this backend only obeys the plan.
 */
public final class GhostBlockBackend implements CollisionBackend {

    /** Radius in blocks that receives the block changes. */
    private static final double VIEW_RADIUS = 48.0;

    @Override
    public BackendType type() {
        return BackendType.GHOST_BLOCK;
    }

    @Override
    public boolean supports(PlacedPart part) {
        return part.state() != null;
    }

    @Override
    public int cost(PlacedPart part) {
        return 0;
    }

    @Override
    public void apply(BackendContext context, PlacedFurniture furniture, PlacedPart part) {
        BlockData data = context.parse(part.state());
        if (data == null) {
            return;
        }
        Location location = context.blockLocation(furniture, part);
        for (Player player : context.world().getPlayers()) {
            if (player.getLocation().distanceSquared(location) <= VIEW_RADIUS * VIEW_RADIUS) {
                player.sendBlockChange(location, data);
            }
        }
    }

    @Override
    public void remove(BackendContext context, PlacedFurniture furniture, PlacedPart part) {
        Location location = context.blockLocation(furniture, part);
        BlockData real = location.getBlock().getBlockData();
        for (Player player : context.world().getPlayers()) {
            if (player.getLocation().distanceSquared(location) <= VIEW_RADIUS * VIEW_RADIUS) {
                player.sendBlockChange(location, real);
            }
        }
    }

    @Override
    public void refresh(BackendContext context, PlacedFurniture furniture,
            Collection<Player> viewers) {
        for (PlacedPart part : furniture.parts()) {
            if (part.backend() != BackendType.GHOST_BLOCK) {
                continue;
            }
            BlockData data = context.parse(part.state());
            if (data == null) {
                continue;
            }
            Location location = context.blockLocation(furniture, part);
            for (Player player : viewers) {
                if (player.getWorld() == context.world()
                        && player.getLocation().distanceSquared(location)
                        <= VIEW_RADIUS * VIEW_RADIUS) {
                    player.sendBlockChange(location, data);
                }
            }
        }
    }
}
