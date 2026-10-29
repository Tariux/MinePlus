package com.mineplus.infrastructure.mob.action;

import com.mineplus.infrastructure.mob.BehaviorAction;
import com.mineplus.infrastructure.mob.BehaviorContext;
import com.mineplus.infrastructure.mob.WorldTimes;
import com.mineplus.infrastructure.mob.journey.JourneyRunner;
import com.mineplus.infrastructure.mob.journey.JourneyStatus;
import com.mineplus.infrastructure.mob.navigation.GroundProbe;
import com.mineplus.infrastructure.mob.navigation.MobAttributes;
import com.mineplus.infrastructure.mob.navigation.MobNavigator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Villager;

/**
 * The generic pilgrim: a mob looks for the nearest structure of a type, rallies
 * the mobs of its kind around it into a crew, and marches the crew there —
 * through the night if it must, but as one group, and only as far as the goal it
 * was given.
 *
 * <p>This is the showcase-shaped version of everything that goes wrong when a
 * module drives long-distance AI by hand, so the Core does it instead:
 * <ul>
 *   <li><b>Finding the goal</b> goes through the engine's structure cache
 *       ({@link BehaviorContext#nearestStructure}) — escalating radius, one
 *       search per region per cooldown, "nothing here" cached too. A module
 *       calling {@code locateNearestStructure} itself produces either a
 *       per-roll worldgen storm or a silent permanent stall.</li>
 *   <li><b>Walking there</b> goes through {@link JourneyRunner}: the destination
 *       is reached one leg inside the mob's path range at a time, every waypoint
 *       normalised to standable ground, stalls detected and routed around. Hand
 *       a villager a raw village coordinate 300 blocks away and it stands still —
 *       which is exactly what "they never move toward a distant village" is.
 *       A crew walks the same way, one bounded step per member per roll, so a
 *       member is never handed a target its own pathing cannot reach.</li>
 *   <li><b>Moving as a group</b> goes through the crew's <b>formation point</b>
 *       ({@link BehaviorContext#crewRally}): every member walks to it, and it
 *       advances along the route once the crew has caught up. A member is
 *       handed a single step toward it, bounded by its own path range, so a
 *       formation point a hundred blocks away is still a walk it can make —
 *       asking a mob to walk to a target beyond its path range returns "no
 *       path" and the mob never moves again.</li>
 *   <li><b>Never losing anybody</b> — three rules keep a march honest: the
 *       formation point waits for a straggler, but only for a bounded number
 *       of rolls; a member that has fallen too far behind to rejoin walks the
 *       goal on its own instead of chasing a formation point it can never
 *       catch; and a crew is <b>local</b>, so two villagers that start a
 *       hundred blocks apart walk as two crews instead of one crew that never
 *       moves because its slowest member is on the other side of the map.</li>
 *   <li><b>Owning the scenario</b> is an activity claim, so no second profile
 *       pulls the same villager somewhere else while it is mid-journey — and a
 *       profile with a higher priority can take that villager over, so a
 *       guardian can still answer an attack in the middle of a pilgrimage.</li>
 * </ul>
 *
 * <p>Night and arrival are the module's business — resting, building a camp,
 * celebrating is content, not mechanics — so they surface as
 * {@link Callbacks} at the moments the Core knows they happened. {@link Callbacks#onNight}
 * is invoked once per in-game night with the crew's camp site; {@link Callbacks#onArrival}
 * once when the goal is reached.
 *
 * <p>Thread model: main thread only, like every action.
 */
public final class JourneyToStructureAction implements BehaviorAction {

    /** The scenario's moments that belong to the module. */
    public interface Callbacks {

        /**
         * Night fell on the march. The crew is halted at {@code campSite};
         * build the camp here. Called once per in-game night for the crew —
         * and, when this returns {@code false}, again on the next engine roll
         * of the same night, so a camp the edit budget could not afford does
         * not silently cost the crew its night.
         *
         * @return {@code true} when the camp was raised
         */
        default boolean onNight(BehaviorContext context, Location campSite) {
            return true;
        }

        /**
         * Day returned and the crew is leaving its camp. Remove the camp here;
         * returning {@code false} retries on the next engine roll, so an
         * exhausted edit budget delays the march instead of abandoning blocks.
         */
        default boolean onDawn(BehaviorContext context, Location campSite) {
            return true;
        }

        /** The crew reached {@code destination}. Called once, for the leader. */
        default void onArrival(BehaviorContext context, Location destination) {
        }

