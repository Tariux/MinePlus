package com.mineplus.infrastructure.mob.action;

import com.mineplus.infrastructure.mob.BehaviorAction;
import com.mineplus.infrastructure.mob.BehaviorContext;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * The mob lights its surroundings: standing in a dark spot on solid ground,
 * it places a torch. This is the Villager "t" flag from the reference mod
 * ({@code vImproveLighting / tryPlaceGroundTorch}) as a generic mob action:
 * block-light gated, replaceability checked, edit-budgeted.
 */
public final class TorchPlaceAction implements BehaviorAction {

    private final int maxBlockLight;

    /** @param maxBlockLight block-light level at or below which a torch is placed */
    public TorchPlaceAction(int maxBlockLight) {
        this.maxBlockLight = Math.max(0, Math.min(15, maxBlockLight));
    }

    public TorchPlaceAction() {
        this(4);
    }

    @Override
    public String id() {
        return "place_torch";
    }

    @Override
    public boolean execute(BehaviorContext context) {
        Block foot = context.footBlock();
        if (!foot.getType().isAir() || !foot.getChunk().isLoaded()) {
            return false;
        }
        Block ground = context.groundBlock();
        if (!ground.getType().isSolid() || ground.isLiquid()) {
            return false;
        }
        if (foot.getLightFromBlocks() > maxBlockLight) {
            return false;
        }
        BlockData torch = org.bukkit.Bukkit.createBlockData(org.bukkit.Material.TORCH);
        if (!context.trySetBlock(foot, torch)) {
            return false;
        }
        Location center = foot.getLocation().add(0.5, 0.5, 0.5);
        context.world().playSound(center, Sound.BLOCK_WOOD_PLACE, 0.7f, 1.4f);
        return true;
    }
}
