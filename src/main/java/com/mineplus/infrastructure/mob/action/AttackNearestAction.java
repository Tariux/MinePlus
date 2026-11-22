package com.mineplus.infrastructure.mob.action;

import com.mineplus.infrastructure.combat.DamageTransactions;
import com.mineplus.infrastructure.mob.BehaviorAction;
import com.mineplus.infrastructure.mob.BehaviorContext;
import com.mineplus.infrastructure.mob.MobMemory;
import com.mineplus.infrastructure.mob.custom.MobRenderEngine;
import com.mineplus.infrastructure.mob.navigation.GroundProbe;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.util.Vector;

/**
 * A mob hunts the nearest eligible target: walks to it, then strikes on a
 * cooldown with knockback and an attack sound.
 *
 * <p>Core-owned because every hostile custom mob needs the same three things
 * done right: a target that does not flicker between two equally near
 * villagers, damage routed through {@link DamageTransactions} so a module's
 * suppression listener cannot swallow the mob's own hit, and movement issued
 * through the shared navigator (never a teleport, never a raw velocity hack).
 *
 * <p>The default target set is "a player or a villager": the Evil Pig's role.
 * {@link Builder#target(Predicate)} replaces it for anything else. A creative or
 * spectator player is hunted like anyone else — the mob walks up and swings —
 * but is never damaged or pushed, because the server cannot hurt them anyway.
 * Excluding them from targeting entirely would leave a hostile mob standing
 * perfectly still next to the one player testing it.
 *
 * <p>Distances are blocks; intervals and cooldowns are server ticks. The action
 * keeps only per-attacker bookkeeping (current target, cooldown, last repath)
 * and prunes it as it grows, so a long-lived world does not accumulate state.
 */
public final class AttackNearestAction implements BehaviorAction {

    /** How long a mob keeps its chosen target before it is willing to switch. */
    private static final long RETARGET_TICKS = 60L;
    /** Repath cadence while closing the distance. */
    private static final long REPATH_TICKS = 10L;
    /**
     * A target that stepped this far from the point the last path was aimed at
     * earns an early repath; otherwise a mob chases the ground its prey left.
     */
    private static final double REPATH_TARGET_MOVE_SQ = 2.25; // 1.5 blocks
    /**
     * A hunter that has moved less than this from where its last path was
     * issued is stuck (a fence, a mob pile-up, a lip of terrain); the chase
     * then force-repaths and finally gives up instead of grinding forever.
     */
    private static final double STUCK_MOVE_SQ = 0.25; // 0.5 blocks
    /** Stuck repaths tolerated before the hunt is abandoned for one cooldown. */
    private static final int MAX_STUCK_REPATHS = 3;
    /** How long a hunter keeps looking where its prey was last visible. */
    private static final long LOST_SIGHT_TICKS = 100L;
    /** How close to the last-seen spot counts as "arrived, nothing here" (squared). */
    private static final double ARRIVED_AT_LAST_SEEN_SQ = 2.25; // 1.5 blocks
    /** Bookkeeping ceiling before expired entries are swept. */
    private static final int STATE_SOFT_LIMIT = 512;