        /**
         * The march found nothing to march to within
         * {@link Builder#searchRadiusChunks()} — a barren region. Called at most
         * once per search cooldown, which is what keeps an empty wasteland from
         * being re-probed forever.
         */
        default void onNotFound(BehaviorContext context) {
        }
    }

    /**
     * Builds the scenario. Everything has a working default, so a module that
     * only wants "walk to the nearest village" writes two lines.
     */
    public static final class Builder {

        private String id = "journey_to_structure";
        private String structure = "village";
        private int searchRadiusChunks = 64;
        private double arrivalDistanceXZ = 24.0;
        private double cohesionRadius = 10.0;
        private double recruitRadius = 16.0;
        private double marchLeg = 32.0;
        private double gatherRadius = 6.0;
        private double detachRadius = 64.0;
        private boolean restAtNight = true;
        private boolean groupTravel = true;
        private String crewName = "pilgrims";
        private com.mineplus.infrastructure.mob.group.Formation formation;
        private JourneyRunner.Options stride = JourneyRunner.Options.defaults();
        private Callbacks callbacks = new Callbacks() {
        };

        /** The action id — also the namespace its crews and claims live under. */
        public Builder id(String value) {
            this.id = value == null || value.isBlank() ? "journey_to_structure" : value;
            return this;
        }

        /** Registry name of the goal, e.g. {@code village}, {@code ocean_monument}. */
        public Builder structure(String value) {
            this.structure = value == null ? "village" : value.toLowerCase(Locale.ROOT);
            return this;
        }

        /** How far the search may reach, in chunks. */
        public Builder searchRadiusChunks(int value) {
            this.searchRadiusChunks = Math.max(4, Math.min(256, value));
            return this;
        }

        /** Horizontal distance at which the goal counts as reached. */
        public Builder arrivalDistance(double value) {
            this.arrivalDistanceXZ = Math.max(2.0, value);
            return this;
        }

        /** How far the crew may be spread before the march waits for it. */
        public Builder cohesionRadius(double value) {
            this.cohesionRadius = Math.max(1.0, value);
            return this;
        }

        /** How wide the crew recruits on the first roll ({@code 0} to travel alone). */
        public Builder recruitRadius(double value) {
            this.recruitRadius = Math.max(0.0, value);
            return this;
        }

        /**
         * How far the formation point advances along the route in one step.
         * It has to stay inside the mob's path range: the members walk from
         * where they are, not from the last formation point.
         */
        public Builder marchLeg(double value) {
            this.marchLeg = Math.max(4.0, Math.min(48.0, value));
            return this;
        }

        /** How close the crew must be to the formation point before it camps. */
        public Builder gatherRadius(double value) {
            this.gatherRadius = Math.max(1.0, value);
            return this;
        }

        /**
         * How far a member may fall behind the formation point before it stops
         * chasing it and walks the goal on its own. Beyond this the crew is a
         * guide the member cannot follow, and a pilgrim that keeps chasing a
         * formation point it can never reach is a pilgrim that never arrives.
         */
        public Builder detachRadius(double value) {
            this.detachRadius = Math.max(16.0, value);
            return this;
        }

        /** {@code false} to march around the clock. */
        public Builder restAtNight(boolean value) {
            this.restAtNight = value;
            return this;
        }

        /** {@code false} to march alone, without rallying a crew. */
        public Builder groupTravel(boolean value) {
            this.groupTravel = value;
            return this;
        }

        /**
         * The crew's name — the key its members, rally point and claims are
         * filed under. Two journeys sharing a name share one crew, so a
         * scenario that must not be joined by a foreign mob needs its own.
         */
        public Builder crewName(String value) {
            this.crewName = value == null || value.isBlank() ? "pilgrims" : value;
            return this;
        }

        /** Stride, patience and leg length of the walk. */
        public Builder stride(JourneyRunner.Options value) {
            this.stride = value == null ? JourneyRunner.Options.defaults() : value;
            return this;
        }

        /**
         * The shape the crew walks in: a wedge behind the leader on a march, a
         * column through a cave, a pincer around a gate. Members keep their own
         * slots around the formation point, so the group crosses a doorway in a
         * line instead of piling onto one block.
         */
        public Builder formation(com.mineplus.infrastructure.mob.group.Formation value) {
            this.formation = value;
            return this;
        }

        public Builder callbacks(Callbacks value) {
            this.callbacks = value == null ? new Callbacks() {
            } : value;
            return this;
        }

        public JourneyToStructureAction build() {
            return new JourneyToStructureAction(this);
        }
    }

