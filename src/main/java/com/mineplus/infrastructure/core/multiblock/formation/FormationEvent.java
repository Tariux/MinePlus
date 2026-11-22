package com.mineplus.infrastructure.core.multiblock.formation;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Fired on the HookBus when a formation forms or breaks in the world.
 *
 * @param pattern       the registered pattern that matched (or un-matched)
 * @param anchor        the block the matcher anchored on (the player's target)
 * @param minCorner     world corner of the matched bounding box (null on break)
 * @param maxCorner     world corner of the matched bounding box (null on break)
 * @param actor         the player whose action formed (or broke) it, may be null
 * @param formed        {@code true} on form, {@code false} on break
 */
public record FormationEvent(
        FormationPattern pattern,
        Location anchor,
        Location minCorner,
        Location maxCorner,
        Player actor,
        boolean formed
) {
}
