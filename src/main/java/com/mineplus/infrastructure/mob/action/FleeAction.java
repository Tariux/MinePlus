package com.mineplus.infrastructure.mob.action;

import com.mineplus.infrastructure.mob.BehaviorAction;
import com.mineplus.infrastructure.mob.BehaviorContext;
import com.mineplus.infrastructure.mob.navigation.GroundProbe;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Flee behavior: when a mob is badly hurt (or its last attacker is close), it
 * runs away from the nearest threat instead of standing in the open or, worse,
 * walking toward it.
 *
 * <p>The threat set is the remembered players around the mob plus anything in
 * its hurt memory; the heading is chosen by {@link FleeLogic} so the mob
 * escapes through gaps between threats rather than straight away from the
 * nearest one. The retreat point is snapped to standable ground with
 * {@link GroundProbe#standableNear} before navigation, so a fleeing mob never
 * pathfinds into a wall or off its perch into lava by blind vector math.
 */
public final class FleeAction implements BehaviorAction {

    private final Options options;
    /** Mob id → game tick until which it keeps fleeing after being triggered. */
    private final Map<UUID, Long> fleeUntil = new ConcurrentHashMap<>();

    /** Tuning for one flee behavior. */
    public record Options(double triggerHealthFraction, double scanRadius, double fleeDistance,
                          double speed, long memoryTicks, Predicate<LivingEntity> threatFilter) {

        public Options {
            if (triggerHealthFraction < 0.0 || triggerHealthFraction > 1.0) {
                throw new IllegalArgumentException("triggerHealthFraction must be within 0..1");
            }
            if (threatFilter == null) {
                throw new IllegalArgumentException("threatFilter is required");
            }
        }

        public static Options defaults() {
            return new Options(0.4, 12.0, 10.0, 1.25, 20L * 4, FleeAction::defaultThreat);
        }
    }

    private FleeAction(Options options) {
        this.options = options;
    }

    @Override
    public String id() {
        return "flee";
    }

    @Override
    public boolean continuous() {
        return true;
    }

    /** Panic rises as health falls: a dying mob flees before anything else. */
    @Override
    public double urgency(BehaviorContext context) {
        if (!(context.entity() instanceof LivingEntity me) || me.isDead()) {
            return 0.0;
        }
        double maxHealth = me.getMaxHealth();
        if (maxHealth <= 0.0) {
            return 0.5;
        }
        double panic = 1.0 - me.getHealth() / maxHealth;
        return Math.max(0.5, panic);
    }

    @Override
    public boolean execute(BehaviorContext context) {
        if (!(context.entity() instanceof Mob me) || me.isDead() || !me.isValid()) {
            return false;
        }
        Location here = me.getLocation();
        long now = me.getWorld().getGameTime();
        UUID threatId = context.memory().lastAttacker(now);
        LivingEntity primaryThreat = threatId == null ? null : entity(context, threatId);
        double maxHealth = me.getMaxHealth();
        boolean hurt = maxHealth > 0.0 && me.getHealth() / maxHealth <= options.triggerHealthFraction();
        Long until = fleeUntil.get(me.getUniqueId());
        boolean panicking = hurt || (until != null && now < until);
        if (hurt) {
            fleeUntil.put(me.getUniqueId(), now + options.memoryTicks());
        } else if (until != null && now >= until) {
            fleeUntil.remove(me.getUniqueId());
        }

        if (primaryThreat == null || !isThreat(primaryThreat)) {
            primaryThreat = nearestThreat(context, here);
        }
        if (primaryThreat == null) {
            return false;
        }
        if (!panicking && !threatTooClose(primaryThreat, here)) {
            return false;
        }

        List<LivingEntity> allThreats = threatsAround(context, here);
        double[] secondaries = new double[Math.max(0, allThreats.size() - 1)];
        double awayAngle = FleeLogic.headingOf(
                here.getX() - primaryThreat.getLocation().getX(),
                here.getZ() - primaryThreat.getLocation().getZ());
        int i = 0;
        for (LivingEntity other : allThreats) {
            if (other == primaryThreat) {
                continue;
            }
            secondaries[i++] = FleeLogic.headingOf(
                    here.getX() - other.getLocation().getX(),
                    here.getZ() - other.getLocation().getZ());
        }
        double heading = FleeLogic.bestHeading(awayAngle, secondaries, 0.5, 16);

        double dist = here.distance(primaryThreat.getLocation());
        double gap = Math.max(2.0, options.fleeDistance() - dist);
        Location retreat = here.clone().add(Math.cos(heading) * gap, 0.0, Math.sin(heading) * gap);
        Location standable = GroundProbe.standableNear(retreat, 2, 3);
        if (standable == null) {
            standable = retreat;
        }
        if (!context.navigateTo(standable, options.speed())) {
            context.stopNavigation();
            return false;
        }
        return true;
    }

    private boolean threatTooClose(LivingEntity threat, Location here) {
        return threat.getLocation().distanceSquared(here) <= options.scanRadius() * options.scanRadius();
    }

    private boolean isThreat(LivingEntity candidate) {
        try {
            return options.threatFilter().test(candidate);
        } catch (Throwable brokenFilter) {
            return false;
        }
    }

    private LivingEntity entity(BehaviorContext context, UUID id) {
        return org.bukkit.Bukkit.getEntity(id) instanceof LivingEntity living ? living : null;
    }

    private LivingEntity nearestThreat(BehaviorContext context, Location here) {
        LivingEntity best = null;
        double bestDistance = options.scanRadius() * options.scanRadius();
        for (LivingEntity candidate : candidates(context, here)) {
            if (!isThreat(candidate)) {
                continue;
            }
            double distance = candidate.getLocation().distanceSquared(here);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private List<LivingEntity> threatsAround(BehaviorContext context, Location here) {
        List<LivingEntity> found = new ArrayList<>();
        for (LivingEntity candidate : candidates(context, here)) {
            if (isThreat(candidate)) {
                found.add(candidate);
            }
        }
        return found;
    }

    private List<LivingEntity> candidates(BehaviorContext context, Location here) {
        double r = options.scanRadius();
        List<LivingEntity> found = new ArrayList<>();
        for (org.bukkit.entity.Entity entity : here.getWorld().getNearbyEntities(here, r, r * 0.75, r)) {
            if (entity instanceof LivingEntity living && living.isValid() && !living.isDead()) {
                found.add(living);
            }
        }
        return found;
    }

    /** The default fear: players. */
    private static boolean defaultThreat(LivingEntity candidate) {
        return candidate instanceof Player;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private double triggerHealthFraction = 0.4;
        private double scanRadius = 12.0;
        private double fleeDistance = 10.0;
        private double speed = 1.25;
        private long memoryTicks = 20L * 4;
        private Predicate<LivingEntity> threatFilter = FleeAction::defaultThreat;

        private Builder() {
        }

        public Builder triggerHealthFraction(double value) {
            this.triggerHealthFraction = value;
            return this;
        }

        public Builder scanRadius(double value) {
            this.scanRadius = value;
            return this;
        }

        public Builder fleeDistance(double value) {
            this.fleeDistance = value;
            return this;
        }

        public Builder speed(double value) {
            this.speed = value;
            return this;
        }

        public Builder memoryTicks(long value) {
            this.memoryTicks = value;
            return this;
        }

        /** Replaces the default player-fear with {@code filter}. */
        public Builder threat(Predicate<LivingEntity> filter) {
            this.threatFilter = filter;
            return this;
        }

        public FleeAction build() {
            return new FleeAction(new Options(triggerHealthFraction, scanRadius, fleeDistance,
                    speed, memoryTicks, threatFilter));
        }
    }
}
