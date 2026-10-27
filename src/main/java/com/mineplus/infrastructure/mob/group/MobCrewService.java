package com.mineplus.infrastructure.mob.group;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;

/**
 * A named group of mobs that walks as one: a leader and the members that follow
 * it, held in the engine rather than in each mob's PDC.
 *
 * <p>Mob showcases keep needing the same three things and keep getting them
 * wrong, and the wrong version is what produces "the villagers stood there" and
 * "the camp is scattered over fifteen blocks":
 * <ul>
 *   <li><b>Who leads.</b> A leader reference stored as a string in PDC is a
 *       reference to an entity that may be gone; the crew here resolves to a
 *       live mob or to nobody, and a dead leader promotes the longest-standing
 *       member on the next look, so a convoy never strands.</li>
 *   <li><b>Who belongs.</b> Membership is a set, so "gather the crew" is one
 *       lookup instead of a radius scan that picks up unrelated mobs.</li>
 *   <li><b>Where the group is.</b> {@link #rally} is the crew's formation
 *       point — a place on the route that the whole group walks to, and that
 *       advances only once everyone has arrived. {@link #rejoinWaypoint} turns
 *       it into the one step a member should take next, bounded by the mob's
 *       path range so it is always a walk the mob can make. That is what keeps
 *       a crew in a clump instead of a column with one villager per hundred
 *       blocks.</li>
 * </ul>
 *
 * <p><b>A crew is local.</b> The membership is remembered per mob, and a mob
 * that has no crew joins the nearest one within {@link #JOIN_RADIUS} — or
 * founds its own where it stands. That is not a detail, it is the difference
 * between a march and a deadlock: a crew shares one formation point and one
 * goal, so a crew that spans a whole map can only move as fast as its
 * slowest member, and the slowest member is always the one on the other side
 * of the map. Two caravins that start far apart therefore walk as two crews
 * and both arrive, instead of one crew that never moves and one member that is
 * lost forever.
 *
 * <p>State is runtime-only by design: a crew is a scenario in motion, and a
 * server restart resurrecting a journey nobody remembers starting is a bug,
 * not a feature. The map is bounded and prunes members that despawn or unload.
 *
 * <p>Thread model: main thread only (the behavior engine's thread).
 */
public final class MobCrewService {

    /** Crews held at once, across every module. */
    private static final int MAX_CREWS = 128;
    /** Members remembered for this many engine steps without being seen. */
    private static final long FORGET_AFTER_STEPS = 400L;

    /**
     * How far a mob may be from a crew to join it instead of founding one.
     *
     * <p>Wide enough that a village's worth of villagers — who start a few
     * blocks apart — end up in one crew, and narrow enough that two caravins
     * on opposite sides of a wasteland do not share a formation point they can
     * never both reach.
     */
    public static final double JOIN_RADIUS = 32.0;

    /** One crew: a leader, its members, the goal and the formation point it shares. */
    private static final class Crew {
        /** The scope-qualified name every crew of this scenario shares. */
        private final String name;
        /** The crew's own identity — two local crews of one name differ here. */
        private final String key;
        private UUID leaderId;
        private final Map<UUID, Long> members = new ConcurrentHashMap<>();
        private Location goal;
        /**
         * The crew's formation point: where the group is expected to be right
         * now. The march advances it along the route only when the whole crew
         * has reached it, so the group walks as one instead of as a leader
         * with a tail.
         */
        private Location rally;
        private long lastSeenStep;

        private Crew(String name, String key) {
            this.name = name;
            this.key = key;
        }
    }

    private final Map<String, Crew> crews = new ConcurrentHashMap<>();
    /** Which crew a mob belongs to — the membership that makes a crew local. */
    private final Map<UUID, String> membership = new ConcurrentHashMap<>();
    /** Hands every crew its own identity, so two locals never share a state. */
    private final AtomicLong sequence = new AtomicLong();
    private long steps;

    /** One engine step; ages every crew and prunes the stale ones. */
    public void step() {
        steps++;
        if (steps % 40L != 0L) {
            return;
        }
        crews.values().removeIf(crew -> {
            crew.members.entrySet().removeIf(entry -> {
                boolean gone = steps - entry.getValue() > FORGET_AFTER_STEPS
                        || resolve(entry.getKey()) == null;
                if (gone) {
                    membership.remove(entry.getKey(), crew.key);
                }
                return gone;
            });
            return crew.members.isEmpty() && steps - crew.lastSeenStep > FORGET_AFTER_STEPS;
        });
    }

