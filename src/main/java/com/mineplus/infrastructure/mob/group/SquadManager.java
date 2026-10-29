package com.mineplus.infrastructure.mob.group;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.entity.Mob;

/**
 * Per-crew formation and role state: the answer to "which spot in the group
 * does this member walk to".
 *
 * <p>{@link MobCrewService} already gives a crew one shared formation point and
 * one bounded step toward it. This class decides the shape around that point —
 * a wedge behind the leader, a pincer around a village, a ring around a gate —
 * and the role taken by each member. Members that have neither a formation nor
 * a role cost nothing: {@link #slot} answers {@code null} on the first check,
 * and the crew walks exactly as before.
 *
 * <p>State is keyed by the crew's identity ({@code MobCrewService.crewKeyOf}),
 * not its name: two local pilgrim crews must not share a pincer. Entries are
 * runtime-only and forgotten when the crew is dropped.
 *
 * <p>Thread model: main thread only (the behavior engine's thread).
 */
public final class SquadManager {

    /** Squads held at once; one per crew in practice, and crews are bounded. */
    private static final int MAX_SQUADS = 256;
    /** Blocks between adjacent slots when a caller does not name a spacing. */
    public static final double DEFAULT_SPACING = 1.6;
    /** How close a member keeps to its neighbours before sidestepping. */
    public static final double DEFAULT_SEPARATION = 1.8;

    private static final class Squad {

        private volatile Formation formation = Formation.NONE;
        private volatile double spacing = DEFAULT_SPACING;
        private final Map<UUID, SquadRole> roles = new ConcurrentHashMap<>();
    }

    private final Map<String, Squad> squads = new ConcurrentHashMap<>();

    /**
     * The world-space slot {@code member} should walk to, or {@code null} when
     * this crew has no formation or role and nothing should change.
     *
     * <p>The crew's direction of travel is taken as the line from the member to
     * the formation point: a follower is behind it, so "forward" points the way
     * the group is going. Offsets are converted into that frame, which keeps a
     * wedge pointing down the route rather than at the compass.
     *
     * @param crewKey the crew's identity (not its name)
     * @param member  the member whose slot is wanted
     * @param rally   the crew's formation point
     * @param leader  the crew's live leader, or {@code null}
     * @param members the crew's live members
     */
    public Location slot(String crewKey, Mob member, Location rally, Mob leader, List<Mob> members) {
        if (crewKey == null || member == null || rally == null || rally.getWorld() == null) {
            return null;
        }
        Squad squad = squads.get(crewKey);
        Formation formation = squad == null ? Formation.NONE : squad.formation;
        SquadRole role = squad == null ? null : squad.roles.get(member.getUniqueId());
        if (formation == Formation.NONE && role == null) {
            return null;
        }
        List<Mob> ordered = orderedMembers(leader, members);
        int index = indexOf(ordered, member.getUniqueId());
        int count = Math.max(1, ordered.size());
        double spacing = (squad == null ? DEFAULT_SPACING : squad.spacing)
                * (role == null ? 1.0 : role.spacingMultiplier());
        double[] local = formation.slot(Math.max(0, index), count, spacing);
        double forward = local[0];
        double lateral = local[1];
        if (role != null) {
            forward += role.forwardBias() * spacing;
            lateral += role.lateralBias() * spacing * (index % 2 == 0 ? -1 : 1);
        }
        Location here = member.getLocation();
        double dx = rally.getX() - here.getX();
        double dz = rally.getZ() - here.getZ();
        double length = Math.hypot(dx, dz);
        if (length < 1.0E-4) {
            return null;
        }
        double fx = dx / length;
        double fz = dz / length;
        return new Location(rally.getWorld(),
                rally.getX() + fx * forward - fz * lateral,
                rally.getY(),
                rally.getZ() + fz * forward + fx * lateral);
    }

    /** Sets a crew's formation; a crew with none walks the plain formation point. */
    public void setFormation(String crewKey, Formation formation) {
        Squad squad = squad(crewKey);
        if (squad != null) {
            squad.formation = formation == null ? Formation.NONE : formation;
        }
    }