    /**
     * How many rolls the crew waits for a straggler before the march moves on
     * without it. A member that cannot reach the formation point — walled off,
     * stuck in a hole, pathing across water — must not freeze the march for
     * everybody, and must not be abandoned silently either: the crew waits a
     * while first. One roll is one engine pass over the mob, so this is a
     * couple of seconds of grace, not a minute.
     */
    private static final int STRAGGLER_PATIENCE = 20;

    /**
     * How many rolls the crew waits to gather before it camps without the last
     * member. A camp is for everybody, so the crew waits — but a member that
     * cannot reach the camp site must not cost the others their night.
     */
    private static final int GATHER_PATIENCE = 20;

    /**
     * How many rolls the leader waits at the goal for the last members. The
     * leader stops at the village and holds the arrival while the others close
     * in; after this the march ends without them, because a leader that waits
     * forever is a leader that never celebrates.
     */
    private static final int ARRIVAL_PATIENCE = 120;

    /** A scenario renews its claim for this many ticks on every fire. */
    private static final long CLAIM_RENEW_TICKS = 120L;

    private final String id;
    private final String structure;
    private final int searchRadiusChunks;
    private final double arrivalDistanceXZ;
    private final double cohesionRadius;
    private final double recruitRadius;
    private final double marchLeg;
    private final double gatherRadius;
    private final double detachRadius;
    private final boolean restAtNight;
    private final boolean groupTravel;
    private final String crewName;
    private final com.mineplus.infrastructure.mob.group.Formation formation;
    private final JourneyRunner.Options stride;
    private final Callbacks callbacks;

    /**
     * Which in-game night each crew last camped, so a night is a moment, not a
     * loop. Keyed by the crew's identity: two local crews of one scenario
     * camp on their own nights.
     */
    private final Map<String, Long> campedNight = new ConcurrentHashMap<>();
    /** Where each crew's current camp stands, so dawn can clear the right site. */
    private final Map<String, Location> campSites = new ConcurrentHashMap<>();
    /** Which in-game day each leader last searched fruitlessly, for {@link Callbacks#onNotFound}. */
    private final Map<UUID, Long> reportedEmpty = new ConcurrentHashMap<>();
    /** How many rolls each crew has already waited for a straggler. */
    private final Map<String, Integer> marchWait = new ConcurrentHashMap<>();
    /** How many rolls each crew has already waited to gather for the night. */
    private final Map<String, Integer> gatherWait = new ConcurrentHashMap<>();
    /** How many rolls each crew has already waited at the goal for its members. */
    private final Map<String, Integer> arrivalWait = new ConcurrentHashMap<>();