    /**
     * Forms or renews membership in a crew.
     *
     * <p>A mob that is already in a crew of this name stays in it — walking
     * away never costs a mob its crew. A mob that is not joins the nearest
     * crew of this name within {@link #JOIN_RADIUS}, or founds one where it
     * stands and leads it.
     *
     * @param ownerKey module-scoped prefix so two modules can name a crew alike
     * @param name     the crew's name inside that module
     * @param member   the mob joining
     * @param leader   the mob to lead the crew, or {@code null} to add a member
     * @param goal     the place the crew is heading, or {@code null} to leave it
     */
    public boolean recruit(String ownerKey, String name, Mob member, Mob leader, Location goal) {
        if (member == null || name == null || name.isBlank()) {
            return false;
        }
        String base = base(ownerKey, name);
        if (belongsElsewhere(member.getUniqueId(), base)) {
            return false; // another scenario owns this mob; it is not ours to take
        }
        Crew crew = crewOf(member.getUniqueId(), base);
        if (crew == null) {
            Mob anchor = leader != null ? leader : member;
            crew = nearest(base, anchor.getLocation(), JOIN_RADIUS);
            if (crew == null) {
                crew = create(base, anchor);
                if (crew == null) {
                    return false; // the crew table is saturated
                }
            }
        }
        join(crew, member);
        if (goal != null) {
            crew.goal = goal.clone();
            crew.lastSeenStep = steps;
        }
        if (crew.leaderId == null || resolve(crew.leaderId) == null) {
            crew.leaderId = (leader != null ? leader : member).getUniqueId();
        }
        return true;
    }

    /**
     * Adds every member under one leader — the rally that turns strays into a
     * crew. The target is the leader's own crew: a mob that already belongs to
     * another crew is left where it is, because two local crews that merged
     * into one would be a group nobody asked for.
     */
    public int recruitAll(String ownerKey, String name, Mob leader, Iterable<? extends Mob> members,
                          Location goal) {
        if (leader == null) {
            return 0;
        }
        String base = base(ownerKey, name);
        Crew crew = crewOf(leader.getUniqueId(), base);
        if (crew == null) {
            crew = nearest(base, leader.getLocation(), JOIN_RADIUS);
            if (crew == null) {
                crew = create(base, leader);
                if (crew == null) {
                    return 0;
                }
            }
        }
        int recruited = 0;
        for (Mob member : members) {
            if (member == null || member.equals(leader)) {
                continue;
            }
            Crew own = crewOf(member.getUniqueId(), base);
            if (own != null && !own.key.equals(crew.key)) {
                continue; // already in another local crew of the same name
            }
            if (own == null && belongsElsewhere(member.getUniqueId(), base)) {
                continue; // another scenario owns this mob
            }
            join(crew, member);
            recruited++;
        }
        if (goal != null) {
            crew.goal = goal.clone();
            crew.lastSeenStep = steps;
        }
        if (crew.leaderId == null || resolve(crew.leaderId) == null) {
            crew.leaderId = leader.getUniqueId();
        }
        return recruited;
    }

    /**
     * Joins the nearest <b>existing</b> crew of this name, without founding
     * one. {@code false} when there is none within reach — which is how a
     * caller asks "is there company nearby?" without creating an empty group
     * for a mob that has nowhere to walk.
     */
    public boolean joinNearest(String ownerKey, String name, Mob member) {
        if (member == null) {
            return false;
        }
        String base = base(ownerKey, name);
        if (belongsElsewhere(member.getUniqueId(), base)) {
            return false;
        }
        Crew crew = crewOf(member.getUniqueId(), base);
        if (crew == null) {
            crew = nearest(base, member.getLocation(), JOIN_RADIUS);
            if (crew == null) {
                return false;
            }
        }
        join(crew, member);
        return true;
    }

    /** The crew's live leader, promoting the longest-standing member if needed. */
    public Mob leader(String ownerKey, String name, Mob member) {
        Crew crew = crewOf(member == null ? null : member.getUniqueId(), base(ownerKey, name));
        if (crew == null) {
            return null;
        }
        Mob leader = resolve(crew.leaderId);
        if (leader != null) {
            return leader;
        }
        UUID oldest = null;
        long oldestStep = Long.MAX_VALUE;
        for (Map.Entry<UUID, Long> entry : crew.members.entrySet()) {
            if (resolve(entry.getKey()) != null && entry.getValue() < oldestStep) {
                oldestStep = entry.getValue();
                oldest = entry.getKey();
            }
        }
        crew.leaderId = oldest;
        return resolve(oldest);
    }

