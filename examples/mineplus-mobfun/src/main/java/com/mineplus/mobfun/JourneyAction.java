package com.mineplus.mobfun;

import com.mineplus.infrastructure.mob.BehaviorAction;
import com.mineplus.infrastructure.mob.BehaviorContext;
import com.mineplus.util.DebugLogger;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.StructureType;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/**
 * The Pilgrim Villager: a villager dropped into the wastelands finds the
 * nearest village and marches there - and villagers around march as one crew.
 *
 * <p>How the journey works (all state rides on the villager's PDC, so it is
 * stateless from the engine's point of view and survives restarts):
 * <ul>
 *   <li><b>Rally</b> - a villager with no destination runs a (cooldown-gated)
 *       village structure search near it. If a village is further than the
 *       arrival radius, it starts: the village waypoint is written to its PDC
 *       and pushed onto every nearby villager that has none, so stray
 *       villagers rally into one crew under the same leader.</li>
 *   <li><b>March in sync</b> - each engine roll, the leader marches toward
 *       the village in short hop bursts (paced so it reads as a trudge, not a
 *       teleport); followers more than a few blocks from the leader march
 *       toward the leader instead, so the crew naturally reforms on the move.
 *       When the leader dies, the first follower to evaluate promotes itself,
 *       and the crew keeps walking.</li>
 *   <li><b>Night camp</b> - between dusk and late night the crew halts. Once
 *       per night the leader raises <b>one shared camp</b> beside its resting
 *       block: a single campfire plus one side-by-side bed per crew member
 *       (all placed through the engine's edit budget, on solid non-leaf
 *       ground), and the whole crew's camped-day PDC is marked - followers
 *       never raise their own fires. The crew holds still until dawn -
 *       villagers near a placed bed will even path over and sleep in it.</li>
 *   <li><b>Arrival</b> - reaching the waypoint clears the journey and the
 *       crew celebrates; the campsite stays behind as a trail marker.</li>
 * </ul>
 *
 * <p>Gaps are covered: search attempts are PDC-cooldown-gated (never a
 * locate-structure storm) but retry within ~30-60s so a barren wasteland is
 * reported and re-probed instead of stalling for minutes, the locate radius
 * reaches well beyond spawn distance, silent failures surface through
 * DebugLogger and {@code /mobfun status} (per-villager journey state), and
 * builders tagged by the golem showcases keep their posts instead of
 * wandering off.
 */
public final class JourneyAction implements BehaviorAction {

    private static final int SEARCH_RADIUS_CHUNKS = 64;
    private static final long SEARCH_COOLDOWN_TICKS = 600L;
    private static final int ARRIVAL_RADIUS = 40;
    private static final int ADOPT_RADIUS = 16;
    private static final double REGROUP_RADIUS = 5.0;
    private static final double HOP_SPEED = 0.38;
    private static final int HOPS_PER_BURST = 6;
    private static final int HOP_DELAY_TICKS = 2;
    private static final double HOP_LIFT = 0.3;
    private static final double HOP_LIFT_STEP = 0.45;
    private static final int NIGHT_START = 13000;
    private static final int NIGHT_END = 23000;
    private static final int CREW_RADIUS = 20;
    private static final int CREW_BED_CAP = 8;
    private static final int CAMP_GUARD_RADIUS = 6;
    private static final int JOURNEY_STATUS_LINES = 16;

    private final Plugin plugin;
    private final NamespacedKey destinationKey;
    private final NamespacedKey leaderKey;
    private final NamespacedKey campedDayKey;
    private final NamespacedKey searchCooldownKey;
    private final NamespacedKey builderKey;

    public JourneyAction(Plugin plugin) {
        this.plugin = plugin;
        this.destinationKey = new NamespacedKey(plugin, "mobfun_journey_dst");
        this.leaderKey = new NamespacedKey(plugin, "mobfun_journey_leader");
        this.campedDayKey = new NamespacedKey(plugin, "mobfun_journey_camp");
        this.searchCooldownKey = new NamespacedKey(plugin, "mobfun_search_cd");
        this.builderKey = new NamespacedKey(plugin, "mobfun_builder");
    }

