package com.mineplus.infrastructure.mob.action;

import com.mineplus.infrastructure.mob.BehaviorAction;
import com.mineplus.infrastructure.mob.BehaviorContext;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;

/**
 * The mob fertilizes the grass it stands on: bonemeal growth on grassy
 * ground underfoot. Mirrors the reference mod's "grow" action but delegates
 * plant selection to vanilla's bonemeal logic instead of hand-placing blocks,
 * so results are biome-correct for free.
 */
public final class GrowPlantsAction implements BehaviorAction {

    @Override
    public String id() {
        return "grow_plants";
    }

    @Override
    public boolean execute(BehaviorContext context) {
        if (!context.footBlock().getType().isAir() || !context.onGrassyGround()) {
            return false;
        }
        Block ground = context.groundBlock();
        if (!context.isChunkLoaded(ground)) {
            return false;
        }
        if (!ground.applyBoneMeal(org.bukkit.block.BlockFace.UP)) {
            return false;
        }
        Location center = ground.getLocation().add(0.5, 1.0, 0.5);
        context.world().spawnParticle(Particle.HAPPY_VILLAGER, center, 6, 0.25, 0.2, 0.25);
        context.world().playSound(center, Sound.ITEM_BONE_MEAL_USE, 0.5f, 1.1f);
        return true;
    }
}