    /** Tunables of one hunting behavior. */
    public record Options(
            double searchRadius,
            double reach,
            double speed,
            double damage,
            long cooldownTicks,
            double knockback,
            double preferredDistance,
            Predicate<LivingEntity> targetFilter
    ) {

        public Options {
            searchRadius = clamp(searchRadius, 1.0, 64.0);
            reach = clamp(reach, 0.5, 6.0);
            speed = clamp(speed, 0.2, 3.0);
            damage = clamp(damage, 0.5, 100.0);
            cooldownTicks = Math.max(1L, cooldownTicks);
            knockback = clamp(knockback, 0.0, 3.0);
            preferredDistance = clamp(preferredDistance, 0.0, 16.0);
            if (targetFilter == null) {
                throw new IllegalArgumentException("targetFilter is required");
            }
        }

        /** Players and villagers, within 16 blocks, straight melee. */
        public static Options defaults() {
            return new Options(16.0, 2.4, 1.15, 4.0, 20L, 0.35, 0.0, AttackNearestAction::defaultTarget);
        }

        private static double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    private final Options options;
    private final Map<UUID, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Chase> chases = new ConcurrentHashMap<>();
    private final Map<UUID, Sticky> targets = new ConcurrentHashMap<>();
    private final Map<UUID, LastSeen> lastSeens = new ConcurrentHashMap<>();

    public AttackNearestAction() {
        this(Options.defaults());
    }

    public AttackNearestAction(Options options) {
        this.options = options == null ? Options.defaults() : options;
    }

    @Override
    public String id() {
        return "attack_nearest";
    }

    @Override
    public boolean continuous() {
        return true;
    }

    /** A hunter with a grudge is more urgent than one merely patrolling. */
    @Override
    public double urgency(BehaviorContext context) {
        return context.memory().lastAttacker(context.entity().getWorld().getGameTime()) != null ? 0.9 : 0.5;
    }

    @Override
    public boolean execute(BehaviorContext context) {
        if (!(context.entity() instanceof Mob attacker) || attacker.isDead() || !attacker.isValid()) {
            return false;
        }
        UUID id = attacker.getUniqueId();
        World world = attacker.getWorld();
        long now = world.getGameTime();
        prune(now);

        LivingEntity target = stickyTarget(id, attacker, now);
        if (target == null) {
            target = findTarget(context, attacker);
            if (target == null) {
                // The hunt is over: without this the mob keeps walking to the
                // last spot its prey occupied.
                if (chases.remove(id) != null) {
                    context.stopNavigation();
                }
                return false;
            }
            targets.put(id, new Sticky(target.getUniqueId(), now + RETARGET_TICKS));
        }
        // Keep the hurt-memory fresh for the prey as long as the hunt runs, so
        // other behaviors (flee, taunts) can reason about the ongoing fight.
        context.memory().sense(target, now);

        boolean sight = attacker.hasLineOfSight(target);
        if (sight) {
            lastSeens.put(id, new LastSeen(target.getLocation().clone(), now));
        }

        double distance = attacker.getLocation().distance(target.getLocation());
        if (sight && options.preferredDistance() > 0.0 && distance < options.preferredDistance() * 0.5) {
            // Ranged-style mob: the prey has closed inside the stand-off range,
            // so back away instead of grinding into it (and its sword).
            backAway(context, id, attacker, target, now);
            return true;
        }
        if (!sight) {
            // The prey broke line of sight: hunt its last-seen spot instead of
            // pathing toward a position the mob cannot see (and through the
            // wall between them).
            return chaseLastSeen(context, id, attacker, target, now);
        }
        if (distance > options.reach()) {
            chase(context, id, attacker, target, now);
            return true;
        }

        // In reach: stop the walk before striking, or the mob keeps pushing
        // into its prey (and past it) while the hit cooldown runs.
        if (chases.remove(id) != null) {
            context.stopNavigation();
        }

        Long until = cooldownUntil.get(id);
        if (until != null && now < until) {
            return true;
        }

        strike(attacker, target);
        cooldownUntil.put(id, now + options.cooldownTicks());
        targets.put(id, new Sticky(target.getUniqueId(), now + RETARGET_TICKS));
        return true;
    }

    /** Drops the hunt and backs off for one cooldown, so an unreachable or
     * vanished target is not re-acquired the very next evaluation. */
    private void giveUp(BehaviorContext context, UUID id, long now) {
        targets.remove(id);
        chases.remove(id);
        lastSeens.remove(id);
        cooldownUntil.put(id, now + options.cooldownTicks());
        context.stopNavigation();
    }

    /**
     * Lost-sight pursuit: walk to where the prey was last visible, give up
     * when the trail expires or the hunter arrives and finds nothing. A fresh
     * hunt that has never seen its prey (a grudge through a wall) treats the
     * prey's current position as the last-seen spot once — the mob sniffed
     * something, it goes and looks.
     */
    private boolean chaseLastSeen(BehaviorContext context, UUID id, Mob attacker, LivingEntity target, long now) {
        LastSeen seen = lastSeens.get(id);
        if (seen == null) {
            seen = new LastSeen(target.getLocation().clone(), now);
            lastSeens.put(id, seen);
        }
        long age = now - seen.tick();
        Location here = attacker.getLocation();
        LostSight.Verdict verdict = LostSight.decide(age, here.distanceSquared(seen.location()),
                ARRIVED_AT_LAST_SEEN_SQ, LOST_SIGHT_TICKS);
        if (verdict == LostSight.Verdict.PURSUE) {
            context.navigateTo(seen.location(), options.speed());
            chases.put(id, new Chase(target.getUniqueId(), seen.location().clone(), now + REPATH_TICKS,
                    here.clone(), 0));
            return true;
        }
        // The trail is dead: arrive-and-searched, or cold. Back off, then let
        // the next normal scan decide whether someone else is worth hunting.
        giveUp(context, id, now);
        return false;
    }

    /**
     * Issues (or re-issues) the walk toward {@code target}. A path is only
     * requested when the previous one is due for a refresh or the target left
     * the spot it was aimed at, so a mob closing on a standing target is not
     * re-routed every evaluation.
     */
    private void chase(BehaviorContext context, UUID id, Mob attacker, LivingEntity target, long now) {
        Location destination = target.getLocation();
        Chase chase = chases.get(id);
        boolean stale = chase == null
                || !chase.targetId().equals(target.getUniqueId())
                || now >= chase.nextRepathAt()
                || chase.destination().distanceSquared(destination) > REPATH_TARGET_MOVE_SQ;
        if (!stale) {
            // Stuck check: the path is still fresh, but the hunter has barely
            // moved from where it was when the path was issued. Force one
            // repath per cycle; after a few, give up the hunt for a cooldown
            // instead of pushing into the obstacle forever.
            if (chase.attackerOrigin().distanceSquared(attacker.getLocation()) <= STUCK_MOVE_SQ) {
                int cycles = chase.stuckCycles() + 1;
                if (cycles > MAX_STUCK_REPATHS) {
                    giveUp(context, id, now);
                    return;
                }
                context.navigateTo(destination, options.speed());
                chases.put(id, new Chase(target.getUniqueId(), destination.clone(),
                        now + REPATH_TICKS, attacker.getLocation().clone(), cycles));
            }
            return;
        }
        context.navigateTo(destination, options.speed());
        chases.put(id, new Chase(target.getUniqueId(), destination.clone(), now + REPATH_TICKS,
                attacker.getLocation().clone(), 0));
    }

    /**
     * Steps the mob out of the prey's face: a retreat point one step further
     * than the stand-off range along the flee heading, snapped to standable
     * ground so the backing-off walk does not aim into a wall.
     */
    private void backAway(BehaviorContext context, UUID id, Mob attacker, LivingEntity target, long now) {
        Location here = attacker.getLocation();
        Location theirs = target.getLocation();
        double dx = here.getX() - theirs.getX();
        double dz = here.getZ() - theirs.getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4) {
            dx = 1.0;
            dz = 0.0;
        } else {
            dx /= length;
            dz /= length;
        }
        double step = options.preferredDistance() - here.distance(theirs) + 2.0;
        Location retreat = here.clone().add(dx * step, 0.0, dz * step);
        Location standable = GroundProbe.standableNear(retreat, 2, 3);
        if (standable != null) {
            retreat = standable;
        }
        context.navigateTo(retreat, options.speed());
        chases.put(id, new Chase(target.getUniqueId(), retreat.clone(), now + REPATH_TICKS,
                here.clone(), 0));
    }

