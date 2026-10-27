package com.mineplus.infrastructure.mob.action;

import com.mineplus.infrastructure.mob.BehaviorAction;
import com.mineplus.infrastructure.mob.BehaviorContext;
import com.mineplus.infrastructure.mob.journey.JourneyRunner;
import com.mineplus.infrastructure.mob.journey.JourneyStatus;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mob;

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
 *       which is exactly what "they never move toward a distant village" is.</li>
 *   <li><b>Moving as a group</b> goes through crews ({@link BehaviorContext}):
 *       a straggler closes the gap to its leader before it works on the shared
 *       goal, so arrival looks like an arrival instead of one mob per horizon.</li>
 *   <li><b>Owning the scenario</b> is an activity claim, so no second profile
 *       pulls the same villager somewhere else while it is mid-journey.</li>
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
        private boolean restAtNight = true;
        private boolean groupTravel = true;
        private String crewName = "pilgrims";
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

        /** How far a member may stray before rejoining outranks marching. */
        public Builder cohesionRadius(double value) {
            this.cohesionRadius = Math.max(1.0, value);
            return this;
        }

        /** How wide the crew recruits on the first roll ({@code 0} to travel alone). */
        public Builder recruitRadius(double value) {
            this.recruitRadius = Math.max(0.0, value);
            return this;
        }

        /** The crew's name inside this scenario. */
        public Builder crewName(String value) {
            this.crewName = value == null || value.isBlank() ? "pilgrims" : value;
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

        /** Stride, patience and leg length of the walk. */
        public Builder stride(JourneyRunner.Options value) {
            this.stride = value == null ? JourneyRunner.Options.defaults() : value;
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

    /** Night in Minecraft time: the hours the crew rests instead of marching. */
    private static final long NIGHT_START_TICKS = 13000L;
    private static final long NIGHT_END_TICKS = 23000L;

    /** A scenario renews its claim for this many ticks on every fire. */
    private static final long CLAIM_RENEW_TICKS = 120L;

    private final String id;
    private final String structure;
    private final int searchRadiusChunks;
    private final double arrivalDistanceXZ;
    private final double cohesionRadius;
    private final double recruitRadius;
    private final boolean restAtNight;
    private final boolean groupTravel;
    private final String crewName;
    private final JourneyRunner.Options stride;
    private final Callbacks callbacks;

    /** Which in-game day each crew last camped, so a night is a moment, not a loop. */
    private final Map<String, Long> campedNight = new ConcurrentHashMap<>();
    /** Which in-game day each leader last searched fruitlessly, for {@link Callbacks#onNotFound}. */
    private final Map<UUID, Long> reportedEmpty = new ConcurrentHashMap<>();

    private JourneyToStructureAction(Builder builder) {
        this.id = builder.id;
        this.structure = builder.structure;
        this.searchRadiusChunks = builder.searchRadiusChunks;
        this.arrivalDistanceXZ = builder.arrivalDistanceXZ;
        this.cohesionRadius = builder.cohesionRadius;
        this.recruitRadius = builder.recruitRadius;
        this.restAtNight = builder.restAtNight;
        this.groupTravel = builder.groupTravel;
        this.crewName = builder.crewName;
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
        long day = world.getFullTime() / 24000L;
        long dayTicks = world.getFullTime() % 24000L;
        boolean night = restAtNight && dayTicks >= NIGHT_START_TICKS && dayTicks <= NIGHT_END_TICKS;

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
        campedNight.remove(crewKey());

        // A straggler rejoins before it marches: cohesion outranks progress.
        Location rejoin = groupTravel ? context.crewFocus(crewName, cohesionRadius) : null;
        if (rejoin != null) {
            context.navigateTo(rejoin, stride.speed());
            return true;
        }

        if (arrived(context, walker, goal)) {
            finish(context, walker, goal);
            return true;
        }

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

    /**
     * The crew's destination: the goal it already shares, or a freshly located
     * structure. Locating is the expensive half, so only the leader asks and the
     * answer is written onto the crew — and only through the engine's cache, so
     * a whole wasteland of pilgrims costs one worldgen sweep per cooldown.
     */
    private Location resolveGoal(BehaviorContext context, Mob walker, long day) {
        if (!groupTravel) {
            Location walking = context.journeyDestination();
            return walking != null ? walking : search(context, walker, day);
        }
        Location shared = context.crewGoal(crewName);
        if (shared != null) {
            return shared;
        }
        if (!context.isCrewLeader(crewName)) {
            // A member with no crew goal waits for its leader to find one
            // instead of paying for the same search twelve times.
            return null;
        }
        Location found = search(context, walker, day);
        if (found != null) {
            int joined = recruitRadius > 0
                    ? context.rallyCrew(crewName, recruitRadius, found)
                    : 0;
            context.joinCrew(crewName, walker, found);
            if (com.mineplus.util.DebugLogger.isEnabled()) {
                com.mineplus.util.DebugLogger.debug("[mob-journey] %s found %s at (%d, %d, %d), crew %d",
                        walker.getType(), structure, found.getBlockX(), found.getBlockY(), found.getBlockZ(),
                        joined + 1);
            }
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

    /**
     * Night: the crew closes ranks, then holds position, and the module gets its
     * one camp window.
     *
     * <p>Rejoining outranks resting. Halting every member exactly where dusk
     * caught it is what makes a "camp" one villager per horizon, so a straggler
     * is walked back to the crew first and only a cohesive crew stops.
     */
    private boolean rest(BehaviorContext context, Mob walker, long day) {
        if (groupTravel) {
            Location rejoin = context.crewFocus(crewName, cohesionRadius);
            if (rejoin != null) {
                context.navigateTo(rejoin, stride.speed());
                return true;
            }
        }
        context.stopNavigation();
        if (groupTravel && !context.isCrewLeader(crewName)) {
            return true; // followers rest; the leader owns the camp
        }
        if (Long.valueOf(day).equals(campedNight.get(crewKey()))) {
            return true;
        }
        // The night is only spent once the camp actually stands: a camp the
        // edit budget could not afford retries on the next roll of the same
        // night instead of writing the crew's campfire off entirely.
        if (callbacks.onNight(context, walker.getLocation())) {
            campedNight.put(crewKey(), day);
        }
        return true;
    }

    private boolean arrived(BehaviorContext context, Mob walker, Location goal) {
        if (goal.getWorld() != context.world()) {
            return false;
        }
        return JourneyRunner.distanceXZ(walker.getLocation(), goal) <= arrivalDistanceXZ;
    }

    /** The march is over: stop owning the mob and tell the module. */
    private void finish(BehaviorContext context, Mob walker, Location goal) {
        context.abandonJourney();
        context.stopNavigation();
        boolean leader = !groupTravel || context.isCrewLeader(crewName);
        if (leader) {
            callbacks.onArrival(context, goal);
            // Disbanding takes the whole crew with it, so the members see no
            // goal on their next roll and go back to their ordinary behaviors.
            context.disbandCrew(crewName);
        } else {
            context.leaveCrew(crewName);
        }
        campedNight.remove(crewKey());
        context.releaseActivity(id);
        reportedEmpty.remove(walker.getUniqueId());
        if (com.mineplus.util.DebugLogger.isEnabled()) {
            com.mineplus.util.DebugLogger.debug("[mob-journey] %s arrived at %s (%d, %d, %d)",
                    walker.getType(), structure, goal.getBlockX(), goal.getBlockY(), goal.getBlockZ());
        }
    }

    private String crewKey() {
        return id + ':' + crewName;
    }
}