    /** {@code true} when the mob leads its crew — or when it has none yet. */
    public boolean isLeader(String ownerKey, String name, Mob member) {
        if (member == null) {
            return false;
        }
        Crew crew = crewOf(member.getUniqueId(), base(ownerKey, name));
        if (crew == null) {
            // No crew: the mob is free to found one, so it leads it.
            return true;
        }
        Mob leader = leader(ownerKey, name, member);
        return leader == null || leader.getUniqueId().equals(member.getUniqueId());
    }

    /**
     * The scope-qualified name of the crew a mob belongs to, or {@code null}.
     * Two local crews of one scenario share this name — that is how a caller
     * tells "this mob is crewed" from "this mob is free".
     */
    public String crewOf(UUID memberId) {
        if (memberId == null) {
            return null;
        }
        String key = membership.get(memberId);
        if (key == null) {
            return null;
        }
        Crew crew = crews.get(key);
        if (crew == null) {
            membership.remove(memberId, key);
            return null;
        }
        return crew.name;
    }

    /**
     * The identity of the crew a mob belongs to, or {@code null}. Two local
     * crews of one scenario share a name but not an identity, and the identity
     * is what per-crew state (a camp, a patience counter) has to be keyed on.
     */
    public String crewKeyOf(UUID memberId) {
        if (memberId == null) {
            return null;
        }
        String key = membership.get(memberId);
        if (key == null) {
            return null;
        }
        Crew crew = crews.get(key);
        if (crew == null) {
            membership.remove(memberId, key);
            return null;
        }
        return crew.key;
    }

    /**
     * The waypoint a member should walk toward to reach the crew's formation
     * point, or {@code null} when it is already close enough.
     *
     * <p>Two rules make this the difference between a group that marches and a
     * group that stands still:
     * <ul>
     *   <li>The waypoint is <b>bounded</b> by {@code maxReach}, the mob's own
     *       path range. A member whose formation point is a hundred blocks away
     *       is handed a waypoint it can actually path to — asking a mob to walk
     *       to a target beyond its path range returns "no path" and the mob
     *       never moves again.</li>
     *   <li>The formation point is a <b>place</b>, not a person. Chasing the
     *       leader's live position means the target moves as fast as the
     *       chaser, so the gap never closes; a formation point that advances
     *       only when the crew has caught up turns the same rule into a march
     *       the crew can finish.</li>
     * </ul>
     */
    public Location rejoinWaypoint(String ownerKey, String name, Mob member, double cohesionRadius,
                                    double maxReach) {
        Crew crew = crewOf(member == null ? null : member.getUniqueId(), base(ownerKey, name));
        if (crew == null || member == null || cohesionRadius <= 0) {
            return null;
        }
        Location target = crew.rally;
        if (target == null) {
            Mob leader = leader(ownerKey, name, member);
            if (leader == null) {
                return null;
            }
            target = leader.getLocation();
        }
        Location here = member.getLocation();
        if (target.getWorld() != here.getWorld()) {
            return null;
        }
        double distance = distanceXZ(here, target);
        if (distance <= cohesionRadius) {
            return null;
        }
        double dx = target.getX() - here.getX();
        double dz = target.getZ() - here.getZ();
        double length = Math.hypot(dx, dz);
        if (length < 1.0e-4) {
            return null;
        }
        // One step toward the formation point, never further than the mob's
        // path range: a waypoint it can path to, not one it cannot.
        double step = Math.min(distance, Math.max(4.0, maxReach));
        double scale = step / length;
        return new Location(target.getWorld(), here.getX() + dx * scale, here.getY(),
                here.getZ() + dz * scale);
    }

    /**
     * The greatest distance from the crew's formation point to any of its
     * participating members: how far the group is spread out right now.
     *
     * <p>A member another scenario has taken over (a villager rescued by a
     * guardian) is no longer part of the formation, so the caller filters it
     * out — counting it would make the crew wait forever for somebody who is
     * not coming.
     *
     * @param include the members that still count, or {@code null} for all
     */
    public double spread(String ownerKey, String name, Mob member, Location point, Predicate<Mob> include) {
        Crew crew = crewOf(member == null ? null : member.getUniqueId(), base(ownerKey, name));
        if (crew == null || point == null) {
            return 0.0;
        }
        double worst = 0.0;
        for (Mob mob : resolveMembers(crew)) {
            if (include != null && !include.test(mob)) {
                continue;
            }
            if (mob.getWorld() != point.getWorld()) {
                return Double.MAX_VALUE;
            }
            worst = Math.max(worst, distanceXZ(mob.getLocation(), point));
        }
        return worst;
    }

