package com.mineplus.infrastructure.mob.action;

import com.mineplus.infrastructure.mob.BehaviorAction;
import com.mineplus.infrastructure.mob.BehaviorContext;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * The mob replants the forest: when wandering empty grass/dirt with clear
 * headroom, a biome-appropriate sapling is planted. Spacing rules keep the
 * reforestation natural — no sapling within a small radius of another one,
 * none under existing tree leaves (sampled, budget-aware checks, block-light
 * preferred so mobs favor open spots). Modeled on the reference mod's
 * "reforest" action ({@code mobReforestAction}) with the block-scan cost
 * contained to a small radius.
 */
public final class PlantSaplingAction implements BehaviorAction {

    private static final int SPACING_CHECK_RADIUS = 3;

    @Override
    public String id() {
        return "plant_sapling";
    }

    @Override
    public boolean execute(BehaviorContext context) {
        Block foot = context.footBlock();
        if (!foot.getType().isAir() || !context.onPlantableGround()) {
            return false;
        }
        Material sapling = BehaviorContext.Planting.saplingFor(context.biome());
        if (sapling == null) {
            return false;
        }
        if (context.countNearby(BehaviorContext.Planting::isSapling, SPACING_CHECK_RADIUS, 1, 1) > 0) {
            return false;
        }
        if (context.countNearby(material -> material != null && org.bukkit.Tag.LEAVES.isTagged(material),
                SPACING_CHECK_RADIUS, Math.max(2, SPACING_CHECK_RADIUS), 1) > 0) {
            return false;
        }
        if (foot.getLightFromSky() < 9) {
            return false;
        }
        BlockData data = org.bukkit.Bukkit.createBlockData(sapling);
        if (!context.trySetBlock(foot, data)) {
            return false;
        }
        Location center = foot.getLocation().add(0.5, 0.7, 0.5);
        context.world().spawnParticle(Particle.COMPOSTER, center, 5, 0.2, 0.2, 0.2);
        context.world().playSound(center, Sound.BLOCK_GRASS_PLACE, 0.6f, 1.2f);
        return true;
    }
}
