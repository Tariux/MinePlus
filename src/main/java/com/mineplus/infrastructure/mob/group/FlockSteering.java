package com.mineplus.infrastructure.mob.group;

import org.bukkit.Location;
import org.bukkit.entity.Mob;

/**
 * The separation half of flocking, for crews that are supposed to look like a
 * group rather than a pile.
 *
 * <p>Cohesion and alignment already have a better implementation in this
 * subsystem: the crew's formation point <i>is</i> cohesion, and the shared route
 * <i>is</i> alignment (every member walks the same way). What is left is the
 * one force that visibly matters in Minecraft — separation. Vanilla pathfinding
 * happily puts five villagers on the same block, and a crew whose members stack
 * cannot cross a doorway. This class computes the small sideways push that
 * keeps them apart, and nothing else.
 *
 * <p>The push is capped at half the separation radius, so steering can never
 * fling a member out of formation; it only rounds off the overlap. Allocation:
 * one {@code double[2]}, no vectors, safe on the engine thread.
 */
public final class FlockSteering {

    private static final double[] NONE = {0.0, 0.0};

    private FlockSteering() {
    }

    /**
     * The separation push for one member.
     *
     * @param member    the member that should sidestep
     * @param neighbours every live member of its crew, itself included
     * @param radius    how close is too close
     * @return {@code {dx, dz}} to add to the member's target, or zero
     */
    public static double[] separation(Mob member, Iterable<? extends Mob> neighbours, double radius) {
        if (member == null || neighbours == null || radius <= 0) {
            return NONE;
        }
        Location here = member.getLocation();
        double pushX = 0.0;
        double pushZ = 0.0;
        for (Mob neighbour : neighbours) {
            if (neighbour == null || neighbour == member || neighbour.isDead() || !neighbour.isValid()
                    || neighbour.getWorld() != here.getWorld()) {
                continue;
            }
            double dx = here.getX() - neighbour.getX();
            double dz = here.getZ() - neighbour.getZ();
            double distance = Math.hypot(dx, dz);
            if (distance >= radius) {
                continue;
            }
            if (distance < 1.0E-4) {
                // Two members on the same spot: split them along a stable
                // direction derived from one id, so they do not oscillate.
                double angle = (member.getUniqueId().hashCode() & 0xFFFF) * (Math.PI * 2.0 / 0x10000);
                dx = Math.cos(angle);
                dz = Math.sin(angle);
                distance = 1.0;
            }
            double weight = (radius - Math.min(distance, radius)) / radius;
            pushX += (dx / distance) * weight;
            pushZ += (dz / distance) * weight;
        }
        double length = Math.hypot(pushX, pushZ);
        if (length < 1.0E-4) {
            return NONE;
        }
        double cap = radius * 0.5;
        double scale = Math.min(length, cap) / length;
        return new double[]{pushX * scale, pushZ * scale};
    }

    /**
     * A target shifted by the separation push — the one call a caller needs.
     * Returns the target itself when the member has room.
     */
    public static Location separated(Mob member, Location target, Iterable<? extends Mob> neighbours,
                                     double radius) {
        if (target == null) {
            return null;
        }
        double[] push = separation(member, neighbours, radius);
        if (push[0] == 0.0 && push[1] == 0.0) {
            return target;
        }
        return new Location(target.getWorld(), target.getX() + push[0], target.getY(),
                target.getZ() + push[1]);
    }
}