    /** The crew's formation point, or {@code null} when it has none yet. */
    public Location rally(String ownerKey, String name, Mob member) {
        Crew crew = crewOf(member == null ? null : member.getUniqueId(), base(ownerKey, name));
        return crew == null || crew.rally == null ? null : crew.rally.clone();
    }

    /**
     * Moves the crew's formation point. The march advances it along the route
     * once the crew has caught up; at night it is pinned to the camp site.
     */
    public void setRally(String ownerKey, String name, Mob member, Location point) {
        Crew crew = crewOf(member == null ? null : member.getUniqueId(), base(ownerKey, name));
        if (crew == null) {
            crew = nearest(base(ownerKey, name), member == null ? null : member.getLocation(), JOIN_RADIUS);
            if (crew == null && member != null) {
                crew = create(base(ownerKey, name), member);
            }
        }
        if (crew != null) {
            crew.rally = point == null ? null : point.clone();
            crew.lastSeenStep = steps;
        }
    }

    /** The shared goal the crew was last given, or {@code null}. */
    public Location goal(String ownerKey, String name, Mob member) {
        Crew crew = crewOf(member == null ? null : member.getUniqueId(), base(ownerKey, name));
        return crew == null || crew.goal == null ? null : crew.goal.clone();
    }

    /** Replaces the crew's shared goal (a freshly located village). */
    public void setGoal(String ownerKey, String name, Mob member, Location goal) {
        Crew crew = crewOf(member == null ? null : member.getUniqueId(), base(ownerKey, name));
        if (crew == null) {
            crew = nearest(base(ownerKey, name), member == null ? null : member.getLocation(), JOIN_RADIUS);
            if (crew == null && member != null) {
                crew = create(base(ownerKey, name), member);
            }
        }
        if (crew != null) {
            crew.goal = goal == null ? null : goal.clone();
            crew.lastSeenStep = steps;
        }
    }

    /** Live members of the crew the member belongs to, despawned ones skipped. */
    public List<Mob> members(String ownerKey, String name, Mob member) {
        Crew crew = crewOf(member == null ? null : member.getUniqueId(), base(ownerKey, name));
        if (crew == null) {
            return List.of();
        }
        return resolveMembers(crew);
    }

    /** The crew's live members, resolving the membership set once. */
    private static List<Mob> resolveMembers(Crew crew) {
        List<Mob> live = new ArrayList<>();
        for (UUID id : crew.members.keySet()) {
            Mob mob = resolve(id);
            if (mob != null) {
                live.add(mob);
            }
        }
        return live;
    }

    /** {@code true} when the mob currently belongs to a crew. */
    public boolean isMember(Mob mob) {
        return mob != null && crewOf(mob.getUniqueId()) != null;
    }

    /** Removes one member; the crew survives until it is empty. */
    public void dismiss(String ownerKey, String name, Mob member) {
        if (member == null) {
            return;
        }
        Crew crew = crewOf(member.getUniqueId(), base(ownerKey, name));
        if (crew == null) {
            return;
        }
        crew.members.remove(member.getUniqueId());
        membership.remove(member.getUniqueId(), crew.key);
        if (member.getUniqueId().equals(crew.leaderId)) {
            crew.leaderId = null;
        }
    }

    /** Drops the whole crew (a completed journey, a disbanded escort). */
    public void disband(String ownerKey, String name, Mob member) {
        if (member == null) {
            return;
        }
        Crew crew = crewOf(member.getUniqueId(), base(ownerKey, name));
        if (crew == null) {
            return;
        }
        for (UUID id : crew.members.keySet()) {
            membership.remove(id, crew.key);
        }
        crews.remove(crew.key, crew);
    }

    /** Live crews — for status output and for bounding a runaway module. */
    public int activeCount() {
        return crews.size();
    }

