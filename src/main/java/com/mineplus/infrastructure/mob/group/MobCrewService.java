package com.mineplus.infrastructure.mob.group;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li><b>Where the group is.</b> {@link #focusOf} answers the only question a
 *       marching member has: is there someone I should be walking toward right
 *       now. That is what keeps a crew in a clump instead of a column with one
 *       villager per hundred blocks.</li>
 * </ul>
 *
 * <p>State is runtime-only by design: a crew is a scenario in motion, and
 * persisting it means a server restart resurrects a journey nobody remembers
 * starting. The map is bounded and prunes members that despawn or unload.
 *
 * <p>Thread model: main thread only (the behavior engine's thread).
 */
public final class MobCrewService {

    /** Crews held at once, across every module. */
    private static final int MAX_CREWS = 128;
    /** Members remembered for this many engine steps without being seen. */
    private static final long FORGET_AFTER_STEPS = 400L;

    /** One crew: a leader preference, its members, and the goal it shares. */
    private static final class Crew {
        private String name;
        private UUID leaderId;
        private final Map<UUID, Long> members = new ConcurrentHashMap<>();
        private Location goal;
        private long lastSeenStep;
    }

    private final Map<String, Crew> crews = new ConcurrentHashMap<>();
    private long steps;

    /** One engine step; ages every crew and prunes the stale ones. */
    public void step() {
        steps++;
        if (steps % 40L != 0L) {
            return;
        }
        crews.values().removeIf(crew -> crew.members.entrySet().removeIf(entry ->
                steps - entry.getValue() > FORGET_AFTER_STEPS || resolve(entry.getKey()) == null));
        crews.entrySet().removeIf(entry -> entry.getValue().members.isEmpty()
                && steps - entry.getValue().lastSeenStep > FORGET_AFTER_STEPS);
    }

    /**
     * Forms or renews membership in a crew. The first leader named keeps the
     * role while alive; {@code leader} being {@code null} only adds a member.
     *
     * @param ownerKey module-scoped prefix so two modules can name a crew alike
     * @param name     the crew's name inside that module
     */
    public boolean recruit(String ownerKey, String name, Mob member, Mob leader, Location goal) {
        if (member == null || name == null || name.isBlank()) {
            return false;
        }
        String key = ownerKey + ':' + name;
        Crew crew = crews.get(key);
        if (crew == null) {
            if (crews.size() >= MAX_CREWS) {
                return false;
            }
            crew = new Crew();
            crew.name = key;
            crews.put(key, crew);
        }
        crew.members.put(member.getUniqueId(), steps);
        crew.lastSeenStep = steps;
        if (goal != null) {
            crew.goal = goal.clone();
        }
        if (leader != null && (crew.leaderId == null || resolve(crew.leaderId) == null)) {
            crew.leaderId = leader.getUniqueId();
        }
        return true;
    }

    /** Adds every member under one leader — the rally that turns strays into a crew. */
    public int recruitAll(String ownerKey, String name, Mob leader, Iterable<? extends Mob> members, Location goal) {
        int recruited = 0;
        for (Mob member : members) {
            if (member != null && recruit(ownerKey, name, member, leader, goal)) {
                recruited++;
            }
        }
        return recruited;
    }

    /** The crew's live leader, promoting the longest-standing member if needed. */
    public Mob leader(String ownerKey, String name) {
        Crew crew = crew(ownerKey, name);
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

    /** The mob's crew name, or {@code null} when it is not in one. */
    public String crewOf(UUID memberId) {
        for (Crew crew : crews.values()) {
            if (crew.members.containsKey(memberId)) {
                return crew.name;
            }
        }
        return null;
    }

    /**
     * The location a member should walk toward to stay with the crew, or
     * {@code null} when it is already cohesive enough.
     *
     * <p>This is the cohesion rule: a member farther than {@code cohesionRadius}
     * from the leader stops working on the shared goal and closes the gap first.
     * Without it a crew stretches into a line and the last members effectively
     * never arrive, which is exactly how "they do not move toward the village"
     * looks from outside.
     */
    public Location focusOf(String ownerKey, String name, Mob member, double cohesionRadius) {
        Crew crew = crew(ownerKey, name);
        if (crew == null || member == null || cohesionRadius <= 0) {
            return null;
        }
        Mob leader = leader(ownerKey, name);
        if (leader == null || leader.getUniqueId().equals(member.getUniqueId())) {
            return null;
        }
        Location here = member.getLocation();
        Location ahead = leader.getLocation();
        if (distanceXZ(here, ahead) <= cohesionRadius && Math.abs(here.getY() - ahead.getY()) <= 3.0) {
            return null;
        }
        return ahead;
    }

    /** The shared goal the crew was last given, or {@code null}. */
    public Location goal(String ownerKey, String name) {
        Crew crew = crew(ownerKey, name);
        return crew == null || crew.goal == null ? null : crew.goal.clone();
    }

    /** Replaces the crew's shared goal (a freshly located village). */
    public void setGoal(String ownerKey, String name, Location goal) {
        Crew crew = crew(ownerKey, name);
        if (crew != null) {
            crew.goal = goal == null ? null : goal.clone();
            crew.lastSeenStep = steps;
        }
    }

    /** Live members of a crew, despawned ones skipped. */
    public List<Mob> members(String ownerKey, String name) {
        Crew crew = crew(ownerKey, name);
        List<Mob> live = new ArrayList<>();
        if (crew == null) {
            return live;
        }
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
        Crew crew = crew(ownerKey, name);
        if (crew != null && member != null) {
            crew.members.remove(member.getUniqueId());
            if (member.getUniqueId().equals(crew.leaderId)) {
                crew.leaderId = null;
            }
        }
    }

    /** Drops the whole crew (a completed journey, a disbanded escort). */
    public void disband(String ownerKey, String name) {
        crews.remove(ownerKey + ':' + name);
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
            lines.add(String.format(java.util.Locale.ROOT, "  %s: %d member(s), leader %s, goal %s",
                    crew.name, crew.members.size(),
                    leader == null ? "none" + (crew.leaderId == null ? "" : " (gone)")
                            : leader.getType().name().toLowerCase(java.util.Locale.ROOT),
                    crew.goal == null ? "unset"
                            : "(" + crew.goal.getBlockX() + ", " + crew.goal.getBlockY() + ", "
                                    + crew.goal.getBlockZ() + ")"));
        }
        return lines;
    }

    private Crew crew(String ownerKey, String name) {
        if (name == null) {
            return null;
        }
        return crews.get(ownerKey + ':' + name);
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
