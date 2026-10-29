package com.mineplus.infrastructure.mob.pipeline.stage;

import com.mineplus.infrastructure.mob.pipeline.PipelineContext;
import com.mineplus.infrastructure.mob.pipeline.PipelineStage;
import com.mineplus.infrastructure.mob.pipeline.StageResult;
import org.bukkit.Location;

/**
 * Finds the nearest structure of a type and writes its walkable centre onto the
 * pipeline blackboard.
 *
 * <p>The lookup goes through the engine's cached, cost-gated structure service
 * and {@link com.mineplus.infrastructure.mob.journey.StructureAnchor}, so the
 * remembered location is a village's bell (not its roof) and a repeated
 * pipeline in the same region does not re-run world generation.
 *
 * <p>Completes as soon as a structure is found; stays {@code RUNNING} while the
 * search is cooling down or nothing exists in range, and fails the pipeline
 * after {@link #timeoutTicks()} so a mob in an empty ocean does not hold the
 * scenario forever.
 */
public final class LocateStructureStage implements PipelineStage {

    private final String id;
    private final String structureKey;
    private final String blackboardKey;
    private final int searchRadiusChunks;
    private final long timeoutTicks;

    public LocateStructureStage(String id, String structureKey, String blackboardKey,
                                int searchRadiusChunks, long timeoutTicks) {
        this.id = id;
        this.structureKey = structureKey;
        this.blackboardKey = blackboardKey;
        this.searchRadiusChunks = Math.max(4, Math.min(256, searchRadiusChunks));
        this.timeoutTicks = timeoutTicks <= 0 ? 1_200L : timeoutTicks;
    }

    /**
     * The common case: find a village within 96 chunks, remember it under
     * {@code blackboardKey}, give the search two in-game minutes.
     */
    public static LocateStructureStage village(String id, String blackboardKey) {
        return new LocateStructureStage(id, "village", blackboardKey, 96, 2_400L);
    }

    /** The key the found location is stored under. */
    public String blackboardKey() {
        return blackboardKey;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public long timeoutTicks() {
        return timeoutTicks;
    }

    @Override
    public StageResult tick(PipelineContext context) {
        if (context.has(blackboardKey)) {
            return StageResult.COMPLETE;
        }
        Location found = context.nearestStructure(structureKey, searchRadiusChunks);
        if (found == null) {
            return StageResult.RUNNING; // still cooling down, or nothing in range
        }
        context.put(blackboardKey, found);
        context.lookAt(found);
        return StageResult.COMPLETE;
    }
}
