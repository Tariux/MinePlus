package com.mineplus.infrastructure.mob.pipeline.stage;

import com.mineplus.infrastructure.mob.journey.JourneyRunner;
import com.mineplus.infrastructure.mob.journey.JourneyStatus;
import com.mineplus.infrastructure.mob.journey.StructureAnchor;
import com.mineplus.infrastructure.mob.pipeline.PipelineContext;
import com.mineplus.infrastructure.mob.pipeline.PipelineStage;
import com.mineplus.infrastructure.mob.pipeline.StageResult;
import org.bukkit.Location;

/**
 * Walks a mob to a location remembered on the blackboard, however far away it
 * is. The actual marching is the shared {@link JourneyRunner}: follow-range
 * legs, standable waypoints, stall detection and detours, chunk leases.
 *
 * <p>Completes on arrival, fails on {@link JourneyStatus#STUCK} (the runner has
 * already tried to route around the obstacle and given up), and keeps running
 * otherwise — including the cooldown passes where the runner is deliberately
 * not re-issuing a path.
 *
 * <p>When the stage knows the destination's structure type, it re-anchors the
 * destination once the mob is close: a village located across the map is
 * anchored while its chunks are unloaded (so the resolver can only hand back
 * the raw feature point), and by the time the mob is near, the bell — the real
 * centre — is there to be found.
 */
public final class JourneyStage implements PipelineStage {

    public static final String DEFAULT_DESTINATION_KEY = "destination";
    /** How close the mob must be before the destination is re-anchored. */
    private static final double REFINE_RADIUS = 80.0;

    private final String id;
    private final String destinationKey;
    private final String structureKey;
    private final JourneyRunner.Options options;

    public JourneyStage(String id, String destinationKey, JourneyRunner.Options options) {
        this(id, destinationKey, null, options);
    }

    public JourneyStage(String id, String destinationKey, String structureKey,
                        JourneyRunner.Options options) {
        this.id = id;
        this.destinationKey = destinationKey == null || destinationKey.isBlank()
                ? DEFAULT_DESTINATION_KEY : destinationKey;
        this.structureKey = structureKey == null || structureKey.isBlank() ? null : structureKey;
        this.options = options == null ? JourneyRunner.Options.defaults() : options;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public StageResult tick(PipelineContext context) {
        Location destination = context.location(destinationKey);
        if (destination == null || destination.getWorld() == null) {
            context.fail("no destination stored under '" + destinationKey + "'");
            return StageResult.FAILED;
        }
        if (destination.getWorld() != context.world()) {
            context.fail("destination is in another world");
            return StageResult.FAILED;
        }
        double remaining = JourneyRunner.distanceXZ(context.mob().getLocation(), destination);
        if (structureKey != null) {
            Location refined = refineAnchor(context, destination, remaining);
            if (refined != null) {
                destination = refined;
                remaining = JourneyRunner.distanceXZ(context.mob().getLocation(), destination);
            }
        }
        if (remaining <= options.arrivalDistanceXZ()) {
            context.abandonJourney();
            context.lookAt(destination);
            return StageResult.COMPLETE;
        }
        JourneyStatus status = context.advanceJourney(destination, options);
        return switch (status) {
            case ARRIVED -> StageResult.COMPLETE;
            case STUCK -> {
                context.fail("stuck " + Math.round(remaining) + " blocks from the destination");
                yield StageResult.FAILED;
            }
            case NO_CAPACITY -> StageResult.RUNNING; // runner full, retry next pass
            default -> StageResult.RUNNING;
        };
    }

    /**
     * One-shot arrival-side anchoring: near the destination the structure's
     * chunks are normally loaded, so the bell (or bed centroid) can replace the
     * raw feature point. The flag lives on the blackboard so a pass that found
     * nothing does not rescan the neighbourhood every tick.
     */
    private Location refineAnchor(PipelineContext context, Location destination, double remaining) {
        String flag = destinationKey + "#anchored";
        if (remaining > REFINE_RADIUS || context.has(flag)) {
            return null;
        }
        context.put(flag, Boolean.TRUE);
        Location refined = StructureAnchor.anchor(context.world(), structureKey, destination);
        if (refined == null || refined.getWorld() != destination.getWorld()) {
            return null;
        }
        if (JourneyRunner.distanceXZ(refined, destination) < 1.0) {
            return null;
        }
        context.put(destinationKey, refined);
        return refined;
    }
}
