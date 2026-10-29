package com.mineplus.infrastructure.mob.journey;

import com.mineplus.infrastructure.mob.navigation.GroundProbe;
import com.mineplus.infrastructure.mob.navigation.MobAttributes;
import com.mineplus.infrastructure.mob.navigation.MobNavigator;
import com.mineplus.infrastructure.mob.route.RoutePlanner;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mob;
import org.bukkit.plugin.Plugin;

/**
 * Long-distance walking for the mob behavior subsystem: a journey is a
 * destination that is further than one path, advanced one leg at a time.
 *
 * <p>Vanilla pathfinding is a per-mob, bounded computation — hand a villager a
 * target four hundred blocks away and it either finds no path or walks to the
 * closest reachable point and stops. A journey instead keeps a <b>waypoint</b>
 * within path range ahead of the mob, walks to it, then names the next one, so
 * a destination any distance away becomes a sequence of ordinary walks. It also
 * fixes the two ways "the mob just stands there" actually happen:
 * <ul>
 *   <li><b>Unreachable waypoints</b> — a structure's reported centre is often a
 *       roof or a hole. Each waypoint is normalised to a standable cell before
 *       the mob is told to walk to it ({@link GroundProbe}).</li>
 *   <li><b>Silent stalls</b> — a path can exist and still not move the mob (a
 *       crowd, a fence, water). Progress is sampled: no ground covered within
 *       the check window means the leg is re-issued around the obstacle,
 *       alternating sides, and after enough failed strikes the journey reports
 *       {@link JourneyStatus#STUCK} instead of standing there forever.</li>
 *   <li><b>Path range</b> — the leg is clamped to the mob's own follow range
 *       ({@link MobAttributes#pathRange}), not to a constant. Vanilla resolves
 *       one path of roughly follow-range blocks, so a fixed 32-block leg makes
 *       a villager (range 16) stand still while a zombie walks.</li>
 *   <li><b>Unloaded terrain</b> — optionally holds a plugin chunk ticket for the
 *       chunk the current leg crosses, so a march does not pause every time the
 *       mob leaves player view distance ({@link ChunkLease}).</li>
 * </ul>
 *
 * <p><b>Confinement:</b> a caller may leash a journey to an area. Outside it, the
 * mob walks back rather than onward — the general rule that a behaviour belongs
 * to the area it was given and the goal it was assigned, never to the whole
 * world.
 *
 * <p>State lives here, keyed by entity id, not in the mob's PDC: journeys are
 * runtime state that means nothing after a restart, and a per-tick PDC write is
 * exactly the packet churn the Core avoids. The map self-bounds: a journey
 * nobody advances for a while is dropped, and a full runner refuses new ones
 * rather than growing.
 *
 * <p>Thread model: main thread only (the behavior engine calls it from its run).
 */
public final class JourneyRunner {

    /** Live journeys per runner; a mob with a journey is one small record. */
    private static final int MAX_JOURNEYS = 512;
    /** Advances nobody touched for this many engine steps are forgotten. */
    private static final long FORGET_AFTER_STEPS = 200L;
    /**
     * Chunks this runner may hold loaded at once for walking legs. One ticket
     * per journey, so 64 journeys walk across unloaded terrain; the rest still
     * work and simply pause when their chunk unloads.
     */
    private static final int MAX_CHUNK_LEASES = 64;

    /**
     * How one journey walks.
     *
     * @param speed             movement-speed modifier (1.0 = the mob's normal walk)
     * @param arrivalDistanceXZ  horizontal distance that counts as "arrived"
     * @param arrivalYTolerance  vertical tolerance for arrival, or {@code <= 0} to ignore height
     * @param legBlocks          how far ahead one waypoint is placed; must stay inside
     *                           the mob's own path range
     * @param repathCooldownTicks ticks between waypoint re-issues while the mob is moving
     * @param progressCheckTicks  ticks between progress samples
     * @param minProgressBlocks   ground distance a mob must cover per sample to count as moving
     * @param maxStuckStrikes     no-progress samples before the journey gives up
     * @param leashRadius         confinement radius around the leash centre, {@code <= 0} = unbounded
     */
    public record Options(
            double speed,
            double arrivalDistanceXZ,
            double arrivalYTolerance,
            int legBlocks,
            long repathCooldownTicks,
            long progressCheckTicks,
            double minProgressBlocks,
            int maxStuckStrikes,
            double leashRadius
    ) {

        public Options {
            speed = Math.max(0.1, Math.min(4.0, speed));
            arrivalDistanceXZ = Math.max(0.5, arrivalDistanceXZ);
            legBlocks = Math.max(8, legBlocks);
            repathCooldownTicks = Math.max(5L, repathCooldownTicks);
            progressCheckTicks = Math.max(10L, progressCheckTicks);
            minProgressBlocks = Math.max(0.5, minProgressBlocks);
            maxStuckStrikes = Math.max(1, maxStuckStrikes);
        }

        /**
         * A sane long march: one block of speed, a 32-block leg, generous
         * patience.
         *
         * <p>The leg stays well inside the mob's own path range. Vanilla
         * navigation resolves a path of roughly three dozen blocks; a waypoint
         * at the edge of that range is a waypoint the mob frequently cannot
         * path to, and a leg it cannot walk is a mob that stands still.
         */
        public static Options defaults() {
            return new Options(1.0, 6.0, 8.0, 32, 40L, 60L, 2.0, 6, 0.0);
        }
    }