    private void strike(Mob attacker, LivingEntity target) {
        // The swing is announced before the damage resolves: a mob that misses
        // still animates, which is what a hunting mob does most of the time.
        MobRenderEngine.notifyMeleeSwing(attacker);
        if (!canHurt(target)) {
            return;
        }
        DamageTransactions.apply(target, options.damage(), attacker);
        if (options.knockback() > 0.0) {
            Vector push = target.getLocation().toVector().subtract(attacker.getLocation().toVector());
            push.setY(0.0);
            if (push.lengthSquared() > 1.0E-6) {
                push.normalize().multiply(options.knockback()).setY(0.32);
                target.setVelocity(target.getVelocity().multiply(0.6).add(push));
            }
        }
        Location hit = target.getLocation().add(0.0, Math.min(1.0, target.getHeight() * 0.5), 0.0);
        attacker.getWorld().playSound(hit, Sound.ENTITY_PLAYER_ATTACK_STRONG, 0.9f, 1.0f);
        attacker.getWorld().playSound(hit, Sound.ENTITY_GENERIC_HURT, 0.7f, 1.1f);
    }

    /** The target this mob is committed to, or {@code null} when it expired or vanished. */
    private LivingEntity stickyTarget(UUID attackerId, Mob attacker, long now) {
        Sticky sticky = targets.get(attackerId);
        if (sticky == null) {
            return null;
        }
        if (now >= sticky.expiresAt()) {
            targets.remove(attackerId, sticky);
            return null;
        }
        Entity entity = Bukkit.getEntity(sticky.targetId());
        if (!(entity instanceof LivingEntity living) || living.isDead() || !living.isValid()
                || living.getWorld() != attacker.getWorld()) {
            targets.remove(attackerId, sticky);
            return null;
        }
        double limit = options.searchRadius() * 1.5;
        if (living.getLocation().distanceSquared(attacker.getLocation()) > limit * limit) {
            targets.remove(attackerId, sticky);
            return null;
        }
        return living;
    }

