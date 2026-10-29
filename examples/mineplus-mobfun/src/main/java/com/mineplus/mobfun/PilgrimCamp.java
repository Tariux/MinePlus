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
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Mob;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

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

    /** Marks a campfire or bed as belonging to one crew's camp. */
    private static final String CAMP_TAG = "pilgrim_camp";

    private static final int CAMP_GUARD_RADIUS = 6;
    private static final List<BlockFace> FACES =
            List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST);

    private final ParticleFx fx;
    private final NamespacedKey campKey;
    /**
     * The exact blocks each crew's camp is made of. A fixed-radius sweep is not
     * enough: a bed row grows with the crew, and its far end can sit well
     * outside the guard radius — those beds would survive dawn and litter the
     * route. Runtime-only state, like the journey that owns it.
     */
    private final java.util.Map<String, List<Location>> campBlocks =
            new java.util.concurrent.ConcurrentHashMap<>();

    public PilgrimCamp(Plugin plugin, ParticleFx fx) {
        this.fx = fx;
        this.campKey = new NamespacedKey(plugin, CAMP_TAG);
    }

    @Override
    public boolean onNight(BehaviorContext context, Location campSite) {
        String crewTag = crewTag(context);
        Block anchor = campSite.getBlock();
        Block fireSpot = campFireSpot(context, anchor, crewTag);
        if (fireSpot == null) {
            // No buildable ground at all: a rocky or canopy floor will not
            // change by morning, so spend the night instead of re-probing it
            // on every engine roll.
            DebugLogger.debug("[MobFun] pilgrim crew found no camp ground at %d %d %d (day %d)",
                    anchor.getX(), anchor.getY(), anchor.getZ(), context.world().getFullTime() / 24000L);
            return true;
        }
        if (!raiseCampfire(context, fireSpot, crewTag)) {
            return false;
        }
        int crew = Math.max(1, context.crewMembers(CREW).size());
        int beds = layBedRow(context, fireSpot, crewTag, crew);
        DebugLogger.debug("[MobFun] crew camp at %d %d %d: crew=%d, beds=%d",
                fireSpot.getX(), fireSpot.getY(), fireSpot.getZ(), crew, beds);
        return beds >= crew;
    }

    @Override
    public boolean onDawn(BehaviorContext context, Location campSite) {
        String crewTag = crewTag(context);
        List<Location> owned = campBlocks.get(crewTag);
        if (owned != null) {
            for (Location at : owned) {
                Block block = context.world().getBlockAt(at);
                if (!isCrewCampBlock(block, crewTag)) {
                    continue;
                }
                if (!context.tryBreak(block, false)) {
                    return false;
                }
            }
            campBlocks.remove(crewTag, owned);
        }
        // Safety net for a camp whose record was never written (a half-finished
        // retry): anything the crew still owns near the site goes as well.
        Block anchor = campSite.getBlock();
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -CAMP_GUARD_RADIUS; dx <= CAMP_GUARD_RADIUS; dx++) {
                for (int dz = -CAMP_GUARD_RADIUS; dz <= CAMP_GUARD_RADIUS; dz++) {
                    Block block = context.world().getBlockAt(anchor.getX() + dx,
                            anchor.getY() + dy, anchor.getZ() + dz);
                    if (!isCrewCampBlock(block, crewTag)) {
                        continue;
                    }
                    if (!context.tryBreak(block, false)) {
                        return false;
                    }
                }
            }
        }
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
     * fire), else buildable ground beside the leader. Never the block she is
     * standing on - a campfire under a villager is a camp she burns in, not a
     * camp she sleeps beside.
     *
     * @return {@code null} when nothing around the crew can carry a camp
     */
    private Block campFireSpot(BehaviorContext context, Block anchor, String crewTag) {
        Block standing = nearestExistingCampfire(context, anchor, crewTag);
        if (standing != null) {
            return standing;
        }
        return campGroundAround(context, anchor, 2, 1);
    }

    /**
     * Buildable ground in a ring around {@code anchor}, with the anchor's own
     * cell excluded.
     *
     * <p>The exclusion is not cosmetic. The formation point the Core hands
     * over is a <em>standable</em> cell - air on solid ground - which is
     * exactly what {@link GroundProbe#isCampGround} accepts. A plain radius
     * search therefore finds the block the leader is standing on first, and
     * either puts the fire under her feet or (when the result is thrown away)
     * reports "no camp ground" on perfectly good terrain, so the crew spends
     * the night with no camp at all.
     */
    private Block campGroundAround(BehaviorContext context, Block anchor, int radius, int verticalWindow) {
        World world = context.world();
        for (int ring = 1; ring <= radius; ring++) {
            for (int dy = -verticalWindow; dy <= verticalWindow; dy++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                            continue; // ring perimeter only; inner rings were checked
                        }
                        Block candidate = world.getBlockAt(anchor.getX() + dx, anchor.getY() + dy,
                                anchor.getZ() + dz);
                        if (!candidate.equals(anchor) && GroundProbe.isCampGround(candidate)) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return null;
    }

    private Block nearestExistingCampfire(BehaviorContext context, Block anchor, String crewTag) {
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
                        if (candidate.getType() == Material.CAMPFIRE
                                && isCrewCampBlock(candidate, crewTag)) {
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
    private boolean raiseCampfire(BehaviorContext context, Block fireSpot, String crewTag) {
        Location at = fireSpot.getLocation().add(0.5, 0.7, 0.5);
        if (fireSpot.getType() == Material.CAMPFIRE) {
            tagCrewCampBlock(fireSpot, crewTag);
            rememberCampBlock(crewTag, fireSpot);
            fx.burst(ParticleFx.Tier.AMBIENT, at, Particle.CAMPFIRE_COSY_SMOKE, 4, 0.2, 0.3, 0.2, 0.0);
            return true;
        }
        boolean lit = context.trySetBlock(fireSpot, Bukkit.createBlockData(Material.CAMPFIRE));
        if (lit) {
            tagCrewCampBlock(fireSpot, crewTag);
            rememberCampBlock(crewTag, fireSpot);
            Sounds.play(at, Sound.BLOCK_CAMPFIRE_CRACKLE.name(), 0.9f, 1.0f);
            fx.burst(ParticleFx.Tier.AMBIENT, at, Particle.CAMPFIRE_SIGNAL_SMOKE, 8, 0.3, 0.4, 0.3, 0.0);
        }
        return lit;
    }

    private String crewTag(BehaviorContext context) {
        String crew = context.crewId(CREW);
        return crew != null ? crew : "solo:" + context.entity().getUniqueId();
    }

    /**
     * A contiguous row of {@code bedsWanted} beds beside the fire, laid along
     * one lateral axis and pointing away from it. Each facing is tried, both
     * ways, and the longest row wins: terrain that blocks one side of a
     * campfire still gives the crew a shared wall of beds on another.
     *
     * <p>Only the crew's <b>own</b> beds count as already laid. Counting every
     * bed in the guard radius meant that a crew camping near a village found
     * the village's beds, decided it already had a row, and laid none — the
     * camp was a campfire in the middle of somebody else's bedroom.
     */
    private int layBedRow(BehaviorContext context, Block fireSpot, String crewTag, int bedsWanted) {
        int already = countCrewBeds(context, crewTag, bedsWanted);
        int missing = bedsWanted - already;
        if (missing <= 0) {
            return already;
        }
        // Plan every side first and commit only the longest row. Trying each
        // direction by writing it laid up to eight overlapping rows around one
        // fire whenever the terrain allowed more than one.
        BedRow best = null;
        for (BlockFace out : FACES) {
            for (BlockFace lateral : List.of(turnLeft(out), turnRight(out))) {
                BedRow candidate = planRow(fireSpot, out, lateral, missing);
                if (best == null || candidate.beds() > best.beds()) {
                    best = candidate;
                }
            }
        }
        if (best == null || best.beds() == 0) {
            return already;
        }
        int placed = 0;
        for (int i = 0; i < best.beds(); i++) {
            // A bed is a pair: never spend the foot's edit without the head's.
            if (!context.hasEditBudget(2)) {
                break;
            }
            Block foot = best.start().getRelative(best.lateral(), i);
            Block head = foot.getRelative(best.out());
            if (!context.trySetBlock(foot, bedData(best.out(), Bed.Part.FOOT))) {
                break;
            }
            if (!context.trySetBlock(head, bedData(best.out(), Bed.Part.HEAD))) {
                context.tryBreak(foot, false);
                break;
            }
            tagCrewCampBlock(foot, crewTag);
            tagCrewCampBlock(head, crewTag);
            rememberCampBlock(crewTag, foot);
            rememberCampBlock(crewTag, head);
            placed++;
        }
        return already + placed;
    }

    private BedRow planRow(Block fireSpot, BlockFace out, BlockFace lateral, int wanted) {
        Block start = fireSpot.getRelative(out, 2).getRelative(lateral);
        int fitting = 0;
        for (int i = 0; i < wanted; i++) {
            Block foot = start.getRelative(lateral, i);
            Block head = foot.getRelative(out);
            if (!isBedGround(foot) || !isBedGround(head)) {
                break;
            }
            fitting++;
        }
        return new BedRow(start, out, lateral, fitting);
    }

    private record BedRow(Block start, BlockFace out, BlockFace lateral, int beds) {
    }

    /** The crew's own beds laid for this camp; its head halves are not counted. */
    private int countCrewBeds(BehaviorContext context, String crewTag, int wanted) {
        List<Location> owned = campBlocks.get(crewTag);
        if (owned == null) {
            return 0;
        }
        int count = 0;
        for (Location at : owned) {
            if (count >= wanted) {
                break;
            }
            if (isCrewBed(context.world().getBlockAt(at), crewTag)) {
                count++;
            }
        }
        return count;
    }

    /** {@code true} when the block is a bed this crew laid; its head half is not counted. */
    private boolean isCrewBed(Block block, String crewTag) {
        if (block == null || !Tag.BEDS.isTagged(block.getType())) {
            return false;
        }
        if (!(block.getBlockData() instanceof Bed bed) || bed.getPart() != Bed.Part.FOOT) {
            return false;
        }
        return isCrewCampBlock(block, crewTag);
    }

    /** {@code true} when this crew owns the campfire or bed. */
    private boolean isCrewCampBlock(Block block, String crewTag) {
        if (block == null || (block.getType() != Material.CAMPFIRE
                && !Tag.BEDS.isTagged(block.getType()))) {
            return false;
        }
        if (!(block.getState() instanceof TileState tile)) {
            return false;
        }
        String owner = tile.getPersistentDataContainer().get(campKey, PersistentDataType.STRING);
        return crewTag.equals(owner);
    }

    /** Marks a camp block as belonging to one crew. */
    private void tagCrewCampBlock(Block block, String crewTag) {
        if (!(block.getState() instanceof TileState tile)) {
            return;
        }
        tile.getPersistentDataContainer().set(campKey, PersistentDataType.STRING, crewTag);
        tile.update();
    }

    /** Records a block the crew's camp owns, so dawn can remove all of it. */
    private void rememberCampBlock(String crewTag, Block block) {
        Location at = block.getLocation();
        campBlocks.computeIfAbsent(crewTag, ignored -> new java.util.ArrayList<>()).add(at);
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