    /** Per-entity journey state. Runtime-only, never persisted. */
    private static final class Journey {

        private Location destination;
        private Location leg;
        private long nextRepathAt;
        private long nextProgressCheckAt;
        private Location lastSample;
        private int strikes;
        private int detourSide = 1;
        /**
         * Whether the current stall already spent its one full reroute. A
         * second consecutive stall after a replan is a genuinely stuck mob, not
         * a leg that merely aimed at the wrong side of a wall.
         */
        private boolean rerouted;
        private long lastAdvancedAtStep;
        /** The chunk currently held loaded for this journey's leg, when any. */
        private World leasedWorld;
        private int leasedChunkX;
        private int leasedChunkZ;
        private boolean hasLease;
    }

    private final Map<UUID, Journey> journeys = new ConcurrentHashMap<>();
    /** The plugin whose chunk tickets keep a walking leg loaded; may be {@code null}. */
    private final Plugin plugin;
    /** Leases currently held, bounded by {@link #MAX_CHUNK_LEASES}. */
    private int leases;
    /**
     * The runner's own clock: one step per engine run. Game time cannot be used
     * here — journeys span worlds whose clocks are unrelated, and a prune
     * comparing against another world's time silently forgets live journeys.
     */
    private long steps;

    /** A runner without chunk tickets (used before the engine knows its plugin). */
    public JourneyRunner() {
        this(null);
    }

