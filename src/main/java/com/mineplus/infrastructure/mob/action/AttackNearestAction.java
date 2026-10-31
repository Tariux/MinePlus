package com.mineplus.infrastructure.mob.action;

import com.mineplus.infrastructure.combat.DamageTransactions;
import com.mineplus.infrastructure.mob.BehaviorAction;
import com.mineplus.infrastructure.mob.BehaviorContext;
import com.mineplus.infrastructure.mob.custom.MobRigRenderer;
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
 * {@link Builder#target(Predicate)} replaces it for anything else.
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
            Predicate<LivingEntity> targetFilter
    ) {

        public Options {
            searchRadius = clamp(searchRadius, 1.0, 64.0);
            reach = clamp(reach, 0.5, 6.0);
            speed = clamp(speed, 0.2, 3.0);
            damage = clamp(damage, 0.5, 100.0);
            cooldownTicks = Math.max(1L, cooldownTicks);
            knockback = clamp(knockback, 0.0, 3.0);
            if (targetFilter == null) {
                throw new IllegalArgumentException("targetFilter is required");
            }
        }

        /** Players (not creative/spectator) and villagers, within 16 blocks. */
        public static Options defaults() {
            return new Options(16.0, 2.4, 1.15, 4.0, 20L, 0.35, AttackNearestAction::defaultTarget);
        }

        private static double clamp(double value, double min, double max) {
            return Math.max(min, Math.min(max, value));
        }
    }

    private final Options options;
    private final Map<UUID, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Chase> chases = new ConcurrentHashMap<>();
    private final Map<UUID, Sticky> targets = new ConcurrentHashMap<>();

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
            target = findTarget(attacker);
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

        double distance = attacker.getLocation().distance(target.getLocation());
        if (distance > options.reach() || !attacker.hasLineOfSight(target)) {
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
            return;
        }
        context.navigateTo(destination, options.speed());
        chases.put(id, new Chase(target.getUniqueId(), destination.clone(), now + REPATH_TICKS));
    }

    private void strike(Mob attacker, LivingEntity target) {
        // The swing is announced before the damage resolves: a mob that misses
        // still animates, which is what a hunting mob does most of the time.
        com.mineplus.infrastructure.mob.custom.MobRigRenderer.notifyMeleeSwing(attacker);
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

    private LivingEntity findTarget(Mob attacker) {
        double radius = options.searchRadius();
        Location origin = attacker.getLocation();
        LivingEntity best = null;
        double bestDistance = radius * radius;
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
            if (!isEligible(candidate)) {
                continue;
            }
            double distance = candidate.getLocation().distanceSquared(origin);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private boolean isEligible(LivingEntity candidate) {
        if (candidate instanceof Player player
                && (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR)) {
            return false;
        }
        try {
            return options.targetFilter().test(candidate);
        } catch (Throwable brokenFilter) {
            return false;
        }
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

        /** Replaces the default player/villager prey with {@code filter}. */
        public Builder target(Predicate<LivingEntity> filter) {
            this.targetFilter = filter;
            return this;
        }

        public AttackNearestAction build() {
            return new AttackNearestAction(new Options(searchRadius, reach, speed, damage,
                    cooldownTicks, knockback, targetFilter));
        }
    }

    private record Sticky(UUID targetId, long expiresAt) {
    }

    /** The walk currently issued for one hunter: its prey, its aim point and its refresh time. */
    private record Chase(UUID targetId, Location destination, long nextRepathAt) {
    }
}
