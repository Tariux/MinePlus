package com.mineplus.infrastructure.combat;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * One resolved projectile landing: what it struck, where, how far the round had
 * flown, and the damage the ballistics model computed for it.
 *
 * <p>Damage is already resolved for falloff, penetration and ricochet losses
 * and the headshot bonus by the time a hit is reported, so a handler can apply
 * gameplay consequences without redoing the math.
 */
public record ProjectileHit(Location position, Entity target, Block block, BlockFace face,
                            double travelled, double damage, boolean headshot,
                            Player shooter, BallisticProfile profile) {

    /** {@code true} when the round struck a living thing rather than geometry. */
    public boolean isEntityHit() {
        return target != null;
    }

    /** {@code true} when the round was stopped by a block. */
    public boolean isBlockHit() {
        return target == null && block != null;
    }
}
