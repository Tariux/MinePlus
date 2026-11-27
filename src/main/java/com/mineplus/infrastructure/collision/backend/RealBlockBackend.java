package com.mineplus.infrastructure.collision.backend;

import com.mineplus.infrastructure.collision.fit.BackendType;
import com.mineplus.infrastructure.collision.runtime.PlacedFurniture;
import com.mineplus.infrastructure.collision.runtime.PlacedPart;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * Places genuine server-side block states.
 *
 * <p>Real blocks are the only parts that block entities, projectiles, pistons
 * and mobs, so the fitter routes every standing surface here. The backend
 * records the block it replaced so removal restores it instead of leaving air.
 */
public final class RealBlockBackend implements CollisionBackend {

    @Override
    public BackendType type() {
        return BackendType.REAL_BLOCK;
    }

    @Override
    public boolean supports(PlacedPart part) {
        return part.state() != null;
    }

    @Override
    public int cost(PlacedPart part) {
        return 1;
    }

    @Override
    public void apply(BackendContext context, PlacedFurniture furniture, PlacedPart part) {
        BlockData data = context.parse(part.state());
        if (data == null) {
            return;
        }
        Location location = context.blockLocation(furniture, part);
        Block block = location.getBlock();
        if (part.originalState() == null) {
            part.setOriginalState(block.getBlockData().getAsString());
        }
        block.setBlockData(data, false);
    }

    @Override
    public void remove(BackendContext context, PlacedFurniture furniture, PlacedPart part) {
        Block block = context.blockLocation(furniture, part).getBlock();
        BlockData original = context.parse(part.originalState());
        if (original != null) {
            block.setBlockData(original, false);
        } else {
            block.setType(Material.AIR, false);
        }
    }
}
