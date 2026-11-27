package com.mineplus.infrastructure.collision.source;

import com.mineplus.infrastructure.collision.mask.OccupancyMask;
import java.util.List;

/**
 * Something that can turn a model into an occupancy grid.
 *
 * <p>Three interchangeable implementations exist — {@link AutoOccupancySource}
 * (model JSON), {@link BlueprintOccupancySource} (hand-authored cells) and
 * {@link FillOccupancySource} (the whole box) — plus
 * {@link HybridOccupancySource}, which lets a blueprint override AUTO.</p>
 *
 * <p>{@link #build()} may be called on a worker thread: implementations work on
 * parsed JSON, never on Bukkit objects.</p>
 */
public interface OccupancySource {

    /** Builds a fresh grid; callers may keep or mutate the result. */
    OccupancyResult build();

    /** Short description for debug output and fit cache keys. */
    String describe();

    /**
     * Grid plus per-cell overrides and human-readable warnings.
     *
     * @param mask       the occupancy grid
     * @param directives blueprint overrides (never null; may be empty)
     * @param warnings   non-fatal authoring notes (thin planes, missing parent,
     *                   out-of-range regions, ...)
     */
    record OccupancyResult(OccupancyMask mask, DirectiveGrid directives, List<String> warnings) {
        public OccupancyResult {
            if (mask == null) {
                throw new IllegalArgumentException("occupancy result needs a mask");
            }
            directives = directives == null
                    ? DirectiveGrid.empty(mask.blocksX(), mask.blocksY(), mask.blocksZ())
                    : directives;
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
