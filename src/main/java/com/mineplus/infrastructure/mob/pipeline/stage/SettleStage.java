package com.mineplus.infrastructure.mob.pipeline.stage;

import com.mineplus.infrastructure.mob.navigation.GroundProbe;
import com.mineplus.infrastructure.mob.pipeline.PipelineContext;
import com.mineplus.infrastructure.mob.pipeline.PipelineStage;
import com.mineplus.infrastructure.mob.pipeline.StageResult;
import java.util.function.Consumer;
import org.bukkit.Location;

/**
 * The settle half of a settler scenario: the mob arrives in a village and takes
 * a home spot near where it was heading instead of standing on the feature
 * point.
 *
 * <p>A structure's anchor is one block — a bell, a bed centroid — and a group
 * walking to it arranges itself around it. A settler needs a <b>place</b>
 * rather than a point: a standable cell within {@code radius} of the anchor,
 * claimed for the mob so no other scenario walks it away, held while the module
 * builds whatever it came to build.
 *
 * <p>The stage stores its choice on the blackboard as {@code <destinationKey>#home}
 * so a build callback can place blocks around the same spot the stage claimed.
 * {@code onSettled} runs exactly once, when the settling pause ends — that is
 * the module's hook for a camp, a hut, a farm.
 */
public final class SettleStage implements PipelineStage {

    private final String id;
    private final String destinationKey;
    private final double radius;
    private final long settleTicks;
    private final Consumer<PipelineContext> onSettled;

    /**
     * @param id             stage id
     * @param destinationKey blackboard key holding the anchor to settle near
     * @param radius         how far from the anchor a home spot may be
     * @param settleTicks    pause after arriving before the callback runs
     * @param onSettled      the module's build callback, or {@code null}
     */
    public SettleStage(String id, String destinationKey, double radius, long settleTicks,
                       Consumer<PipelineContext> onSettled) {
        this.id = id;
        this.destinationKey = destinationKey == null || destinationKey.isBlank()
                ? JourneyStage.DEFAULT_DESTINATION_KEY : destinationKey;
        this.radius = Math.max(1.0, radius);
        this.settleTicks = Math.max(0L, settleTicks);
        this.onSettled = onSettled;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public long timeoutTicks() {
        return settleTicks + 1_200L;
    }

    @Override
    public StageResult tick(PipelineContext context) {
        Location anchor = context.location(destinationKey);
        if (anchor == null || anchor.getWorld() == null) {
            context.fail("no destination stored under '" + destinationKey + "'");
            return StageResult.FAILED;
        }
        String homeKey = destinationKey + "#home";
        Location home = context.location(homeKey);
        if (home == null) {
            Location found = GroundProbe.standableNear(anchor, (int) Math.ceil(radius), 8);
            home = found != null ? found : anchor;
            context.put(homeKey, home);
        }
        if (context.stageGameTicks() < settleTicks) {
            context.navigateToWithin(home, 2.0, 1.0);
            context.lookAt(anchor);
            return StageResult.RUNNING;
        }
        String doneKey = homeKey + "#done";
        if (!context.has(doneKey)) {
            context.put(doneKey, Boolean.TRUE);
            context.stopNavigation();
            if (onSettled != null) {
                onSettled.accept(context);
            }
        }
        return StageResult.COMPLETE;
    }
}
