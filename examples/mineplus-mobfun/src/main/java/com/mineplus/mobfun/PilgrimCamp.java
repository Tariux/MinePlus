package com.mineplus.mobfun;

import com.mineplus.infrastructure.fx.ParticleFx;
import com.mineplus.infrastructure.fx.Sounds;
import com.mineplus.infrastructure.mob.BehaviorContext;
import com.mineplus.infrastructure.mob.action.JourneyToStructureAction;
import com.mineplus.infrastructure.mob.navigation.GroundProbe;
import com.mineplus.util.DebugLogger;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Mob;

/**
 * The pilgrim's night and arrival — the part of the journey that is content
 * rather than mechanics.
 *
 * <p>Finding the village, walking to it however far away it is, keeping the
 * crew together, halting at night and owning the scenario are all the Core's
 * job ({@link JourneyToStructureAction}); this class only decides what the
 * crew's camp <i>looks like</i> and what arriving <i>feels like</i>:
 * <ul>
 *   <li><b>One shared camp</b> - the leader raises a single campfire plus a
 *       side-by-side row of beds, one bed per crew member, on ground the Core
 *       accepts as buildable ({@link GroundProbe#isCampGround} - solid, not a
 *       leaf, not a log, which is the check whose absence made the old camp
 *       "build" nothing on a forest floor). Every edit goes through the
 *       engine's per-run budget, and both halves of a bed are verified before
 *       either is written, so exhaustion never leaves half a bed behind.</li>
 *   <li><b>Everyone beside everyone</b> - the beds are laid as one contiguous
 *       row along a single lateral axis, and the crew is already cohesive when
 *       the camp is raised (the Core walks stragglers back before it halts
 *       them). Villagers by a placed bed path over and sleep in it, so the camp
 *       reads as a camp.</li>
 *   <li><b>A night is not re-spent</b> - an existing campfire within the guard
 *       radius tops up instead of restarting: no second fire, and a camp the
 *       budget could not finish is retried later in the same night rather than
 *       lost.</li>
 * </ul>
 */
public final class PilgrimCamp implements JourneyToStructureAction.Callbacks {

    /** The crew name handed to the journey builder, and read back here. */
    static final String CREW = "pilgrims";