    /**
     * Picks prey by situation, not by raw distance: {@link TargetSelector}
     * weighs proximity against line of sight, the mob's hurt memory (a mob
     * retaliates against whoever hurt it, even if someone else is nearer) and
     * target stickiness. {@code currentTargetId} may be {@code null}.
     */
    private LivingEntity findTarget(BehaviorContext context, Mob attacker) {
        double radius = options.searchRadius();
        Location origin = attacker.getLocation();
        MobMemory memory = context.memory();
        long now = attacker.getWorld().getGameTime();
        UUID grudge = memory.mostHated(now);

        List<TargetSelector.Candidate> candidates = new ArrayList<>();
        List<LivingEntity> prey = new ArrayList<>();
        // World#getNearbyEntities(Location, x, y, z) is the one query every
        // server implementation ships; the living-entity/type variants are
        // Paper-only and fail to link on Spigot.
        for (Entity entity : origin.getWorld().getNearbyEntities(origin, radius, radius * 0.75, radius)) {
            if (!(entity instanceof LivingEntity candidate)) {
                continue;
            }
            if (candidate == attacker || candidate.isDead() || !candidate.isValid()) {
                continue;
            }
            if (!isPrey(candidate)) {
                continue;
            }
            double distanceSq = candidate.getLocation().distanceSquared(origin);
            if (distanceSq >= radius * radius) {
                continue;
            }
            boolean visible = attacker.hasLineOfSight(candidate);
            candidates.add(new TargetSelector.Candidate(candidate.getUniqueId(), distanceSq, visible,
                    grudge != null && grudge.equals(candidate.getUniqueId())));
            prey.add(candidate);
        }
        TargetSelector.Candidate best = TargetSelector.best(candidates, radius * radius, null);
        if (best == null) {
            return null;
        }
        return prey.get(candidates.indexOf(best));
    }