    @Override
    public String id() {
        return "village_journey";
    }

    @Override
    public boolean execute(BehaviorContext context) {
        Mob pilgrim = context.entity() instanceof Mob mob ? mob : null;
        if (pilgrim == null || pilgrim.isDead() || !pilgrim.isValid()
                || !context.isChunkLoaded(context.footBlock())) {
            return false;
        }
        String destination = pilgrim.getPersistentDataContainer().get(destinationKey, PersistentDataType.STRING);
        if (destination == null) {
            return startJourney(context, pilgrim);
        }
        return march(context, pilgrim, destination);
    }

    /** First roll without a destination: try to rally a journey. */
    private boolean startJourney(BehaviorContext context, Mob pilgrim) {
        if (isBuilder(pilgrim)) {
            return false;
        }
        WorldTime time = worldTime(context);
        long now = time.gameTime;
        Long cooldownUntil = pilgrim.getPersistentDataContainer().get(searchCooldownKey, PersistentDataType.LONG);
        if (cooldownUntil != null && now < cooldownUntil) {
            return false;
        }
        Location village = locateVillage(context, pilgrim);
        pilgrim.getPersistentDataContainer().set(searchCooldownKey, PersistentDataType.LONG,
                now + SEARCH_COOLDOWN_TICKS + context.random().nextInt(600));
        if (village == null) {
            // Barren region: say so, and retry on the next (short) cooldown
            // instead of silently stalling for minutes at a time.
            DebugLogger.debug("[MobFun] pilgrim %s found no village within %d chunks (search retries in"
                    + " ~%ds)", pilgrim.getUniqueId(), SEARCH_RADIUS_CHUNKS,
                    (SEARCH_COOLDOWN_TICKS + 300) / 20);
            return false;
        }
        double distance = pilgrim.getLocation().distance(village);
        if (distance < ARRIVAL_RADIUS) {
            // Already home: nothing to journey toward, stay put.
            DebugLogger.debug("[MobFun] pilgrim %s is already within a village (%.0f blocks to its"
                    + " center) - no journey", pilgrim.getUniqueId(), distance);
            return false;
        }

        DebugLogger.debug("[MobFun] pilgrim %s starts a journey to %d %d %d (%.0f blocks away)",
                pilgrim.getUniqueId(), village.getBlockX(), village.getBlockY(), village.getBlockZ(),
                distance);
        Location waypoint = new Location(village.getWorld(), village.getBlockX(), village.getBlockY(),
                village.getBlockZ());
        String encoded = encode(waypoint);
        pilgrim.getPersistentDataContainer().set(destinationKey, PersistentDataType.STRING, encoded);
        pilgrim.getPersistentDataContainer().set(leaderKey, PersistentDataType.STRING,
                pilgrim.getUniqueId().toString());
        int adopted = adoptCrew(context, pilgrim, encoded);
        DebugLogger.debug("[MobFun] pilgrim %s rallied %d follower(s) onto the journey",
                pilgrim.getUniqueId(), adopted);
        return true;
    }

    /** Shares the destination with nearby village-less villagers - the crew rallies. */
    private int adoptCrew(BehaviorContext context, Mob leader, String encoded) {
        int adopted = 0;
        for (Entity entity : leader.getNearbyEntities(ADOPT_RADIUS, ADOPT_RADIUS, ADOPT_RADIUS)) {
            if (!(entity instanceof Villager villager) || villager == leader || isBuilder(villager)) {
                continue;
            }
            if (villager.getPersistentDataContainer().get(destinationKey, PersistentDataType.STRING) != null) {
                continue;
            }
            villager.getPersistentDataContainer().set(destinationKey, PersistentDataType.STRING, encoded);
            villager.getPersistentDataContainer().set(leaderKey, PersistentDataType.STRING,
                    leader.getUniqueId().toString());
            adopted++;
        }
        return adopted;
    }

