package com.mineplus.infrastructure.mob.action;

import com.mineplus.infrastructure.mob.BehaviorAction;
import com.mineplus.infrastructure.mob.BehaviorContext;
import com.mineplus.infrastructure.mob.navigation.GroundProbe;
import com.mineplus.infrastructure.mob.navigation.MobNavigator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Mob;

/**
 * Idle strolls around a home anchor for special mobs that would otherwise
 * stand frozen at rest. Continuous: while it owns the entity it evaluates on
 * every engine stagger, so the stroll cadence is steady. No world writes —
 * movement only, always through {@link BehaviorContext} navigation.
 *
 * <p>Per-mob stroll state lives in a bounded per-UUID map; the action class
 * itself carries no entity state, per the {@link BehaviorAction} contract.</p>
 */
public final class WanderAction implements BehaviorAction {

    private static final double ARRIVAL_DISTANCE_SQ = 1.2 * 1.2;
    private static final long NAV_RETRY_NANOS = 2_000_000_000L;
    private static final int STATE_MAP_CAP = 512;

    /** Where the mob's home anchor is (its spawn point, a leash post, …). */
    public interface HomeAnchor extends Supplier<Location> {
    }

    public static final class Builder {
        private HomeAnchor home;
        private double radius = 8.0;
        private double speedModifier = 0.9;
        private long minDwellNanos = 4_000_000_000L;
        private long maxDwellNanos = 14_000_000_000L;

        public Builder home(HomeAnchor home) {
            this.home = home;
            return this;
        }

        public Builder radius(double radius) {
            this.radius = Math.max(2.0, radius);
            return this;
        }

        public Builder speed(double speedModifier) {
            this.speedModifier = Math.max(0.1, speedModifier);
            return this;
        }

        public Builder dwellSeconds(double min, double max) {
            this.minDwellNanos = (long) (Math.max(0.5, min) * 1_000_000_000L);
            this.maxDwellNanos = (long) (Math.max(min, max) * 1_000_000_000L);
            return this;
        }

        public WanderAction build() {
            return new WanderAction(home, radius, speedModifier, minDwellNanos, maxDwellNanos);
        }
    }

    private final HomeAnchor home;
    private final double radius;
    private final double speedModifier;
    private final long minDwellNanos;
    private final long maxDwellNanos;
    private final Map<UUID, Trip> trips = new HashMap<>();

    private WanderAction(HomeAnchor home, double radius, double speedModifier,
                         long minDwellNanos, long maxDwellNanos) {
        this.home = home;
        this.radius = radius;
        this.speedModifier = speedModifier;
        this.minDwellNanos = minDwellNanos;
        this.maxDwellNanos = maxDwellNanos;
    }

    @Override
    public String id() {
        return "wander";
    }

    @Override
    public boolean continuous() {
        return true;
    }

    @Override
    public boolean execute(BehaviorContext context) {
        if (home == null || !(context.entity() instanceof Mob mob)) {
            return false;
        }
        Location anchor = home.get();
        World world = anchor == null ? null : anchor.getWorld();
        if (world == null) {
            return false;
        }
        if (trips.size() > STATE_MAP_CAP) {
            trips.clear();
        }
        UUID id = mob.getUniqueId();
        Trip trip = trips.computeIfAbsent(id, key -> new Trip());
        Location current = mob.getLocation();
        long now = System.nanoTime();

        if (trip.target != null) {
            if (squaredHorizontal(current, trip.target) <= ARRIVAL_DISTANCE_SQ) {
                trip.target = null;
                trip.navCooldownUntil = 0L;
                trip.dwell.beginRest(now, minDwellNanos, maxDwellNanos, context.random());
                return false;
            }
            if (now >= trip.navCooldownUntil
                    && !context.navigateToWithin(trip.target, 1.0, speedModifier)) {
                trip.navCooldownUntil = now + NAV_RETRY_NANOS;
            }
            return false;
        }
        if (!trip.dwell.ready(now)) {
            return false;
        }
        double[] picked = WanderLogic.pickDestination(
                anchor.getX(), anchor.getY(), anchor.getZ(),
                current.getX(), current.getY(), current.getZ(),
                radius, MobNavigator.pathRange(mob), context.random(),
                (x, z, nearY) -> groundY(world, x, z, (int) Math.round(nearY)));
        if (picked == null) {
            trip.dwell.beginRest(now, minDwellNanos, maxDwellNanos, context.random());
            return false;
        }
        trip.target = new Location(world, picked[0], picked[1], picked[2]);
        context.navigateToWithin(trip.target, 1.0, speedModifier);
        return false;
    }

    private static double groundY(World world, double x, double z, int nearY) {
        Location spot = GroundProbe.standableColumn(world,
                (int) Math.floor(x), (int) Math.floor(z), nearY, 5);
        return spot == null ? Double.NaN : spot.getY();
    }

    private static double squaredHorizontal(Location from, Location to) {
        double dx = from.getX() - to.getX();
        double dz = from.getZ() - to.getZ();
        return dx * dx + dz * dz;
    }

    /** Per-mob stroll state. */
    private static final class Trip {
        Location target;
        long navCooldownUntil;
        final WanderLogic.Dwell dwell = new WanderLogic.Dwell();
    }
}
