package com.mineplus.infrastructure.core.multiblock.formation;

import com.mineplus.infrastructure.core.events.HookBus;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Ties formations into the world: checks a location against every registered
 * pattern, publishes {@link FormationEvent}s on the HookBus, and remembers
 * live formations so breaks are reported precisely.
 *
 * <p>A formation is keyed by its matched bounding box corners; a break is
 * detected when any block inside a live formation's box changes. This is
 * exact (no guessing) and cheap: the box check is a bounds comparison, and
 * the full re-match only runs when a block inside a live box changes.
 */
public final class FormationManager {

    private final FormationRegistry registry;
    private final HookBus hookBus;
    /** Keys are "world,x1,y1,z1,x2,y2,z2" of live matched boxes; value is the pattern id. */
    private final Map<String, String> liveFormations = new ConcurrentHashMap<>();

    public FormationManager(FormationRegistry registry, HookBus hookBus) {
        this.registry = registry;
        this.hookBus = hookBus;
    }

    public FormationRegistry registry() {
        return registry;
    }

    HookBus hookBus() {
        return hookBus;
    }

    /**
     * Checks every registered pattern at {@code anchor} and fires
     * {@code formed=true} events for each new match. Returns the first
     * matching pattern id, or {@code null} when nothing matched.
     */
    public String checkFormed(Location anchor, Player actor) {
        for (FormationPattern pattern : registry.patternsView().values()) {
            FormationMatcher.Match match = FormationMatcher.matchAt(pattern, anchor);
            if (match == null) {
                continue;
            }
            String key = boxKey(match);
            String previous = liveFormations.putIfAbsent(key, pattern.id());
            if (previous == null) {
                hookBus.publishFormation(new FormationEvent(pattern, anchor,
                        match.minCorner(), match.maxCorner(), actor, true));
                return pattern.id();
            }
            // Already live: not a new formation. Stop at the first match —
            // patterns that overlap this anchor are still reported on break.
            return pattern.id();
        }
        return null;
    }

    /**
     * Notifies live formations overlapping {@code changed} that they broke.
     * Fires {@code formed=false} for each and forgets them. Returns the first
     * broken pattern id, or {@code null} when no live formation covered the
     * changed block.
     */
    public String checkBroken(Location changed, Player actor) {
        String firstBroken = null;
        for (Map.Entry<String, String> entry : liveFormations.entrySet()) {
            if (!covers(entry.getKey(), changed)) {
                continue;
            }
            FormationPattern pattern = registry.get(entry.getValue());
            liveFormations.remove(entry.getKey());
            if (pattern != null) {
                hookBus.publishFormation(new FormationEvent(pattern, changed, null, null, actor, false));
                if (firstBroken == null) {
                    firstBroken = pattern.id();
                }
            }
        }
        return firstBroken;
    }

    /** Read-only view of live formation keys, for diagnostics and tests. */
    public Map<String, String> liveFormationsView() {
        return Map.copyOf(liveFormations);
    }

    private static String boxKey(FormationMatcher.Match match) {
        Location min = match.minCorner();
        Location max = match.maxCorner();
        return min.getWorld().getName() + ","
                + min.getBlockX() + "," + min.getBlockY() + "," + min.getBlockZ() + ","
                + max.getBlockX() + "," + max.getBlockY() + "," + max.getBlockZ();
    }

    private static boolean covers(String key, Location block) {
        String[] parts = key.split(",");
        if (!parts[0].equals(block.getWorld().getName())) {
            return false;
        }
        int x1 = Integer.parseInt(parts[1]), y1 = Integer.parseInt(parts[2]), z1 = Integer.parseInt(parts[3]);
        int x2 = Integer.parseInt(parts[4]), y2 = Integer.parseInt(parts[5]), z2 = Integer.parseInt(parts[6]);
        int x = block.getBlockX(), y = block.getBlockY(), z = block.getBlockZ();
        return x >= Math.min(x1, x2) && x <= Math.max(x1, x2)
                && y >= Math.min(y1, y2) && y <= Math.max(y1, y2)
                && z >= Math.min(z1, z2) && z <= Math.max(z1, z2);
    }
}