    /** Every later roll: march, regroup, camp at night, or celebrate arrival. */
    private boolean march(BehaviorContext context, Mob pilgrim, String encoded) {
        Location waypoint = decode(encoded);
        if (waypoint == null || !waypoint.getWorld().equals(context.world())) {
            clearJourney(pilgrim);
            return false;
        }
        if (pilgrim.getLocation().distance(waypoint) < ARRIVAL_RADIUS) {
            DebugLogger.debug("[MobFun] pilgrim %s arrived (within %d blocks) and celebrates",
                    pilgrim.getUniqueId(), ARRIVAL_RADIUS);
            clearJourney(pilgrim);
            celebrateArrival(context, pilgrim);
            return true;
        }

        WorldTime time = worldTime(context);
        if (time.dayTicks >= NIGHT_START && time.dayTicks <= NIGHT_END) {
            return campIfDue(context, pilgrim, encoded, time);
        }
        clearCampedDay(pilgrim, time);

        Location rallyTarget = rallyPoint(pilgrim, waypoint);
        if (rallyTarget == null) {
            return false;
        }
        marchBurst(context, pilgrim, rallyTarget);
        return true;
    }

    /**
     * Leaders march toward the waypoint; followers march toward their leader
     * until regrouped (then idle). A dead leader is replaced by the evaluating
     * follower so the crew never strands.
     */
    private Location rallyPoint(Mob pilgrim, Location waypoint) {
        Mob leader = resolveLeader(pilgrim);
        if (leader == pilgrim) {
            return waypoint;
        }
        double distanceToLeader = pilgrim.getLocation().distance(leader.getLocation());
        return distanceToLeader > REGROUP_RADIUS ? leader.getLocation() : null;
    }

    /**
     * The crew's leader (PDC-stored): dead/gone leaders are replaced by the
     * evaluating villager so the crew always has one.
     */
    private Mob resolveLeader(Mob pilgrim) {
        String leaderId = pilgrim.getPersistentDataContainer().get(leaderKey, PersistentDataType.STRING);
        if (leaderId != null) {
            Entity leader = null;
            try {
                leader = Bukkit.getEntity(java.util.UUID.fromString(leaderId));
            } catch (IllegalArgumentException brokenId) {
                leader = null;
            }
            if (leader != null && !leader.isDead() && leader instanceof Villager
                    && leader != pilgrim) {
                return (Mob) leader;
            }
        }
        pilgrim.getPersistentDataContainer().set(leaderKey, PersistentDataType.STRING,
                pilgrim.getUniqueId().toString());
        return pilgrim;
    }

