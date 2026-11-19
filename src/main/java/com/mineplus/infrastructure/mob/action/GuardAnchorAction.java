package com.mineplus.infrastructure.mob.action;

import com.mineplus.infrastructure.mob.BehaviorAction;
import com.mineplus.infrastructure.mob.BehaviorContext;
import com.mineplus.infrastructure.mob.navigation.MobNavigator;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;

/**
 * Holds a guard at its anchor: it walks back when displaced and faces the
 * nearest qualifying threat inside the radius without leaving its post.
 * Continuous, and never writes the world — movement and look only.
 *
 * <p>The default threat is any vanilla {@link Monster}; a module narrows or
 * widens it with {@link Builder#threat(Predicate)}.</p>
 */
public final class GuardAnchorAction implements BehaviorAction {

    private static final int MAX_CANDIDATES = 16;
    private static final double RETURN_DISTANCE_SQ = 3.0 * 3.0;
    private static final long NAV_RETRY_NANOS = 2_000_000_000L;
    private static final int STATE_MAP_CAP = 512;

    /** Where the guard posts (a bell, a door, a village edge, …). */
    public interface Anchor {
        /** The guard's post, or {@code null} to skip guarding (e.g. no anchor yet). */
        Location anchor(LivingEntity mob);
    }

    public static final class Builder {
        private Anchor anchor;
        private double threatRadius = 8.0;
        private double speedModifier = 1.0;
        private Predicate<LivingEntity> threat = candidate -> candidate instanceof Monster;

        public Builder anchor(Anchor anchor) {
            this.anchor = anchor;
            return this;
        }

        public Builder threatRadius(double radius) {
            this.threatRadius = Math.max(2.0, radius);
            return this;
        }

        public Builder speed(double speedModifier) {
            this.speedModifier = Math.max(0.1, speedModifier);
            return this;
        }

        public Builder threat(Predicate<LivingEntity> threat) {
            this.threat = threat;
            return this;
        }

        public GuardAnchorAction build() {
            return new GuardAnchorAction(anchor, threatRadius, speedModifier, threat);
        }
    }

    private final Anchor anchor;
    private final double threatRadius;
    private final double speedModifier;
    private final Predicate<LivingEntity> threat;
    private final Map<UUID, Post> posts = new HashMap<>();

    private GuardAnchorAction(Anchor anchor, double threatRadius, double speedModifier,
                              Predicate<LivingEntity> threat) {
        this.anchor = anchor;
        this.threatRadius = threatRadius;
        this.speedModifier = speedModifier;
        this.threat = threat;
    }

    @Override
    public String id() {
        return "guard_anchor";
    }

    @Override
    public boolean continuous() {
        return true;
    }

    @Override
    public boolean execute(BehaviorContext context) {
        if (anchor == null || !(context.entity() instanceof Mob mob)) {
            return false;
        }
        Location post = anchor.anchor(mob);
        World world = post == null ? null : post.getWorld();
        if (world == null) {
            return false;
        }
        if (posts.size() > STATE_MAP_CAP) {
            posts.clear();
        }
        UUID id = mob.getUniqueId();
        Post state = posts.computeIfAbsent(id, key -> new Post());
        long now = System.nanoTime();
        Location current = mob.getLocation();
        double displacedSq = squaredHorizontal(current, post);

        LivingEntity threatTarget = scanThreat(mob, post);
        if (threatTarget != null) {
            MobNavigator.lookToward(mob, threatTarget.getLocation());
        }
        if (displacedSq > RETURN_DISTANCE_SQ) {
            if (now >= state.navCooldownUntil) {
                if (context.navigateToWithin(post, 1.5, speedModifier)) {
                    state.hadNavTarget = true;
                } else {
                    state.navCooldownUntil = now + NAV_RETRY_NANOS;
                }
            }
            return false;
        }
        if (threatTarget == null && state.hadNavTarget) {
            context.stopNavigation();
            state.hadNavTarget = false;
        }
        return false;
    }

    /**
     * The nearest qualifying threat inside the anchor radius, or {@code null}.
     * The decision math lives in {@link GuardLogic}; this is the entity glue.
     */
    private LivingEntity scanThreat(Mob mob, Location post) {
        Collection<Entity> nearby = post.getWorld().getNearbyEntities(
                mob.getLocation(), threatRadius, threatRadius, threatRadius);
        int count = Math.min(nearby.size(), MAX_CANDIDATES);
        if (count == 0) {
            return null;
        }
        double[] xs = new double[count];
        double[] zs = new double[count];
        boolean[] qualifies = new boolean[count];
        LivingEntity[] resolved = new LivingEntity[count];
        int filled = 0;
        for (Entity candidate : nearby) {
            if (filled >= count) {
                break;
            }
            if (candidate == mob || !(candidate instanceof LivingEntity living)) {
                continue;
            }
            if (!threat.test(living)) {
                continue;
            }
            Location at = living.getLocation();
            xs[filled] = at.getX();
            zs[filled] = at.getZ();
            qualifies[filled] = true;
            resolved[filled] = living;
            filled++;
        }
        int chosen = GuardLogic.pickThreatIndex(post.getX(), post.getZ(), threatRadius,
                xs, zs, qualifies, filled);
        return chosen < 0 ? null : resolved[chosen];
    }

    private static double squaredHorizontal(Location from, Location to) {
        double dx = from.getX() - to.getX();
        double dz = from.getZ() - to.getZ();
        return dx * dx + dz * dz;
    }

    /** Per-guard navigation bookkeeping. */
    private static final class Post {
        long navCooldownUntil;
        boolean hadNavTarget;
    }
}