    /**
     * @param plugin the plugin that owns walking-leg chunk tickets, or
     *               {@code null} to leave chunks to vanilla loading
     */
    public JourneyRunner(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Advances one mob's journey by a step. Call it whenever the mob's owning
     * action fires — the runner decides whether that means re-issuing a
     * waypoint, sampling progress, or finishing.
     *
     * @param leashCenter the area this journey belongs to, or {@code null} when
     *                    {@link Options#leashRadius()} is unbounded
     */
    public JourneyStatus advance(Mob mob, Location destination, Options options, Location leashCenter) {
        if (mob == null || mob.isDead() || !mob.isValid() || destination == null) {
            return JourneyStatus.NO_CAPACITY;
        }
        World world = mob.getWorld();
        Options settings = options == null ? Options.defaults() : options;
        // The world age, not the time of day: the cooldowns below are
        // durations, and a /time set must not push a repath into the future
        // (or hold one there) — a mob whose repath clock jumps backwards
        // stands still until the world catches up again.
        long now = world.getGameTime();
        UUID id = mob.getUniqueId();

        Journey journey = journeys.get(id);
        if (journey == null) {
            if (journeys.size() >= MAX_JOURNEYS) {
                return JourneyStatus.NO_CAPACITY;
            }
            journey = new Journey();
            journeys.put(id, journey);
        }
        journey.lastAdvancedAtStep = steps;

        // A new destination is a new journey: forget the leg and the strikes.
        if (!samePlace(journey.destination, destination)) {
            journey.destination = destination.clone();
            journey.leg = null;
            journey.strikes = 0;
            journey.rerouted = false;
            journey.lastSample = null;
            journey.nextRepathAt = 0L;
            journey.nextProgressCheckAt = now + settings.progressCheckTicks();
        }

        Location here = mob.getLocation();
        double remaining = distanceXZ(here, journey.destination);
        double heightGap = Math.abs(here.getY() - journey.destination.getY());
        boolean arrivedHorizontally = remaining <= settings.arrivalDistanceXZ();
        boolean heightOk = settings.arrivalYTolerance() <= 0
                || heightGap <= settings.arrivalYTolerance();
        if (arrivedHorizontally && heightOk) {
            finish(id, mob, journey);
            return JourneyStatus.ARRIVED;
        }

        // Confinement first: a mob outside its area walks back to it, it does
        // not carry on toward a goal that no longer belongs to this area.
        Location aim = journey.destination;
        JourneyStatus status = JourneyStatus.WALKING;
        boolean leashed = false;
        if (settings.leashRadius() > 0 && leashCenter != null && leashCenter.getWorld() == world
                && distanceXZ(here, leashCenter) > settings.leashRadius()) {
            aim = leashCenter;
            status = JourneyStatus.RETURNING;
            leashed = true;
        }

        // Progress is sampled on the same visit that does the walking: a mob
        // that has covered no ground gets its waypoint pushed sideways, so the
        // detour is decided before the next leg is issued, not after it.
        JourneyStatus progress = checkProgress(journey, here, now, settings);
        if (progress == JourneyStatus.STUCK) {
            if (!journey.rerouted) {
                // One full replan before giving up. A stall usually means the
                // leg crossed terrain the router can go around, and the detour
                // above is only a sideways nudge; a replanned leg is a real
                // route. A second consecutive stall is a genuinely stuck mob.
                journey.rerouted = true;
                journey.strikes = 0;
                journey.leg = null;
                journey.lastSample = null;
                journey.nextRepathAt = 0L;
                progress = JourneyStatus.DETOURING;
            } else {
                drop(id);
                return JourneyStatus.STUCK;
            }
        }
        if (progress == JourneyStatus.DETOURING && !leashed) {
            status = JourneyStatus.DETOURING;
        }

        // A leg longer than the mob's own path range is a leg it cannot path;
        // vanilla resolves roughly follow-range blocks per search. The fixed
        // option is a ceiling, not the length every mob gets.
        double legLength = Math.min(settings.legBlocks(), MobAttributes.pathRange(mob));
        if (journey.leg != null && distanceXZ(here, journey.leg) <= settings.arrivalDistanceXZ() + 1.0) {
            // Reaching the current waypoint early means the next advance should
            // immediately name a new one rather than wait out the cooldown.
            journey.nextRepathAt = 0L;
        }

        if (now < journey.nextRepathAt) {
            return status;
        }

        // Name the next leg. A straight line toward the goal is only correct
        // on open ground; the planner routes the leg around water, cliffs and
        // walls inside a bounded window, so the mob walks a walkable line
        // instead of stalling against the first obstacle (it returns the
        // straight leg when the line is already clear, costing a handful of
        // block reads). A failed search leaves the straight leg in place.
        Location routed = RoutePlanner.waypoint(world, here, aim, legLength);
        Location leg = routed != null ? routed : pointTowards(world, here, aim, legLength);
        if (journey.strikes > 0) {
            leg = applyDetour(leg, aim, here, journey, settings);
        }

        // Hold the leg's chunk loaded before probing its ground: an unloaded
        // chunk has no standable answer, and a raw aim point on unloaded
        // terrain is exactly the waypoint a mob refuses to walk to.
        leaseLegChunk(journey, world, leg);
        Location walkable = GroundProbe.standableNear(leg, 2, 6);
        Location waypoint = walkable != null ? walkable : leg;
        if (distanceXZ(here, waypoint) < 1.5 && distanceXZ(here, aim) > 2.0) {
            // The router landed the mob on the waypoint it is already standing
            // on (a replan from a stall does this): nudge it toward the goal so
            // the next navigation call is not a walk to the current spot.
            waypoint = pointTowards(world, here, aim, Math.min(legLength, 6.0));
        }
        journey.leg = waypoint;
        journey.nextRepathAt = now + settings.repathCooldownTicks();
        double stopDistance = Math.min(settings.arrivalDistanceXZ(),
                Math.max(1.0, distanceXZ(here, waypoint)));
        boolean started = MobNavigator.moveToWithin(mob, waypoint, stopDistance, settings.speed());
        if (started) {
            MobNavigator.lookToward(mob, waypoint);
        } else {
            journey.strikes++;
            if (journey.strikes >= settings.maxStuckStrikes()) {
                drop(id);
                return JourneyStatus.STUCK;
            }
            journey.nextRepathAt = now + 5L; // try around it soon
            return JourneyStatus.BLOCKED;
        }
        return status;
    }

    /**
     * Progress sampling: ground covered since the last sample resets the
     * strikes, a stationary mob earns one and gets its waypoint pushed sideways.
     * Kept separate from {@link #advance} so the stall rule stays readable.
     */
    private JourneyStatus checkProgress(Journey journey, Location here, long now, Options settings) {
        if (now < journey.nextProgressCheckAt) {
            return JourneyStatus.WALKING;
        }
        double moved = journey.lastSample == null ? Double.MAX_VALUE : distanceXZ(here, journey.lastSample);
        journey.lastSample = here.clone();
        journey.nextProgressCheckAt = now + settings.progressCheckTicks();
        if (moved >= settings.minProgressBlocks()) {
            journey.strikes = 0;
            journey.rerouted = false;
            return JourneyStatus.WALKING;
        }
        journey.strikes++;
        if (journey.strikes >= settings.maxStuckStrikes()) {
            return JourneyStatus.STUCK;
        }
        journey.nextRepathAt = 0L; // re-issue now, with a sideways offset
        return JourneyStatus.DETOURING;
    }

    /**
     * Standalone progress probe for callers that drive the walk themselves
     * (module-side crews that regroup before marching): {@code true} when the
     * mob has stalled badly enough that its waypoint should be re-issued.
     */
    public boolean isStalled(Mob mob, Options settings) {
        if (mob == null || settings == null) {
            return false;
        }
        Journey journey = journeys.get(mob.getUniqueId());
        if (journey == null) {
            return false;
        }
        JourneyStatus status = checkProgress(journey, mob.getLocation(),
                mob.getWorld().getGameTime(), settings);
        return status == JourneyStatus.DETOURING || status == JourneyStatus.STUCK;
    }

    /** Drops the mob's journey state, releases its chunk lease and stops it walking. */
    public void abandon(Mob mob) {
        if (mob == null) {
            return;
        }
        releaseLease(journeys.remove(mob.getUniqueId()));
        MobNavigator.stop(mob);
    }

    /** {@code true} when the mob currently has a live journey. */
    public boolean isJourneying(Mob mob) {
        return mob != null && journeys.containsKey(mob.getUniqueId());
    }

    /** The destination of the mob's live journey, or {@code null}. */
    public Location destination(Mob mob) {
        Journey journey = mob == null ? null : journeys.get(mob.getUniqueId());
        return journey == null || journey.destination == null ? null : journey.destination.clone();
    }

    /** One-line report for status commands: distance, waypoint and strikes. */
    public String describe(Mob mob) {
        Journey journey = mob == null ? null : journeys.get(mob.getUniqueId());
        if (journey == null || journey.destination == null || mob.getWorld() == null) {
            return "no journey";
        }
        return String.format(java.util.Locale.ROOT, "%.0f blocks to (%d, %d, %d), leg %.0f, strikes %d",
                distanceXZ(mob.getLocation(), journey.destination),
                journey.destination.getBlockX(), journey.destination.getBlockY(), journey.destination.getBlockZ(),
                journey.leg == null ? -1 : distanceXZ(mob.getLocation(), journey.leg),
                journey.strikes);
    }

    /** Live journeys; bounded, but reported so a module can see it is saturated. */
    public int activeCount() {
        return journeys.size();
    }

    /**
     * One engine step. Called once per behavior run so the runner's clock moves
     * with the engine rather than with any world's game time.
     */
    public void step() {
        steps++;
        if (steps % 20L == 0L && !journeys.isEmpty()) {
            prune();
        }
    }

    /** Forgets journeys nobody advanced for a while. Called from {@link #step()}. */
    public void prune() {
        for (Map.Entry<UUID, Journey> entry : journeys.entrySet()) {
            if (steps - entry.getValue().lastAdvancedAtStep > FORGET_AFTER_STEPS
                    && journeys.remove(entry.getKey(), entry.getValue())) {
                releaseLease(entry.getValue());
            }
        }
    }

    /**
     * One line per live journey, oldest state first, for status commands. The
     * bound matters: a village full of pilgrims must not flood a chat page.
     */
    public java.util.List<String> snapshot(int maxLines) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (Map.Entry<UUID, Journey> entry : journeys.entrySet()) {
            if (lines.size() >= Math.max(1, maxLines)) {
                lines.add("  ... " + (journeys.size() - lines.size()) + " more");
                return lines;
            }
            org.bukkit.entity.Entity entity = org.bukkit.Bukkit.getEntity(entry.getKey());
            if (!(entity instanceof Mob mob)) {
                lines.add("  " + shortId(entry.getKey()) + ": unloaded (journey resumes on load)");
                continue;
            }
            lines.add("  " + shortId(entry.getKey()) + " " + mob.getType() + ": " + describe(mob));
        }
        return lines;
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private void finish(UUID id, Mob mob, Journey journey) {
        journeys.remove(id);
        releaseLease(journey);
        MobNavigator.stop(mob);
    }

    /** Removes a journey and releases whatever chunk ticket it held. */
    private void drop(UUID id) {
        releaseLease(journeys.remove(id));
    }

    /**
     * Holds the chunk the next leg crosses loaded, within the runner's lease
     * budget. Re-leasing the same chunk is a no-op; moving to a new leg swaps
     * the ticket, so a journey never accumulates chunks.
     */
    private void leaseLegChunk(Journey journey, World world, Location point) {
        if (plugin == null || point == null || !ChunkLease.available()) {
            return;
        }
        int chunkX = point.getBlockX() >> 4;
        int chunkZ = point.getBlockZ() >> 4;
        if (journey.hasLease && journey.leasedWorld == world
                && journey.leasedChunkX == chunkX && journey.leasedChunkZ == chunkZ) {
            return;
        }
        if (!journey.hasLease && leases >= MAX_CHUNK_LEASES) {
            return;
        }
        releaseLease(journey);
        ChunkLease.add(world, chunkX, chunkZ, plugin);
        journey.leasedWorld = world;
        journey.leasedChunkX = chunkX;
        journey.leasedChunkZ = chunkZ;
        journey.hasLease = true;
        leases++;
    }

    private void releaseLease(Journey journey) {
        if (journey == null || !journey.hasLease) {
            return;
        }
        ChunkLease.remove(journey.leasedWorld, journey.leasedChunkX, journey.leasedChunkZ, plugin);
        journey.hasLease = false;
        journey.leasedWorld = null;
        leases = Math.max(0, leases - 1);
    }

    /** A point {@code distance} blocks along the straight line from {@code from} to {@code towards}. */
    private static Location pointTowards(World world, Location from, Location towards, double distance) {
        double dx = towards.getX() - from.getX();
        double dz = towards.getZ() - from.getZ();
        double length = Math.hypot(dx, dz);
        if (length < 1.0e-4) {
            return from.clone();
        }
        double scale = Math.min(distance, length) / length;
        return new Location(world, from.getX() + dx * scale, from.getY(), from.getZ() + dz * scale);
    }

    /**
     * Sideways push of a waypoint around whatever the mob is stuck on. The
     * offset grows with the strike count (one wall needs a wider berth than a
     * crowd) and alternates side, so a second strike tries the other way
     * instead of walking into the same obstacle twice.
     */
    private static Location applyDetour(Location leg, Location goal, Location from, Journey journey,
                                        Options settings) {
        if (journey.strikes == 0) {
            return leg;
        }
        double dx = goal.getX() - from.getX();
        double dz = goal.getZ() - from.getZ();
        double length = Math.hypot(dx, dz);
        if (length < 1.0e-3) {
            return leg;
        }
        // Unit perpendicular to the travel direction.
        double px = -dz / length;
        double pz = dx / length;
        journey.detourSide = -journey.detourSide;
        // A quarter of the leg to the side, clamped so the detour stays inside
        // the mob's path range and still converges back toward the goal.
        double offset = Math.min(settings.legBlocks() * 0.35,
                settings.legBlocks() * 0.1 * journey.strikes);
        return new Location(leg.getWorld(),
                leg.getX() + px * offset * journey.detourSide,
                leg.getY(),
                leg.getZ() + pz * offset * journey.detourSide);
    }

    private static boolean samePlace(Location a, Location b) {
        if (a == null || b == null) {
            return false;
        }
        return a.getWorld() == b.getWorld()
                && Math.abs(a.getX() - b.getX()) < 0.51
                && Math.abs(a.getY() - b.getY()) < 0.51
                && Math.abs(a.getZ() - b.getZ()) < 0.51;
    }

    /** Horizontal distance: journeys are planned on the ground, not in a sphere. */
    public static double distanceXZ(Location from, Location to) {
        double dx = from.getX() - to.getX();
        double dz = from.getZ() - to.getZ();
        return Math.hypot(dx, dz);
    }
}