    /** A short burst of velocity hops toward the target - a trudge, not a teleport. */
    private void marchBurst(BehaviorContext context, Mob pilgrim, Location target) {
        Vector course = target.toVector().subtract(pilgrim.getLocation().toVector()).setY(0);
        if (course.lengthSquared() < 1.0E-4) {
            return;
        }
        course = course.normalize().multiply(HOP_SPEED);
        for (int hop = 0; hop < HOPS_PER_BURST; hop++) {
            Vector push = course.clone();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (pilgrim.isDead() || !pilgrim.isValid()) {
                    return;
                }
                pilgrim.setVelocity(new Vector(push.getX(), hopLift(pilgrim, push), push.getZ()));
            }, (long) hop * HOP_DELAY_TICKS);
        }
    }

    /**
     * Vertical kick for one hop: a normal trudge receives a small lift; when
     * the block ahead is a one-block step with air above, the lift is high
     * enough to clear it, so real terrain (gentle slopes, river banks) does
     * not strand the crew.
     */
    private static double hopLift(Mob pilgrim, Vector push) {
        Block foot = pilgrim.getLocation().getBlock();
        int dx = Math.abs(push.getX()) >= Math.abs(push.getZ()) ? (push.getX() >= 0 ? 1 : -1) : 0;
        int dz = dx == 0 ? (push.getZ() >= 0 ? 1 : -1) : 0;
        Block ahead = foot.getRelative(dx, 0, dz);
        if (!ahead.isPassable() && ahead.getRelative(BlockFace.UP).isPassable()
                && ahead.getRelative(BlockFace.UP, 2).isPassable()) {
            return HOP_LIFT_STEP;
        }
        return HOP_LIFT;
    }

    /**
     * Night: hold position; raise ONE crew camp per night. Only the leader
     * camps: it collects the nearby crew (journey villagers sharing the same
     * destination), places a single campfire plus a side-by-side bed for each
     * crew member, and marks the whole crew's camped-day PDC, so followers
     * never raise their own scattered campfires. Followers simply hold still.
     */
    private boolean campIfDue(BehaviorContext context, Mob pilgrim, String encoded, WorldTime time) {
        if (resolveLeader(pilgrim) != pilgrim) {
            return false;
        }
        long campedDay = pilgrim.getPersistentDataContainer()
                .getOrDefault(campedDayKey, PersistentDataType.LONG, -1L);
        if (campedDay == time.day) {
            return false;
        }
        if (context.countNearby(Material.CAMPFIRE, CAMP_GUARD_RADIUS, 2, 1) > 0) {
            // A camp already stands by the crew (e.g. from before a restart):
            // mark the crew camped instead of raising a second fire.
            List<Villager> crew = crewNear(pilgrim, encoded);
            markCrewCamped(crew, time);
            return false;
        }
        Block campfireSpot = nearestCampSpot(context.footBlock());
        if (campfireSpot == null) {
            DebugLogger.debug("[MobFun] pilgrim leader %s found no camp spot at night (day %d)",
                    pilgrim.getUniqueId(), time.day);
            return false;
        }
        BlockData campfireData = org.bukkit.Bukkit.createBlockData(Material.CAMPFIRE);
        boolean placedFire = context.trySetBlock(campfireSpot, campfireData);
        if (!placedFire) {
            // Edit budget exhausted (or the world refused the write): leave the
            // crew unmarked so the camp retries on a later engine roll instead
            // of stranding a bed row without its fire.
            return false;
        }
        playCamp(context, campfireSpot);
        List<Villager> crew = crewNear(pilgrim, encoded);
        int bedsPlaced = placeBedRow(context, campfireSpot, Math.min(Math.max(crew.size(), 1), CREW_BED_CAP));
        markCrewCamped(crew, time);
        DebugLogger.debug("[MobFun] pilgrim leader %s raised the crew camp (day %d): crew=%d,"
                + " beds=%d at %d %d %d", pilgrim.getUniqueId(), time.day, crew.size(), bedsPlaced,
                campfireSpot.getX(), campfireSpot.getY(), campfireSpot.getZ());
        return true;
    }

    /** The crew: this pilgrim plus nearby journey villagers on the same destination. */
    private List<Villager> crewNear(Mob pilgrim, String encoded) {
        List<Villager> crew = new ArrayList<>();
        if (pilgrim instanceof Villager leader) {
            crew.add(leader);
        }
        for (Entity entity : pilgrim.getNearbyEntities(CREW_RADIUS, CREW_RADIUS, CREW_RADIUS)) {
            if (!(entity instanceof Villager villager) || isBuilder(villager)) {
                continue;
            }
            String otherDestination = villager.getPersistentDataContainer()
                    .get(destinationKey, PersistentDataType.STRING);
            if (encoded != null && encoded.equals(otherDestination)) {
                crew.add(villager);
            }
        }
        return crew;
    }

    /** Marks the crew's camped-day so no member re-camps (or re-fires) tonight. */
    private void markCrewCamped(List<Villager> crew, WorldTime time) {
        for (Villager member : crew) {
            member.getPersistentDataContainer().set(campedDayKey, PersistentDataType.LONG, time.day);
        }
    }

    /**
     * A side-by-side row of beds next to the campfire: each bed starts two
     * blocks out from the fire and points away from it, beds sit adjacent
     * along one lateral axis (spilling to the opposite side to keep the row
     * contiguous), one bed per crew member. Best effort after cap: check
     * both blocks of every bed before any write so budget exhaustion never
     * leaves half a bed behind.
     */
    private int placeBedRow(BehaviorContext context, Block campfireSpot, int bedsWanted) {
        int bestPlaced = 0;
        for (BlockFace out : List.of(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)) {
            BlockFace side = lateral(out);
            Block firstFoot = campfireSpot.getRelative(out, 2);
            int placed = 0;
            for (int slot = 0; slot < bedsWanted; slot++) {
                // Positive side first (0, +1, +2 ...), then fill the remaining
                // contiguous slots on the negative side.
                Block foot = side == null ? null : firstFoot.getRelative(side, slot);
                if (foot == null || !placeSingleBed(context, foot, out)) {
                    break;
                }
                placed++;
            }
            if (placed < bedsWanted) {
                int extra = 0;
                while (placed < bedsWanted) {
                    extra++;
                    Block foot = firstFoot.getRelative(side, -extra);
                    if (!placeSingleBed(context, foot, out)) {
                        break;
                    }
                    placed++;
                }
            }
            if (placed > bestPlaced) {
                bestPlaced = placed;
            }
            if (bestPlaced >= bedsWanted) {
                break;
            }
        }
        return bestPlaced;
    }

    /** Places one bed (foot at {@code foot}, head one block toward {@code out}). */
    private boolean placeSingleBed(BehaviorContext context, Block foot, BlockFace out) {
        Block head = foot.getRelative(out);
        if (!isValidCampBlock(foot) || !isValidCampBlock(head)) {
            return false;
        }
        Bed footData = (Bed) org.bukkit.Bukkit.createBlockData(Material.CYAN_BED);
        footData.setFacing(out);
        footData.setPart(Bed.Part.FOOT);
        Bed headData = (Bed) org.bukkit.Bukkit.createBlockData(Material.CYAN_BED);
        headData.setFacing(out);
        headData.setPart(Bed.Part.HEAD);
        // Both blocks pre-validated; the only way the second write fails is
        // budget exhaustion, in which case the foot is rolled back through
        // the same budget.
        if (context.trySetBlock(foot, footData) && context.trySetBlock(head, headData)) {
            return true;
        }
        if (foot.getType() == Material.CYAN_BED) {
            context.tryBreak(foot, false);
        }
        return false;
    }

    /** 90-degree clockwise lateral of a cardinal face (never diagonal). */
    private static BlockFace lateral(BlockFace face) {
        return switch (face) {
            case NORTH -> BlockFace.EAST;
            case EAST -> BlockFace.SOUTH;
            case SOUTH -> BlockFace.WEST;
            case WEST -> BlockFace.NORTH;
            default -> null;
        };
    }

    /** Ring-1 solid, low, uncovered ground for the campfire. */
    private Block nearestCampSpot(Block foot) {
        for (BlockFace face : List.of(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
            Block spot = foot.getRelative(face);
            if (isValidCampBlock(spot)) {
                return spot;
            }
        }
        return null;
    }

    private static boolean isValidCampBlock(Block block) {
        if (!block.isPassable() || block.isLiquid()) {
            return false;
        }
        Block ground = block.getRelative(BlockFace.DOWN);
        return ground.getType().isSolid()
                && !Tag.LEAVES.isTagged(ground.getType())
                && !Tag.LOGS.isTagged(ground.getType());
    }

    private void playCamp(BehaviorContext context, Block campfireSpot) {
        Location center = campfireSpot.getLocation().add(0.5, 0.5, 0.5);
        context.world().playSound(center, Sound.BLOCK_CAMPFIRE_CRACKLE, 1.0f, 1.0f);
        context.world().spawnParticle(Particle.SMOKE, center.clone().add(0, 0.6, 0), 4, 0.2, 0.2, 0.2, 0.01);
    }

    private void celebrateArrival(BehaviorContext context, Mob pilgrim) {
        Location center = pilgrim.getLocation().add(0, 1, 0);
        context.world().playSound(center, Sound.ENTITY_VILLAGER_CELEBRATE, 1.0f, 1.0f);
        context.world().spawnParticle(Particle.HAPPY_VILLAGER, center, 8, 0.4, 0.6, 0.4, 0.02);
    }

    /**
     * One human-readable line per active journey (villager carrying the
     * journey PDC): leader or follower, target, distance remaining, crew
     * camped state, and day/night mode. Bounded so a village full of
     * pilgrims cannot flood the command output.
     */
    public List<String> describeJourneys() {
        List<String> lines = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                String encoded = villager.getPersistentDataContainer()
                        .get(destinationKey, PersistentDataType.STRING);
                if (encoded == null) {
                    continue;
                }
                Location waypoint = decode(encoded);
                if (waypoint == null) {
                    lines.add("  " + villager.getUniqueId() + ": broken destination state (will reset)");
                    continue;
                }
                String leaderId = villager.getPersistentDataContainer()
                        .get(leaderKey, PersistentDataType.STRING);
                boolean leader = villager.getUniqueId().toString().equals(leaderId);
                long dayTicks = world.getFullTime() % 24000L;
                String mode = dayTicks >= NIGHT_START && dayTicks <= NIGHT_END
                        ? "night camp" : "day march";
                lines.add(String.format("  %s %s -> %d %d %d (%s), dist %d, %s",
                        leader ? "leader  " : "follower",
                        villager.getUniqueId().toString().substring(0, 8),
                        waypoint.getBlockX(), waypoint.getBlockY(), waypoint.getBlockZ(),
                        waypoint.getWorld().getName(),
                        (int) villager.getLocation().distance(waypoint), mode));
                if (lines.size() >= JOURNEY_STATUS_LINES) {
                    lines.add("  ... (more journeys omitted)");
                    return lines;
                }
            }
        }
        return lines;
    }

    private void clearJourney(Mob pilgrim) {
        pilgrim.getPersistentDataContainer().remove(destinationKey);
        pilgrim.getPersistentDataContainer().remove(leaderKey);
        pilgrim.getPersistentDataContainer().remove(campedDayKey);
    }

    private void clearCampedDay(Mob pilgrim, WorldTime time) {
        Long campedDay = pilgrim.getPersistentDataContainer().get(campedDayKey, PersistentDataType.LONG);
        if (campedDay != null && campedDay != time.day) {
            pilgrim.getPersistentDataContainer().remove(campedDayKey);
        }
    }

    private boolean isBuilder(Mob villager) {
        Byte tagged = villager.getPersistentDataContainer().get(builderKey, PersistentDataType.BYTE);
        return tagged != null && tagged == (byte) 1;
    }

    private WorldTime worldTime(BehaviorContext context) {
        long fullTime = context.world().getFullTime();
        return new WorldTime(fullTime / 24000L, fullTime % 24000L, context.world().getGameTime());
    }

    private Location locateVillage(BehaviorContext context, Mob pilgrim) {
        Location found = context.world().locateNearestStructure(pilgrim.getLocation(),
                StructureType.VILLAGE, SEARCH_RADIUS_CHUNKS, true);
        return found == null ? null : new Location(found.getWorld(), found.getBlockX(), found.getBlockY(),
                found.getBlockZ());
    }

    private static String encode(Location location) {
        return location.getWorld().getName() + ";" + location.getBlockX() + ";" + location.getBlockY()
                + ";" + location.getBlockZ();
    }

    private static Location decode(String encoded) {
        try {
            String[] parts = encoded.split(";");
            org.bukkit.World world = Bukkit.getWorld(parts[0]);
            if (world == null) {
                return null;
            }
            return new Location(world, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                    Integer.parseInt(parts[3]));
        } catch (RuntimeException brokenState) {
            return null;
        }
    }

    private record WorldTime(long day, long dayTicks, long gameTime) {
    }
}