    /**
     * Creative and spectator players are still prey: a mob whose only nearby
     * player is in creative would otherwise never pick a target and stand
     * perfectly still, which reads as broken AI to the very tester checking it.
     * They are simply never hurt (see {@link #strike}).
     */
    private boolean isPrey(LivingEntity candidate) {
        try {
            return options.targetFilter().test(candidate);
        } catch (Throwable brokenFilter) {
            return false;
        }
    }

    /** {@code false} for the game modes that take no entity damage. */
    private static boolean canHurt(LivingEntity candidate) {
        if (!(candidate instanceof Player player)) {
            return true;
        }
        GameMode mode = player.getGameMode();
        return mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR;
    }

    /** Sweeps bookkeeping that outlived its entities. */
    private void prune(long now) {
        if (cooldownUntil.size() > STATE_SOFT_LIMIT) {
            cooldownUntil.entrySet().removeIf(entry -> entry.getValue() < now - 200L);
        }
        if (chases.size() > STATE_SOFT_LIMIT) {
            chases.entrySet().removeIf(entry -> entry.getValue().nextRepathAt() < now - 200L);
        }
        if (targets.size() > STATE_SOFT_LIMIT) {
            targets.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        }
        if (lastSeens.size() > STATE_SOFT_LIMIT) {
            lastSeens.entrySet().removeIf(entry -> entry.getValue().tick() < now - LOST_SIGHT_TICKS - 200L);
        }
    }

    /** The default prey: players and villagers. */
    private static boolean defaultTarget(LivingEntity candidate) {
        return candidate instanceof Player || candidate instanceof Villager;
    }

    /** The prey a custom mob hunts. */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private double searchRadius = 16.0;
        private double reach = 2.4;
        private double speed = 1.15;
        private double damage = 4.0;
        private long cooldownTicks = 20L;
        private double knockback = 0.35;
        private double preferredDistance = 0.0;
        private Predicate<LivingEntity> targetFilter = AttackNearestAction::defaultTarget;

        private Builder() {
        }

        public Builder searchRadius(double value) {
            this.searchRadius = value;
            return this;
        }

        public Builder reach(double value) {
            this.reach = value;
            return this;
        }

        /** Navigator speed modifier (1.0 = the mob's normal walk). */
        public Builder speed(double value) {
            this.speed = value;
            return this;
        }

        public Builder damage(double value) {
            this.damage = value;
            return this;
        }

        public Builder cooldownTicks(long value) {
            this.cooldownTicks = value;
            return this;
        }

        /** Horizontal push per hit; {@code 0} disables it. */
        public Builder knockback(double value) {
            this.knockback = value;
            return this;
        }

        /**
         * Stand-off range for ranged-style mobs: while the prey is inside half
         * this distance the mob backs away instead of striking (0 = straight
         * melee, the default).
         */
        public Builder preferredDistance(double value) {
            this.preferredDistance = value;
            return this;
        }

        /** Replaces the default player/villager prey with {@code filter}. */
        public Builder target(Predicate<LivingEntity> filter) {
            this.targetFilter = filter;
            return this;
        }

        public AttackNearestAction build() {
            return new AttackNearestAction(new Options(searchRadius, reach, speed, damage,
                    cooldownTicks, knockback, preferredDistance, targetFilter));
        }
    }

    private record Sticky(UUID targetId, long expiresAt) {
    }

    /** Where and when the prey was last visible to its hunter. */
    private record LastSeen(Location location, long tick) {
    }

    /** The walk currently issued for one hunter: its prey, its aim point, its
     * refresh time, where the hunter stood when the path was issued (stuck
     * detection) and how many stuck cycles it has burned. */
    private record Chase(UUID targetId, Location destination, long nextRepathAt,
                         Location attackerOrigin, int stuckCycles) {
    }
}