    private JourneyToStructureAction(Builder builder) {
        this.id = builder.id;
        this.structure = builder.structure;
        this.searchRadiusChunks = builder.searchRadiusChunks;
        this.arrivalDistanceXZ = builder.arrivalDistanceXZ;
        this.cohesionRadius = builder.cohesionRadius;
        this.recruitRadius = builder.recruitRadius;
        this.marchLeg = builder.marchLeg;
        this.gatherRadius = builder.gatherRadius;
        this.detachRadius = builder.detachRadius;
        this.restAtNight = builder.restAtNight;
        this.groupTravel = builder.groupTravel;
        this.crewName = builder.crewName;
        this.formation = builder.formation;
        this.stride = builder.stride;
        this.callbacks = builder.callbacks;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public boolean execute(BehaviorContext context) {
        if (!(context.entity() instanceof Mob walker)
                || !context.isChunkLoaded(context.footBlock())) {
            return false;
        }
        World world = context.world();
        // The time of day, read the one way every server agrees on: a march
        // that reads the world age rests at noon and walks at midnight.
        boolean night = restAtNight && WorldTimes.isNight(world);
        long day = WorldTimes.dayIndex(world);

        Location goal = resolveGoal(context, walker, day);
        if (goal == null) {
            return false;
        }

        // Own the mob for as long as the march lasts. Renewed every fire, so a
        // scenario that stops firing stops owning it too.
        context.claimActivity(id, goal, 0.0, CLAIM_RENEW_TICKS);

        if (night) {
            return rest(context, walker, day);
        }
        if (!resumeAfterNight(context, walker, crewId(context))) {
            return true;
        }

        if (arrived(context, walker, goal)) {
            return complete(context, walker, goal);
        }

        if (!groupTravel) {
            JourneyStatus status = context.advanceJourney(goal, stride);
            return switch (status) {
                case ARRIVED -> {
                    finish(context, walker, goal);
                    yield true;
                }
                case NO_CAPACITY -> false;
                // Walking, returning, detouring, blocked or stuck all mean the same
                // thing to the engine: the scenario did something on this roll.
                default -> true;
            };
        }
        return march(context, walker, goal);
    }

    /**
     * The crew walks as one.
     *
     * <p>A formation point carries the group along the route: every member
     * walks to it, and it advances once the whole crew has arrived. That is
     * what turns "each villager finds its own way to the village" into a
     * march.
     *
     * <p>Every member — the leader included — walks through
     * {@link JourneyRunner}, the same long-distance machinery the solo march
     * uses: legs inside the mob's own path range, waypoints normalised to
     * standable ground, progress sampled and stalls routed around. The old
     * shape of this method handed each member one raw path step at the
     * formation point; a villager whose step landed in a wall, a river or a
     * hill simply stood there, and the crew piled up "in front of each other"
     * while the formation waited for a member that could never arrive. The
     * journey runner either gets the member there or reports the leg stuck, and
     * a stuck formation is abandoned in favour of walking the goal directly.
     */
    private boolean march(BehaviorContext context, Mob walker, Location goal) {
        Location rally = ensureRally(context, walker);
        String crew = crewId(context);
        // The formation point is advanced by the leader's own path range: a
        // rally step the leader cannot path to is a step the crew never takes.
        double leg = Math.min(marchLeg, MobAttributes.pathRange(walker));
        if (context.isCrewLeader(crewName)) {
            if (context.crewSpread(crewName) <= cohesionRadius) {
                // The crew is together: the formation point moves a whole leg.
                advanceRally(context, rally, goal, leg);
                marchWait.remove(crew);
            } else {
                // The crew is spread out: the formation point waits for the
                // straggler — but only for so long. A member that cannot reach
                // it must not freeze the march for everybody, so after the
                // patience the march creeps on with a shorter leg.
                int waited = marchWait.merge(crew, 1, Integer::sum);
                if (waited >= STRAGGLER_PATIENCE) {
                    advanceRally(context, rally, goal, leg * 0.5);
                    marchWait.put(crew, 0);
                }
            }
        }
        // Too far behind to rejoin: the member walks the goal on its own. It
        // rejoins the formation when it catches up, but it is never lost.
        boolean detached = distanceXZ(walker.getLocation(), rally) > detachRadius;
        JourneyStatus status = context.advanceJourney(detached ? goal : rally, stride);
        if (status == JourneyStatus.STUCK && !detached) {
            // The formation point itself is unreachable from here (a river, a
            // wall, a cliff beyond the mob's path range). Chasing it is what
            // parks a member in place for the rest of the march; walk the goal
            // instead and let the crew's own patience move on.
            context.abandonJourney();
            context.advanceJourney(goal, stride);
        }
        return true;
    }

    /** The crew's formation point, created at the walker's feet on first use. */
    private Location ensureRally(BehaviorContext context, Mob walker) {
        Location rally = context.crewRally(crewName);
        if (rally != null) {
            return rally;
        }
        context.setCrewRally(crewName, walker.getLocation());
        rally = context.crewRally(crewName);
        return rally != null ? rally : walker.getLocation();
    }

    /**
     * Moves the formation point one leg along the route toward the goal,
     * normalised to standable ground. A point the mob cannot stand on is a
     * point the mob never reaches.
     */
    private void advanceRally(BehaviorContext context, Location rally, Location goal, double leg) {
        if (rally.getWorld() == null || goal.getWorld() != rally.getWorld()) {
            return;
        }
        double dx = goal.getX() - rally.getX();
        double dz = goal.getZ() - rally.getZ();
        double length = Math.hypot(dx, dz);
        if (length < 1.0e-4) {
            return;
        }
        double scale = Math.min(1.0, leg / length);
        Location next = new Location(rally.getWorld(),
                rally.getX() + dx * scale, rally.getY(), rally.getZ() + dz * scale);
        // The formation point follows a walkable line, not a straight one: a
        // rally stepped onto a lake or the far side of a wall is a rally the
        // whole crew then tries to path to, and one member after another
        // reports it stuck. Routing the step keeps the group on ground both
        // the leader and its followers can walk.
        Location routed = com.mineplus.infrastructure.mob.route.RoutePlanner
                .waypoint(rally.getWorld(), rally, goal, leg);
        Location candidate = routed != null ? routed : next;
        Location standable = GroundProbe.standableNear(candidate, 3, 6);
        context.setCrewRally(crewName, standable != null ? standable : candidate);
    }

    /**
     * Night: the crew closes ranks around the formation point and the leader
     * raises the camp once they are all there.
     *
     * <p>Rejoining outranks resting. Halting every member exactly where dusk
     * caught it is what makes a "camp" one villager per horizon, so a straggler
     * is walked back to the crew first and only a cohesive crew stops — and
     * only a gathered crew gets a camp raised for it.
     */
    private boolean rest(BehaviorContext context, Mob walker, long night) {
        String crew = crewId(context);
        if (!groupTravel) {
            context.stopNavigation();
            if (Long.valueOf(night).equals(campedNight.get(crew))) {
                return true;
            }
            Location site = walker.getLocation().clone();
            if (callbacks.onNight(context, site)) {
                campedNight.put(crew, night);
                campSites.put(crew, site);
            }
            return true;
        }
        Location rally = ensureRally(context, walker);
        boolean leader = context.isCrewLeader(crewName);
        // The leader joins the crew as well: the formation point is where the
        // camp stands, and the camp is for everybody. Gathering goes through
        // the journey runner, so a straggler blocked by terrain is routed
        // around it instead of being left to bump into the same wall all night.
        context.advanceJourney(rally, stride);
        if (!leader) {
            return true; // followers gather; the leader owns the camp
        }
        if (context.crewSpread(crewName) > gatherRadius) {
            Integer waited = gatherWait.get(crew);
            if (waited == null || waited < GATHER_PATIENCE) {
                gatherWait.merge(crew, 1, Integer::sum);
                return true; // still waiting for the crew to arrive
            }
        }
        gatherWait.remove(crew);
        context.stopNavigation();
        if (Long.valueOf(night).equals(campedNight.get(crew))) {
            return true;
        }
        // The night is only spent once the camp actually stands: a camp the
        // edit budget could not afford retries on the next roll of the same
        // night instead of writing the crew's campfire off entirely.
        if (callbacks.onNight(context, rally)) {
            campedNight.put(crew, night);
            campSites.put(crew, rally.clone());
        }
        return true;
    }

    /**
     * The first daytime roll after a camp: wake sleeping members and clear the
     * camp. A failed teardown (an exhausted edit budget) holds the march for a
     * roll rather than leaving beds and a fire behind on the route.
     */
    private boolean resumeAfterNight(BehaviorContext context, Mob walker, String crew) {
        Long camped = campedNight.get(crew);
        if (camped == null) {
            return true;
        }
        if (groupTravel) {
            for (Mob member : context.crewMembers(crewName)) {
                if (member instanceof Villager villager) {
                    MobNavigator.wakeUp(villager);
                }
            }
        } else if (walker instanceof Villager villager) {
            MobNavigator.wakeUp(villager);
        }
        Location site = campSites.get(crew);
        if (site == null) {
            site = groupTravel ? context.crewRally(crewName) : walker.getLocation();
        }
        if (site == null) {
            site = walker.getLocation();
        }
        if (!callbacks.onDawn(context, site)) {
            return false;
        }
        campedNight.remove(crew, camped);
        campSites.remove(crew);
        return true;
    }

    /**
     * The crew's destination: the goal it already shares, or a freshly located
     * structure. Locating is the expensive half, so only the leader asks and the
     * answer is written onto the crew — and only through the engine's cache, so
     * a whole wasteland of pilgrims costs one worldgen sweep per cooldown.
     *
     * <p>A crew is local: the walker joins the nearest crew of this name (or
     * founds one where it stands), so two villagers that start a hundred blocks
     * apart march as two crews and both arrive, instead of joining one crew
     * whose formation point neither of them can ever reach.
     */
    private Location resolveGoal(BehaviorContext context, Mob walker, long day) {
        if (!groupTravel) {
            Location walking = context.journeyDestination();
            return walking != null ? walking : search(context, walker, day);
        }
        // The crew this walker belongs to already knows where it goes.
        Location shared = context.crewGoal(crewName);
        if (shared != null) {
            return shared;
        }
        // Company nearby? Joining it saves this mob a structure search of its
        // own — and inherits the goal the crew already paid for.
        if (context.joinNearestCrew(crewName)) {
            shared = context.crewGoal(crewName);
            if (shared != null) {
                return shared;
            }
            if (!context.isCrewLeader(crewName)) {
                // A member of a crew that has not found its goal yet waits for
                // its leader instead of paying for the same search twice.
                return null;
            }
        }
        Location found = search(context, walker, day);
        if (found == null) {
            return null;
        }
        context.joinCrew(crewName, walker, found);
        int joined = recruitRadius > 0
                ? context.rallyCrew(crewName, recruitRadius, found)
                : 0;
        if (formation != null) {
            // Every crew is shaped the moment it knows where it goes: members
            // joining later inherit the formation with their membership.
            context.setCrewFormation(crewName, formation);
        }
        if (com.mineplus.util.DebugLogger.isEnabled()) {
            com.mineplus.util.DebugLogger.debug("[mob-journey] %s found %s at (%d, %d, %d), crew %d",
                    walker.getType(), structure, found.getBlockX(), found.getBlockY(), found.getBlockZ(),
                    joined + 1);
        }
        return found;
    }

    private Location search(BehaviorContext context, Mob walker, long day) {
        Location found = context.nearestStructure(structure, searchRadiusChunks);
        if (found == null) {
            Long last = reportedEmpty.get(walker.getUniqueId());
            if (last == null || last != day) {
                reportedEmpty.put(walker.getUniqueId(), day);
                callbacks.onNotFound(context);
            }
            return null;
        }
        double distance = JourneyRunner.distanceXZ(context.entity().getLocation(), found);
        if (distance <= arrivalDistanceXZ) {
            // Already home: a journey to the village the mob is standing in is
            // not a journey, and claiming the mob for it would freeze its other
            // behaviors for nothing.
            return null;
        }
        return found;
    }

    private boolean arrived(BehaviorContext context, Mob walker, Location goal) {
        if (goal.getWorld() != context.world()) {
            return false;
        }
        return JourneyRunner.distanceXZ(walker.getLocation(), goal) <= arrivalDistanceXZ;
    }

    /**
     * The goal is reached — but the crew arrives together: the leader waits at
     * the goal for the members still on the road, so the arrival is a group
     * arrival and not one villager celebrating alone.
     */
    private boolean complete(BehaviorContext context, Mob walker, Location goal) {
        String crew = crewId(context);
        if (groupTravel && !context.isCrewLeader(crewName)) {
            // Each follower ends its own claim as it arrives; while the leader
            // waits at the goal there is nothing left for it to march.
            finish(context, walker, goal);
            return true;
        }
        if (groupTravel && context.crewSpread(crewName) > arrivalDistanceXZ) {
            Integer waited = arrivalWait.get(crew);
            if (waited == null || waited < ARRIVAL_PATIENCE) {
                arrivalWait.merge(crew, 1, Integer::sum);
                return true; // still waiting for the last members
            }
        }
        finish(context, walker, goal);
        arrivalWait.remove(crew);
        return true;
    }

    /** The march is over: stop owning the mob and tell the module. */
    private void finish(BehaviorContext context, Mob walker, Location goal) {
        String crew = crewId(context);
        context.abandonJourney();
        context.stopNavigation();
        boolean leader = !groupTravel || context.isCrewLeader(crewName);
        if (leader) {
            callbacks.onArrival(context, goal);
            // Disbanding takes the whole crew with it. Release every member's
            // claim first: an arrival must not leave a straggler frozen under
            // a journey activity it can no longer finish.
            context.stopCrew(crewName, id);
            context.disbandCrew(crewName);
        } else {
            context.leaveCrew(crewName);
        }
        campedNight.remove(crew);
        campSites.remove(crew);
        marchWait.remove(crew);
        gatherWait.remove(crew);
        arrivalWait.remove(crew);
        context.releaseActivity(id);
        reportedEmpty.remove(walker.getUniqueId());
        if (com.mineplus.util.DebugLogger.isEnabled()) {
            com.mineplus.util.DebugLogger.debug("[mob-journey] %s arrived at %s (%d, %d, %d)",
                    walker.getType(), structure, goal.getBlockX(), goal.getBlockY(), goal.getBlockZ());
        }
    }

    /**
     * The identity of the crew this mob belongs to. Two local crews of one
     * scenario share a name but not an identity, and every per-crew counter
     * has to hang on the identity — otherwise the patience of one crew resets
     * the patience of another.
     */
    private String crewId(BehaviorContext context) {
        String key = context.crewId(crewName);
        return key != null ? key : id + ':' + crewName + ":solo";
    }

    private static double distanceXZ(Location from, Location to) {
        double dx = from.getX() - to.getX();
        double dz = from.getZ() - to.getZ();
        return Math.hypot(dx, dz);
    }
}