    private static final int BED_CAP = 8;
    private static final int CAMP_GUARD_RADIUS = 6;
    private static final List<BlockFace> FACES =
            List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST);

    private final ParticleFx fx;

    public PilgrimCamp(ParticleFx fx) {
        this.fx = fx;
    }

    @Override
    public boolean onNight(BehaviorContext context, Location campSite) {
        Block anchor = campSite.getBlock();
        Block fireSpot = campFireSpot(context, anchor);
        if (fireSpot == null) {
            // No buildable ground at all: a rocky or canopy floor will not
            // change by morning, so spend the night instead of re-probing it
            // on every engine roll.
            DebugLogger.debug("[MobFun] pilgrim crew found no camp ground at %d %d %d (day %d)",
                    anchor.getX(), anchor.getY(), anchor.getZ(), context.world().getFullTime() / 24000L);
            return true;
        }
        if (!raiseCampfire(context, fireSpot)) {
            return false;
        }
        int crew = Math.min(BED_CAP, Math.max(1, context.crewMembers(CREW).size()));
        int beds = layBedRow(context, fireSpot, crew);
        DebugLogger.debug("[MobFun] crew camp at %d %d %d: crew=%d, beds=%d",
                fireSpot.getX(), fireSpot.getY(), fireSpot.getZ(), crew, beds);
        return true;
    }

    @Override
    public void onArrival(BehaviorContext context, Location destination) {
        Location at = context.entity().getLocation().add(0, 1.2, 0);
        fx.ring(at, Particle.HAPPY_VILLAGER, 2.5, 6);
        fx.burst(ParticleFx.Tier.AMBIENT, at, Particle.HAPPY_VILLAGER, 16, 0.8, 0.5, 0.8, 0.0);
        Sounds.play(at, Sound.ENTITY_VILLAGER_CELEBRATE.name(), 1.0f, 1.1f);
        DebugLogger.debug("[MobFun] pilgrim crew arrived at %d %d %d",
                destination.getBlockX(), destination.getBlockY(), destination.getBlockZ());
    }

    @Override
    public void onNotFound(BehaviorContext context) {
        Mob pilgrim = context.entity() instanceof Mob mob ? mob : null;
        DebugLogger.debug("[MobFun] pilgrim %s found no village nearby - the region is barren;"
                        + " the Core re-probes it on its search cooldown",
                pilgrim == null ? "?" : pilgrim.getUniqueId());
    }

    /**
     * The campfire's block: the fire itself if one already stands beside the
     * crew (so a second night tops up the camp instead of lighting a parallel
     * fire), else buildable ground one step away from the leader. Never the
     * block she is standing on - a campfire under a villager is a camp she
     * burns in, not a camp she sleeps beside.
     *
     * @return {@code null} when nothing around the crew can carry a camp
     */
    private Block campFireSpot(BehaviorContext context, Block anchor) {
        Block standing = nearestExistingCampfire(context, anchor);
        if (standing != null) {
            return standing;
        }
        for (BlockFace face : FACES) {
            Block spot = GroundProbe.campGroundNear(anchor.getRelative(face), 2, 1);
            if (spot != null && !spot.equals(anchor)) {
                return spot;
            }
        }
        return null;
    }

    private Block nearestExistingCampfire(BehaviorContext context, Block anchor) {
        int radius = CAMP_GUARD_RADIUS;
        for (int ring = 1; ring <= radius; ring++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                            continue;
                        }
                        Block candidate = context.world().getBlockAt(anchor.getX() + dx,
                                anchor.getY() + dy, anchor.getZ() + dz);
                        if (candidate.getType() == Material.CAMPFIRE) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Lights the fire, once. {@code false} only when the engine's edit budget
     * refused the write, which is a transient condition worth retrying inside
     * the same night.
     */
    private boolean raiseCampfire(BehaviorContext context, Block fireSpot) {
        Location at = fireSpot.getLocation().add(0.5, 0.7, 0.5);
        if (fireSpot.getType() == Material.CAMPFIRE) {
            fx.burst(ParticleFx.Tier.AMBIENT, at, Particle.CAMPFIRE_COSY_SMOKE, 4, 0.2, 0.3, 0.2, 0.0);
            return true;
        }
        boolean lit = context.trySetBlock(fireSpot, Bukkit.createBlockData(Material.CAMPFIRE));
        if (lit) {
            Sounds.play(at, Sound.BLOCK_CAMPFIRE_CRACKLE.name(), 0.9f, 1.0f);
            fx.burst(ParticleFx.Tier.AMBIENT, at, Particle.CAMPFIRE_SIGNAL_SMOKE, 8, 0.3, 0.4, 0.3, 0.0);
        }
        return lit;
    }

    /**
     * A contiguous row of {@code bedsWanted} beds beside the fire, laid along
     * one lateral axis and pointing away from it. Each facing is tried, both
     * ways, and the longest row wins: terrain that blocks one side of a
     * campfire still gives the crew a shared wall of beds on another.
     */
    private int layBedRow(BehaviorContext context, Block fireSpot, int bedsWanted) {
        int already = context.countNearby(Tag.BEDS::isTagged, CAMP_GUARD_RADIUS, 1, bedsWanted);
        int missing = bedsWanted - already;
        if (missing <= 0) {
            return already;
        }
        int best = 0;
        for (BlockFace out : FACES) {
            for (BlockFace lateral : List.of(turnLeft(out), turnRight(out))) {
                best = Math.max(best, layRow(context, fireSpot, out, lateral, missing));
            }
        }
        return best + already;
    }

    /** Beds start two blocks out from the fire and run sideways from there. */
    private int layRow(BehaviorContext context, Block fireSpot, BlockFace out, BlockFace lateral, int wanted) {
        Block start = fireSpot.getRelative(out, 2).getRelative(lateral);
        int placed = 0;
        for (int i = 0; i < wanted; i++) {
            Block foot = start.getRelative(lateral, i);
            Block head = foot.getRelative(out);
            if (!isBedGround(foot) || !isBedGround(head)) {
                break;
            }
            if (!context.trySetBlock(foot, bedData(out, Bed.Part.FOOT))) {
                break;
            }
            if (!context.trySetBlock(head, bedData(out, Bed.Part.HEAD))) {
                context.tryBreak(foot, false);
                break;
            }
            placed++;
        }
        return placed;
    }

    private static Bed bedData(BlockFace facing, Bed.Part part) {
        Bed bed = (Bed) Bukkit.createBlockData(Material.CYAN_BED);
        bed.setFacing(facing);
        bed.setPart(part);
        return bed;
    }

    /** A bed needs buildable ground under it and clear air over it. */
    private static boolean isBedGround(Block spot) {
        return GroundProbe.isCampGround(spot) && spot.getRelative(BlockFace.UP).isPassable();
    }

    private static BlockFace turnLeft(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.WEST;
            case EAST -> BlockFace.NORTH;
            case SOUTH -> BlockFace.EAST;
            case WEST -> BlockFace.SOUTH;
            default -> face;
        };
    }

    private static BlockFace turnRight(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> face;
        };
    }
}