    /** The crew's formation ({@link Formation#NONE} by default). */
    public Formation formation(String crewKey) {
        Squad squad = crewKey == null ? null : squads.get(crewKey);
        return squad == null ? Formation.NONE : squad.formation;
    }

    /** Distance between adjacent slots; clamped to a walkable range. */
    public void setSpacing(String crewKey, double spacing) {
        Squad squad = squad(crewKey);
        if (squad != null) {
            squad.spacing = Math.max(0.8, Math.min(6.0, spacing));
        }
    }

    /** Gives one member a role inside its squad, or clears it with {@code null}. */
    public void assignRole(String crewKey, UUID member, SquadRole role) {
        if (member == null) {
            return;
        }
        Squad squad = squad(crewKey);
        if (squad == null) {
            return;
        }
        if (role == null) {
            squad.roles.remove(member);
        } else {
            squad.roles.put(member, role);
        }
    }

    /** The member's role, or {@code null} when it has none. */
    public SquadRole role(String crewKey, UUID member) {
        Squad squad = crewKey == null || member == null ? null : squads.get(crewKey);
        return squad == null ? null : squad.roles.get(member);
    }

    /** {@code true} when this crew has a formation or any role assigned. */
    public boolean hasState(String crewKey) {
        Squad squad = crewKey == null ? null : squads.get(crewKey);
        return squad != null && (squad.formation != Formation.NONE || !squad.roles.isEmpty());
    }

    /** Drops a crew's squad state (the crew ended). */
    public void forget(String crewKey) {
        if (crewKey != null) {
            squads.remove(crewKey);
        }
    }

    /** Squads currently held — for status output and for bounding a module. */
    public int activeCount() {
        return squads.size();
    }

    /** One readable line per squad for status commands. */
    public List<String> snapshot(int maxLines) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Squad> entry : squads.entrySet()) {
            if (lines.size() >= Math.max(1, maxLines)) {
                lines.add("  ... " + (squads.size() - lines.size()) + " more squads");
                return lines;
            }
            Squad squad = entry.getValue();
            lines.add(String.format(java.util.Locale.ROOT, "  %s: %s, %.1f blocks, %d role(s)",
                    entry.getKey(), squad.formation, squad.spacing, squad.roles.size()));
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private Squad squad(String crewKey) {
        if (crewKey == null || crewKey.isBlank()) {
            return null;
        }
        Squad existing = squads.get(crewKey);
        if (existing != null) {
            return existing;
        }
        if (squads.size() >= MAX_SQUADS) {
            return null; // saturated: formations are a convenience, never a failure
        }
        return squads.computeIfAbsent(crewKey, ignored -> new Squad());
    }

    /** Leader first, then members in a stable order, so slots do not shuffle per tick. */
    private static List<Mob> orderedMembers(Mob leader, List<Mob> members) {
        List<Mob> ordered = new ArrayList<>(members == null ? 0 : members.size());
        if (members == null || members.isEmpty()) {
            return ordered;
        }
        UUID leaderId = leader == null ? null : leader.getUniqueId();
        List<Mob> rest = new ArrayList<>(members.size());
        for (Mob member : members) {
            if (member == null) {
                continue;
            }
            if (leaderId != null && leaderId.equals(member.getUniqueId())) {
                continue;
            }
            rest.add(member);
        }
        if (leaderId != null) {
            for (Mob member : members) {
                if (member != null && leaderId.equals(member.getUniqueId())) {
                    ordered.add(member);
                    break;
                }
            }
        }
        rest.sort(Comparator.comparing(member -> member.getUniqueId().toString()));
        ordered.addAll(rest);
        return ordered;
    }

    private static int indexOf(List<Mob> ordered, UUID id) {
        for (int index = 0; index < ordered.size(); index++) {
            if (ordered.get(index).getUniqueId().equals(id)) {
                return index;
            }
        }
        return -1;
    }
}