    /** One readable line per crew for status commands. */
    public List<String> snapshot(int maxLines) {
        List<String> lines = new ArrayList<>();
        for (Crew crew : crews.values()) {
            if (lines.size() >= Math.max(1, maxLines)) {
                lines.add("  ... " + (crews.size() - lines.size()) + " more crews");
                return lines;
            }
            Mob leader = resolve(crew.leaderId);
            lines.add(String.format(java.util.Locale.ROOT, "  %s: %d member(s), leader %s, goal %s, rally %s",
                    crew.name, crew.members.size(),
                    leader == null ? "none" + (crew.leaderId == null ? "" : " (gone)")
                            : leader.getType().name().toLowerCase(java.util.Locale.ROOT),
                    crew.goal == null ? "unset"
                            : "(" + crew.goal.getBlockX() + ", " + crew.goal.getBlockY() + ", "
                                    + crew.goal.getBlockZ() + ")",
                    crew.rally == null ? "unset"
                            : "(" + crew.rally.getBlockX() + ", " + crew.rally.getBlockY() + ", "
                                    + crew.rally.getBlockZ() + ")"));
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static String base(String ownerKey, String name) {
        return (ownerKey == null ? "" : ownerKey) + ':' + name;
    }

    /** The crew a mob belongs to under one scenario, or {@code null}. */
    private Crew crewOf(UUID memberId, String base) {
        if (memberId == null) {
            return null;
        }
        String key = membership.get(memberId);
        if (key == null) {
            return null;
        }
        Crew crew = crews.get(key);
        if (crew == null) {
            membership.remove(memberId, key);
            return null;
        }
        return crew.name.equals(base) ? crew : null;
    }

    /** {@code true} when the mob is crewed by a different scenario. */
    private boolean belongsElsewhere(UUID memberId, String base) {
        if (memberId == null) {
            return false;
        }
        String key = membership.get(memberId);
        if (key == null) {
            return false;
        }
        Crew crew = crews.get(key);
        if (crew == null) {
            membership.remove(memberId, key);
            return false;
        }
        return !crew.name.equals(base);
    }

    /** The nearest live crew of one name within {@code radius} of a place. */
    private Crew nearest(String base, Location at, double radius) {
        if (at == null) {
            return null;
        }
        Crew best = null;
        double bestDistance = radius;
        boolean bestHasGoal = false;
        for (Crew crew : crews.values()) {
            if (!crew.name.equals(base)) {
                continue;
            }
            Location anchor = anchorOf(crew);
            if (anchor == null || anchor.getWorld() != at.getWorld()) {
                continue;
            }
            double distance = distanceXZ(at, anchor);
            if (distance > radius) {
                continue;
            }
            boolean hasGoal = crew.goal != null;
            // A crew that knows where it goes is always the better company:
            // joining it saves the mob a structure search of its own.
            if (best == null
                    || (hasGoal && !bestHasGoal)
                    || (hasGoal == bestHasGoal && distance < bestDistance)) {
                best = crew;
                bestDistance = distance;
                bestHasGoal = hasGoal;
            }
        }
        return best;
    }

    /** Where a crew is: its formation point, else its leader, else a member. */
    private static Location anchorOf(Crew crew) {
        if (crew.rally != null) {
            return crew.rally;
        }
        Mob leader = resolve(crew.leaderId);
        if (leader != null) {
            return leader.getLocation();
        }
        for (UUID id : crew.members.keySet()) {
            Mob member = resolve(id);
            if (member != null) {
                return member.getLocation();
            }
        }
        return null;
    }

    /** A new, empty crew led by its founder. */
    private Crew create(String base, Mob founder) {
        if (crews.size() >= MAX_CREWS) {
            return null;
        }
        Crew crew = new Crew(base, base + '#' + sequence.incrementAndGet());
        crew.leaderId = founder == null ? null : founder.getUniqueId();
        crews.put(crew.key, crew);
        return crew;
    }

    /** Adds one member to a crew and remembers the membership. */
    private void join(Crew crew, Mob member) {
        crew.members.put(member.getUniqueId(), steps);
        crew.lastSeenStep = steps;
        membership.put(member.getUniqueId(), crew.key);
    }

    private static Mob resolve(UUID id) {
        if (id == null) {
            return null;
        }
        // A member whose chunk is unloaded resolves to nothing: membership is
        // kept (age-out is a separate, slow rule) but the role is vacant, so a
        // leader walking out of render distance hands over instead of leaving
        // the crew following a location nobody can reach.
        Entity entity = Bukkit.getEntity(id);
        return entity instanceof Mob mob && mob.isValid() && !mob.isDead() ? mob : null;
    }

    private static double distanceXZ(Location from, Location to) {
        double dx = from.getX() - to.getX();
        double dz = from.getZ() - to.getZ();
        return Math.hypot(dx, dz);
    }
}
