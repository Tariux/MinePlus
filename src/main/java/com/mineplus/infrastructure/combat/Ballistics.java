package com.mineplus.infrastructure.combat;

import com.mineplus.infrastructure.fx.ParticleFx;
import com.mineplus.infrastructure.fx.Sounds;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * The Core's projectile engine: server-side simulated rounds, stepped a
 * fraction of their path per tick, with no projectile entity ever spawned.
 *
 * <p>Why simulated rather than {@code spawnArrow}: a vanilla arrow costs one
 * tracked entity per shot (entity metadata, movement packets to every nearby
 * client, physics, despawn), it is affected by whatever the server's projectile
 * rules already do, and it cannot be given a truthful ballistic model. A
 * simulated round costs one object from a pool, one ray trace per tick, and a
 * handful of particles — and it hits exactly where the ballistics say.
 *
 * <p><b>One task, many rounds.</b> A single repeating task advances every
 * in-flight round; there is never a task per shot or per weapon. Round objects
 * are pooled, and the hot loop allocates only the vectors Bukkit's ray trace
 * requires.
 *
 * <p><b>Bounded, never queued.</b> {@link Settings#maxActiveRounds()} caps the
 * simulation. Over the cap, a shot degrades to one instant trace over its full
 * range instead of waiting in a queue: the player gets the same hit result the
 * same tick, and the server never accumulates backlog under fire.
 *
 * <p><b>Damage is transactional.</b> Hits land through
 * {@link DamageTransactions#apply}, so a module's own "cancel vanilla melee"
 * listener cannot cancel the damage the engine just computed — the failure mode
 * that silently zeroes every simulated hit.
 *
 * <p>Threading: fire and tick are main-thread operations; {@link #shutdown()}
 * is idempotent.
 */
public final class Ballistics {

    /** Engine ticks with nothing in flight before the simulation task retires. */
    private static final int IDLE_TASK_RETIRE_TICKS = 60;

    /** Tunable operating envelope of the engine. */
    public record Settings(int maxActiveRounds, boolean emitTracers, long intervalTicks) {

        public static Settings defaults() {
            return new Settings(128, true, 1L);
        }

        public Settings {
            maxActiveRounds = Math.max(1, maxActiveRounds);
            intervalTicks = Math.max(1L, intervalTicks);
        }
    }

    private final Plugin plugin;
    private final ParticleFx particles;
    private final List<Round> rounds = new ArrayList<>(64);
    private final Deque<Round> pool = new ArrayDeque<>();

    private volatile Settings settings = Settings.defaults();
    private volatile boolean enabled = true;
    private BukkitTask task;

    private long totalRounds;
    private long totalHits;
    private long degradedShots;
    private long tickStamp;
    private int idleTicks;

    public Ballistics(Plugin plugin, ParticleFx particles) {
        this.plugin = plugin;
        this.particles = particles;
    }

    /** Creates and starts an engine bound to {@code plugin}'s lifecycle. */
    public static Ballistics start(Plugin plugin, ParticleFx particles) {
        Ballistics ballistics = new Ballistics(plugin, particles);
        ballistics.start();
        return ballistics;
    }

    /**
     * Arms the simulation task immediately instead of waiting for the first
     * shot. {@link #fire} self-arms, so this is only needed by a host that
     * wants the engine warm.
     */
    public void start() {
        if (task != null || !plugin.isEnabled()) {
            return;
        }
        idleTicks = 0;
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick,
                settings.intervalTicks(), settings.intervalTicks());
    }

    /** Cancels the run task and drops every in-flight round. Idempotent. */
    public void shutdown() {
        BukkitTask current = task;
        task = null;
        if (current != null) {
            current.cancel();
        }
        rounds.clear();
        pool.clear();
    }

    public void configure(Settings newSettings) {
        Settings previous = this.settings;
        this.settings = newSettings == null ? Settings.defaults() : newSettings;
        BukkitTask current = task;
        if (current != null && previous.intervalTicks() != this.settings.intervalTicks()) {
            // The cadence is a schedule property, not a loop property: re-arm.
            current.cancel();
            task = null;
            start();
        }
    }

    public Settings settings() {
        return settings;
    }

    public ParticleFx effects() {
        return particles;
    }

    /**
     * Applies the combat slice of {@code settings.mp.yml}: the engine's own
     * envelope plus the particle budgets it spends. Safe while running.
     */
    public void applySettings(CombatSettings newSettings) {
        CombatSettings value = newSettings == null ? CombatSettings.defaults() : newSettings;
        this.enabled = value.enabled();
        particles.configure(value.criticalParticlesPerTick(), value.ambientParticlesPerTick(),
                value.particlesPerEmit());
        configure(value.ballistics());
    }

    /** {@code true} while the engine accepts shots. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Launches one round.
     *
     * @param aimDirection the shooter's line of sight (any length; it is
     *                     normalized here and re-scaled to the profile speed)
     * @return {@code false} when the origin is not usable
     */
    public boolean fire(Player shooter, Location origin, Vector aimDirection, BallisticProfile profile,
                        ProjectileHandler handler) {
        if (!enabled || origin == null || origin.getWorld() == null || profile == null
                || aimDirection == null || aimDirection.lengthSquared() < 1.0e-8) {
            return false;
        }
        ProjectileHandler sink = handler == null ? ProjectileHandler.STANDARD : handler;
        Vector unit = aimDirection.clone().normalize();
        Vector velocity = applySpread(unit, profile.spreadDegrees()).multiply(profile.velocity());
        Location start = origin.clone();

        emitLaunchFx(shooter, start, unit, profile);
        sink.onLaunch(shooter, start, unit, profile);

        if (rounds.size() >= settings.maxActiveRounds()) {
            // Over budget: resolve the whole path now instead of growing the
            // simulation. Same damage, same hit, one tick earlier than a
            // stepped round would reach it.
            degradedShots++;
            instantTrace(shooter, start, velocity, profile, sink);
            return true;
        }
        acquire(start, velocity, profile, shooter == null ? null : shooter.getUniqueId(), sink);
        totalRounds++;
        start();
        return true;
    }

    /**
     * Launches a whole weapon shot: {@code profile.pellets()} independent rounds
     * down the same cone, each with its own spread sample.
     */
    public int fireWeapon(Player shooter, Location origin, Vector aimDirection, BallisticProfile profile,
                          ProjectileHandler handler) {
        if (profile == null) {
            return 0;
        }
        int launched = 0;
        for (int pellet = 0; pellet < profile.pellets(); pellet++) {
            if (fire(shooter, origin, aimDirection, profile, handler)) {
                launched++;
            }
        }
        return launched;
    }

    // ------------------------------------------------------------------
    // Simulation
    // ------------------------------------------------------------------

    private void tick() {
        // The engine owns its own monotonic tick stamp: the particle budget only
        // needs "a new run began", not the server's absolute game time.
        particles.beginTick(++tickStamp);
        if (rounds.isEmpty()) {
            // Nothing in flight: retire the task so an idle server pays nothing
            // for the engine. The next shot re-arms it.
            if (++idleTicks >= IDLE_TASK_RETIRE_TICKS) {
                BukkitTask current = task;
                task = null;
                if (current != null) {
                    current.cancel();
                }
            }
            return;
        }
        idleTicks = 0;
        // Reverse iteration with in-place swap removal: rounds retire mid-loop
        // (penetration, expiry) without ever shifting the list.
        for (int i = rounds.size() - 1; i >= 0; i--) {
            Round round = rounds.get(i);
            try {
                step(round);
            } catch (Throwable failure) {
                // One bad round must not end the tick for all the others.
                round.alive = false;
            }
            if (!round.alive) {
                int last = rounds.size() - 1;
                rounds.set(i, rounds.get(last));
                rounds.remove(last);
                if (pool.size() < 64) {
                    pool.addLast(round);
                }
            }
        }
    }

    private void step(Round round) {
        BallisticProfile profile = round.profile;
        World world = round.position.getWorld();
        if (world == null || round.travelled >= profile.range()) {
            expire(round);
            return;
        }
        if (!world.isChunkLoaded(round.position.getBlockX() >> 4, round.position.getBlockZ() >> 4)) {
            // The round flew out of the loaded, simulated area: nothing on the
            // server can be hit there, so stop paying for it.
            expire(round);
            return;
        }

        // Drag scales the whole velocity, gravity is added on top: the round
        // decelerates and arcs exactly as the profile describes.
        round.velocity.multiply(1.0 - profile.drag());
        round.velocity.setY(round.velocity.getY() - profile.gravity());
        double stepLength = round.velocity.length();
        if (stepLength < 1.0e-4) {
            expire(round);
            return;
        }
        Vector direction = round.velocity.clone().normalize();
        Location start = round.position;
        double remaining = profile.range() - round.travelled;
        double distance = Math.min(stepLength, remaining);
        Player shooter = player(round.shooterId);

        RayTraceResult blockHit = world.rayTraceBlocks(start, direction, distance,
                FluidCollisionMode.NEVER, true);
        RayTraceResult entityHit = world.rayTraceEntities(start, direction, distance,
                profile.hitboxExpansion(),
                candidate -> round.allows(candidate) && round.handler.allowTarget(candidate, shooter));

        double blockDistance = blockHit == null ? Double.MAX_VALUE
                : start.toVector().distance(blockHit.getHitPosition());
        double entityDistance = entityHit == null || entityHit.getHitEntity() == null ? Double.MAX_VALUE
                : start.toVector().distance(entityHit.getHitPosition());

        if (entityDistance <= blockDistance && entityHit != null && entityHit.getHitEntity() != null) {
            resolveEntityHit(round, entityHit, entityDistance, shooter);
            if (!round.alive) {
                return;
            }
        } else if (blockHit != null) {
            resolveBlockHit(round, blockHit, blockDistance, shooter);
            if (!round.alive) {
                return;
            }
        }

        emitTracer(round, start, distance);
        round.travelled += distance;
        round.position.setX(start.getX() + direction.getX() * distance);
        round.position.setY(start.getY() + direction.getY() * distance);
        round.position.setZ(start.getZ() + direction.getZ() * distance);
    }

    private void resolveEntityHit(Round round, RayTraceResult hit, double hitDistance, Player shooter) {
        BallisticProfile profile = round.profile;
        Entity target = hit.getHitEntity();
        if (!(target instanceof LivingEntity living)) {
            return;
        }
        totalHits++;
        round.lastHitId = target.getEntityId();
        Location at = hit.getHitPosition().toLocation(round.world());
        double impactDistance = round.travelled + hitDistance;
        double damage = profile.damageAt(impactDistance) * round.damageScale;
        boolean headshot = isHeadshot(living, hit);
        if (headshot) {
            damage *= profile.headshotMultiplier();
        }

        boolean withheld = !round.handler.onEntityHit(new ProjectileHit(at, target, hit.getHitBlock(),
                hit.getHitBlockFace(), impactDistance, damage, headshot, shooter, profile));
        emitImpactFx(at, profile);
        if (!withheld && damage > 0.0) {
            // Transactional: the shooter's own melee-suppression listener is
            // told not to cancel a hit the engine computed.
            DamageTransactions.apply(living, damage, shooter);
        }

        if (round.penetrationLeft > 0) {
            round.penetrationLeft--;
            round.damageScale *= 0.7;
        } else {
            round.alive = false;
        }
    }

    private void resolveBlockHit(Round round, RayTraceResult hit, double hitDistance, Player shooter) {
        BallisticProfile profile = round.profile;
        Block block = hit.getHitBlock();
        BlockFace face = hit.getHitBlockFace();
        Location at = hit.getHitPosition().toLocation(round.world());
        round.handler.onBlockHit(new ProjectileHit(at, null, block, face,
                round.travelled + hitDistance, 0.0, false, shooter, profile));
        emitImpact(at, profile);

        if (face != null && round.ricochetLeft > 0
                && ThreadLocalRandom.current().nextDouble() < profile.ricochetChance()) {
            Vector normal = new Vector(face.getModX(), face.getModY(), face.getModZ());
            if (normal.lengthSquared() > 0.0) {
                double dot = 2.0 * round.velocity.dot(normal);
                round.velocity = round.velocity.clone().subtract(normal.multiply(dot)).multiply(0.8);
                round.ricochetLeft--;
                round.damageScale *= 0.8;
                advanceTo(round, at, hitDistance);
                return;
            }
        }
        if (round.penetrationLeft > 0) {
            // Penetration applies to geometry too: a round that punches a wall
            // keeps flying, with its energy reduced.
            round.penetrationLeft--;
            round.damageScale *= 0.8;
            advanceTo(round, at, hitDistance);
            return;
        }
        round.alive = false;
    }

    /** Moves a round to the point where it struck and accounts for the distance. */
    private static void advanceTo(Round round, Location at, double hitDistance) {
        round.travelled += hitDistance;
        round.position.setX(at.getX());
        round.position.setY(at.getY());
        round.position.setZ(at.getZ());
    }

    private void expire(Round round) {
        if (!round.alive) {
            return;
        }
        round.alive = false;
        round.handler.onExpired(player(round.shooterId), round.position.clone(), round.profile);
    }

    /** The over-budget path: one trace over the whole remaining range. */
    private void instantTrace(Player shooter, Location origin, Vector velocity, BallisticProfile profile,
                              ProjectileHandler handler) {
        World world = origin.getWorld();
        if (world == null) {
            return;
        }
        Vector direction = velocity.clone().normalize();
        RayTraceResult blockHit = world.rayTraceBlocks(origin, direction, profile.range(),
                FluidCollisionMode.NEVER, true);
        RayTraceResult entityHit = world.rayTraceEntities(origin, direction, profile.range(),
                profile.hitboxExpansion(), candidate -> handler.allowTarget(candidate, shooter));
        double blockDistance = blockHit == null ? Double.MAX_VALUE
                : origin.toVector().distance(blockHit.getHitPosition());
        double entityDistance = entityHit == null || entityHit.getHitEntity() == null ? Double.MAX_VALUE
                : origin.toVector().distance(entityHit.getHitPosition());

        double resolved = Math.min(profile.range(), Math.min(blockDistance, entityDistance));
        if (settings.emitTracers()) {
            particles.tracer(origin, origin.clone().add(direction.clone().multiply(resolved)),
                    ParticleFx.particle(profile.tracerParticle(), Particle.CRIT), profile.tracerSpacing());
        }

        if (entityDistance <= blockDistance && entityHit != null
                && entityHit.getHitEntity() instanceof LivingEntity living) {
            totalHits++;
            boolean headshot = isHeadshot(living, entityHit);
            double damage = profile.damageAt(entityDistance) * (headshot ? profile.headshotMultiplier() : 1.0);
            Location at = entityHit.getHitPosition().toLocation(world);
            boolean withheld = !handler.onEntityHit(new ProjectileHit(at, living, entityHit.getHitBlock(),
                    entityHit.getHitBlockFace(), entityDistance, damage, headshot, shooter, profile));
            emitImpactFx(at, profile);
            if (!withheld && damage > 0.0) {
                DamageTransactions.apply(living, damage, shooter);
            }
            return;
        }
        if (blockHit != null) {
            Location at = blockHit.getHitPosition().toLocation(world);
            handler.onBlockHit(new ProjectileHit(at, null, blockHit.getHitBlock(), blockHit.getHitBlockFace(),
                    blockDistance, 0.0, false, shooter, profile));
            emitImpact(at, profile);
        }
    }

    // ------------------------------------------------------------------
    // Effects
    // ------------------------------------------------------------------

    private void emitLaunchFx(Player shooter, Location origin, Vector unit, BallisticProfile profile) {
        Location muzzle = origin.clone().add(unit.clone().multiply(0.6));
        particles.muzzle(muzzle, ParticleFx.particle(profile.muzzleParticle(), Particle.SMOKE), 8);
        Sounds.play(origin, profile.fireSound(), profile.soundVolume(),
                Sounds.variance(1.0f, 0.06f), org.bukkit.Sound.ENTITY_ARROW_SHOOT);
    }

    private void emitImpactFx(Location at, BallisticProfile profile) {
        particles.impact(at, ParticleFx.particle(profile.impactParticle(), Particle.CRIT), 6);
    }

    /**
     * A round stopped by geometry also reports audibly: a thud at the wall is
     * what tells the shooter the shot was not a miss. A profile that names no
     * impact event still gets one — silence on impact reads as a missed hit.
     */
    private void emitImpact(Location at, BallisticProfile profile) {
        emitImpactFx(at, profile);
        String event = profile.impactSound() == null || profile.impactSound().isBlank()
                ? profile.fallbackSound()
                : profile.impactSound();
        Sounds.play(at, event, profile.soundVolume() * 0.6f, 1.5f,
                org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT);
    }

    private void emitTracer(Round round, Location from, double distance) {
        if (!settings.emitTracers() || distance <= 0.0) {
            return;
        }
        if (round.travelled + distance < round.nextTracerAt) {
            return;
        }
        double spacing = Math.max(1.0, round.profile.tracerSpacing());
        while (round.travelled + distance >= round.nextTracerAt) {
            round.nextTracerAt += spacing;
        }
        Vector direction = round.velocity.clone().normalize();
        particles.tracer(from, from.clone().add(direction.multiply(distance)),
                ParticleFx.particle(round.profile.tracerParticle(), Particle.CRIT), 1.0);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Round acquire(Location position, Vector velocity, BallisticProfile profile, UUID shooterId,
                          ProjectileHandler handler) {
        Round round = pool.pollFirst();
        if (round == null) {
            round = new Round();
        }
        round.reset(position, velocity, profile, shooterId, handler);
        rounds.add(round);
        return round;
    }

    private static boolean isHeadshot(LivingEntity living, RayTraceResult hit) {
        double eyeY = living.getLocation().getY() + living.getEyeHeight();
        return hit.getHitPosition().getY() >= eyeY;
    }

    private Player player(UUID id) {
        return id == null ? null : plugin.getServer().getPlayer(id);
    }

    /**
     * Random cone perturbation applied to a <em>unit</em> direction, then
     * re-normalized. Scaling to the muzzle speed happens after this, so spread
     * can never quietly reduce a round's velocity.
     */
    private static Vector applySpread(Vector unit, double degrees) {
        if (degrees <= 0.0) {
            return unit;
        }
        double radians = Math.toRadians(degrees);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Vector jittered = unit.clone().add(new Vector(
                random.nextDouble(-radians, radians),
                random.nextDouble(-radians, radians),
                random.nextDouble(-radians, radians)));
        if (jittered.lengthSquared() < 1.0e-8) {
            return unit;
        }
        return jittered.normalize();
    }

    /** Mutable per-round state; instances are pooled and reused. */
    private static final class Round {
        Location position;
        Vector velocity;
        BallisticProfile profile;
        UUID shooterId;
        ProjectileHandler handler;
        double travelled;
        double nextTracerAt;
        double damageScale = 1.0;
        int penetrationLeft;
        int ricochetLeft;
        int lastHitId = Integer.MIN_VALUE;
        boolean alive = true;

        void reset(Location position, Vector velocity, BallisticProfile profile, UUID shooterId,
                   ProjectileHandler handler) {
            this.position = position.clone();
            this.velocity = velocity.clone();
            this.profile = profile;
            this.shooterId = shooterId;
            this.handler = handler;
            this.travelled = 0.0;
            // The first puff appears a full spacing out, not at the muzzle: a
            // burst at the barrel is the launch flash's job, not the tracer's.
            this.nextTracerAt = Math.max(1.0, profile.tracerSpacing());
            this.damageScale = 1.0;
            this.penetrationLeft = profile.penetration();
            this.ricochetLeft = profile.ricochetCount();
            this.lastHitId = Integer.MIN_VALUE;
            this.alive = true;
        }

        World world() {
            return position.getWorld();
        }

        /** A round never re-hits the same entity on a following step. */
        boolean allows(Entity candidate) {
            return candidate != null && candidate.getEntityId() != lastHitId;
        }
    }

    // stats accessors
    public int activeRounds() {
        return rounds.size();
    }

    public long totalRounds() {
        return totalRounds;
    }

    public long totalHits() {
        return totalHits;
    }

    /** Shots resolved as a single trace because the round budget was full. */
    public long degradedShots() {
        return degradedShots;
    }

    /** One-line telemetry for status commands. */
    public String telemetrySummary() {
        return "rounds=" + rounds.size() + "/" + settings.maxActiveRounds() + " fired=" + totalRounds
                + " hits=" + totalHits + " degraded=" + degradedShots
                + " particlesEmitted=" + particles.emitted() + " particlesDropped=" + particles.dropped();
    }
}
