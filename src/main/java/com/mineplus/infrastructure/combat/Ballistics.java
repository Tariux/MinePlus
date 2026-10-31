package com.mineplus.infrastructure.combat;

import com.mineplus.infrastructure.fx.ParticleFx;
import com.mineplus.infrastructure.fx.Sounds;
import com.mineplus.util.DebugLogger;
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
    private volatile com.mineplus.infrastructure.fx.unit.VisualUnitService visualUnits;
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
     * Binds the composed visual-unit service; block impacts are then emitted as
     * one unit (flash + dust + decal + sound). {@code null} or a disabled
     * service leaves the existing per-effect path untouched.
     */
    public void setVisualUnits(com.mineplus.infrastructure.fx.unit.VisualUnitService visualUnits) {
        this.visualUnits = visualUnits;
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
                value.particlesPerEmit(), value.particleRadius());
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
        Vector unit = aimDirection.clone().normalize();
        Vector velocity = applySpread(unit, profile.spreadDegrees()).multiply(profile.velocity());
        emitLaunchFx(shooter, origin, unit, profile);
        return launchRound(shooter, origin, unit, velocity, profile, handler);
    }

    /**
     * Launches a whole weapon shot: {@code profile.pellets()} independent rounds
     * down the same cone, each with its own spread sample.
     *
     * <p>The muzzle effect belongs to the <em>shot</em>, not to the round: one
     * flash and one report per trigger pull, however many pellets leave the
     * barrel. Emitting it per pellet made a shotgun fire six flashes and six
     * reports, and burned six times the particle budget for one pull.</p>
     */
    public int fireWeapon(Player shooter, Location origin, Vector aimDirection, BallisticProfile profile,
                          ProjectileHandler handler) {
        if (profile == null || !enabled || origin == null || origin.getWorld() == null
                || aimDirection == null || aimDirection.lengthSquared() < 1.0e-8) {
            return 0;
        }
        Vector unit = aimDirection.clone().normalize();
        emitLaunchFx(shooter, origin, unit, profile);
        int launched = 0;
        for (int pellet = 0; pellet < profile.pellets(); pellet++) {
            Vector velocity = applySpread(unit, profile.spreadDegrees()).multiply(profile.velocity());
            if (launchRound(shooter, origin, unit, velocity, profile, handler)) {
                launched++;
            }
        }
        return launched;
    }

    /** Launches one round without the muzzle effect (the shot already spent it). */
    private boolean launchRound(Player shooter, Location origin, Vector unit, Vector velocity,
                                BallisticProfile profile, ProjectileHandler handler) {
        ProjectileHandler sink = handler == null ? ProjectileHandler.STANDARD : handler;
        Location start = origin.clone();

        safeLaunch(sink, shooter, start, unit, profile);

        if (rounds.size() >= settings.maxActiveRounds()) {
            // Over budget: resolve the whole path now instead of growing the
            // simulation. Same damage, same hit, one tick earlier than a
            // stepped round would reach it.
            degradedShots++;
            totalRounds++;
            instantTrace(shooter, start, velocity, profile, sink);
            return true;
        }
        acquire(start, velocity, profile, shooter == null ? null : shooter.getUniqueId(), sink);
        totalRounds++;
        start();
        return true;
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
        double budget = Math.min(stepLength, profile.range() - round.travelled);
        Player shooter = player(round.shooterId);
        Location cursor = round.position.clone();

        // Resolve impacts in distance order over the remaining segment. A
        // penetrating round must advance only to what it struck, then trace
        // the rest of the segment; moving the full original distance after an
        // impact both overshot the hit and skipped geometry behind it.
        while (round.alive && budget > 1.0e-6) {
            Vector direction = round.velocity.clone().normalize();
            RayTraceResult blockHit = world.rayTraceBlocks(cursor, direction, budget,
                    FluidCollisionMode.NEVER, true);
            RayTraceResult entityHit = world.rayTraceEntities(cursor, direction, budget,
                    profile.hitboxExpansion(),
                    candidate -> candidate instanceof LivingEntity
                            && round.allows(candidate) && allows(round.handler, candidate, shooter));

            double blockDistance = blockHit == null ? Double.MAX_VALUE
                    : cursor.toVector().distance(blockHit.getHitPosition());
            double entityDistance = entityHit == null || entityHit.getHitEntity() == null ? Double.MAX_VALUE
                    : cursor.toVector().distance(entityHit.getHitPosition());

            if (entityDistance <= blockDistance && entityHit != null
                    && entityHit.getHitEntity() != null) {
                emitTracer(round, cursor, entityDistance);
                round.travelled += entityDistance;
                cursor = entityHit.getHitPosition().toLocation(round.world());
                round.position.setX(cursor.getX());
                round.position.setY(cursor.getY());
                round.position.setZ(cursor.getZ());
                resolveEntityHit(round, entityHit, shooter);
                if (!round.alive) {
                    return;
                }
                budget -= entityDistance;
                cursor.add(direction.multiply(1.0e-4));
            } else if (blockHit != null) {
                emitTracer(round, cursor, blockDistance);
                round.travelled += blockDistance;
                cursor = blockHit.getHitPosition().toLocation(round.world());
                round.position.setX(cursor.getX());
                round.position.setY(cursor.getY());
                round.position.setZ(cursor.getZ());
                if (!resolveBlockHit(round, blockHit, shooter)) {
                    return;
                }
                // A penetrating round must leave the struck block, or the next
                // ray inside it hits the same face at distance zero forever.
                Block pierced = blockHit.getHitBlock();
                double through = pierced == null ? 0.0
                        : exitDistance(pierced, cursor, direction);
                if (through >= budget - blockDistance) {
                    round.travelled += Math.max(0.0, budget - blockDistance);
                    cursor.add(direction.multiply(Math.max(0.0, budget - blockDistance)));
                    round.position.setX(cursor.getX());
                    round.position.setY(cursor.getY());
                    round.position.setZ(cursor.getZ());
                    return;
                }
                round.travelled += through + 1.0e-4;
                budget -= blockDistance + through + 1.0e-4;
                cursor.add(direction.multiply(through + 1.0e-4));
                round.position.setX(cursor.getX());
                round.position.setY(cursor.getY());
                round.position.setZ(cursor.getZ());
            } else {
                emitTracer(round, cursor, budget);
                round.travelled += budget;
                round.position.setX(cursor.getX() + direction.getX() * budget);
                round.position.setY(cursor.getY() + direction.getY() * budget);
                round.position.setZ(cursor.getZ() + direction.getZ() * budget);
                return;
            }
        }
    }

    private void resolveEntityHit(Round round, RayTraceResult hit, Player shooter) {
        BallisticProfile profile = round.profile;
        Entity target = hit.getHitEntity();
        if (!(target instanceof LivingEntity living)) {
            return;
        }
        totalHits++;
        round.lastHitId = target.getEntityId();
        Location at = hit.getHitPosition().toLocation(round.world());
        double impactDistance = round.travelled;
        double damage = profile.damageAt(impactDistance) * round.damageScale;
        boolean headshot = isHeadshot(living, hit);
        if (headshot) {
            damage *= profile.headshotMultiplier();
        }
        Vector direction = round.velocity.lengthSquared() > 1.0e-8
                ? round.velocity.clone().normalize() : new Vector(0, 0, 1);

        boolean withheld = !safeEntityHit(round.handler, new ProjectileHit(at, target, hit.getHitBlock(),
                hit.getHitBlockFace(), impactDistance, damage, headshot, shooter, profile, direction));
        emitImpactFx(at, profile);
        if (!withheld && damage > 0.0) {
            // Transactional: the shooter's own melee-suppression listener is
            // told not to cancel a hit the engine computed.
            DamageTransactions.apply(living, damage, shooter);
        }
        if (!withheld) {
            applyKnockback(living, profile, direction);
        }

        if (round.penetrationLeft > 0) {
            round.penetrationLeft--;
            round.damageScale *= 0.7;
        } else {
            round.alive = false;
        }
    }

    /**
     * Pushes a struck target along the round's travel vector. The lift is what
     * separates a visible impact from a target that only slides along the
     * ground; it is proportional to the configured push so a heavy round also
     * lifts more.
     */
    private static void applyKnockback(LivingEntity target, BallisticProfile profile, Vector direction) {
        double strength = profile.knockback();
        if (strength <= 0.0 || target.isDead() || !target.isValid()) {
            return;
        }
        Vector push = direction.clone().setY(0.0);
        if (push.lengthSquared() < 1.0e-8) {
            return;
        }
        push.normalize().multiply(strength);
        push.setY(Math.max(0.18, strength * 0.35));
        target.setVelocity(target.getVelocity().add(push));
    }

    /** @return {@code true} when the round keeps flying in this same step. */
    private boolean resolveBlockHit(Round round, RayTraceResult hit, Player shooter) {
        BallisticProfile profile = round.profile;
        Block block = hit.getHitBlock();
        BlockFace face = hit.getHitBlockFace();
        Location at = hit.getHitPosition().toLocation(round.world());
        Vector direction = round.velocity.lengthSquared() > 1.0e-8
                ? round.velocity.clone().normalize() : new Vector(0, 0, 1);
        safeBlockHit(round.handler, new ProjectileHit(at, null, block, face,
                round.travelled, 0.0, false, shooter, profile, direction));
        emitImpact(at, profile);
        if (face != null && round.ricochetLeft > 0
                && ThreadLocalRandom.current().nextDouble() < profile.ricochetChance()) {
            Vector normal = new Vector(face.getModX(), face.getModY(), face.getModZ());
            if (normal.lengthSquared() > 0.0) {
                double dot = 2.0 * round.velocity.dot(normal);
                round.velocity = round.velocity.clone().subtract(normal.multiply(dot)).multiply(0.8);
                round.ricochetLeft--;
                round.damageScale *= 0.8;
                // The reflected direction belongs to the next step: finish the
                // current segment at the impact instead of spending the original
                // direction's remaining distance after the bounce.
                return false;
            }
        }
        if (round.penetrationLeft > 0) {
            round.penetrationLeft--;
            round.damageScale *= 0.8;
            return true;
        }
        round.alive = false;
        return false;
    }

    private void expire(Round round) {
        if (!round.alive) {
            return;
        }
        round.alive = false;
        safeExpired(round.handler, player(round.shooterId), round.position.clone(), round.profile);
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
                profile.hitboxExpansion(),
                candidate -> candidate instanceof LivingEntity && allows(handler, candidate, shooter));
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
            boolean withheld = !safeEntityHit(handler, new ProjectileHit(at, living, entityHit.getHitBlock(),
                    entityHit.getHitBlockFace(), entityDistance, damage, headshot, shooter, profile, direction));
            emitImpactFx(at, profile);
            if (!withheld && damage > 0.0) {
                DamageTransactions.apply(living, damage, shooter);
            }
            if (!withheld) {
                applyKnockback(living, profile, direction);
            }
            return;
        }
        if (blockHit != null) {
            Location at = blockHit.getHitPosition().toLocation(world);
            safeBlockHit(handler, new ProjectileHit(at, null, blockHit.getHitBlock(), blockHit.getHitBlockFace(),
                    blockDistance, 0.0, false, shooter, profile, direction));
            emitImpact(at, profile);
        }
    }

    // ------------------------------------------------------------------
    // Module callback isolation
    // ------------------------------------------------------------------

    /**
     * A module's handler is the enrichment of a shot, never its condition: a
     * handler that throws must not delete the round, hide the impact or
     * withhold the damage the engine computed. Every callback therefore runs
     * behind a guard, and a broken one degrades to "no extra behaviour".
     */

    private void safeLaunch(ProjectileHandler handler, Player shooter, Location origin, Vector direction,
                             BallisticProfile profile) {
        try {
            handler.onLaunch(shooter, origin, direction, profile);
        } catch (Throwable failure) {
            DebugLogger.warning("[combat] launch handler failed: " + failure);
        }
    }

    /** @return the handler's verdict; a broken handler does not withhold damage */
    private boolean safeEntityHit(ProjectileHandler handler, ProjectileHit hit) {
        try {
            return handler.onEntityHit(hit);
        } catch (Throwable failure) {
            DebugLogger.warning("[combat] entity-hit handler failed: " + failure);
            return true;
        }
    }

    private void safeBlockHit(ProjectileHandler handler, ProjectileHit hit) {
        try {
            handler.onBlockHit(hit);
        } catch (Throwable failure) {
            DebugLogger.warning("[combat] block-hit handler failed: " + failure);
        }
    }

    private void safeExpired(ProjectileHandler handler, Player shooter, Location at, BallisticProfile profile) {
        try {
            handler.onExpired(shooter, at, profile);
        } catch (Throwable failure) {
            DebugLogger.warning("[combat] expiry handler failed: " + failure);
        }
    }

    /**
     * The targeting filter, guarded: a filter that throws must not abort the
     * whole ray trace (which would silently delete the shot). A broken filter
     * admits the candidate — the engine's own hit rules still decide what a
     * round may damage.
     */
    private boolean allows(ProjectileHandler handler, Entity candidate, Player shooter) {
        try {
            return handler.allowTarget(candidate, shooter);
        } catch (Throwable failure) {
            DebugLogger.warning("[combat] target filter failed: " + failure);
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Effects
    // ------------------------------------------------------------------

    /**
     * The shot's muzzle effect: a bright flash, the burning powder it throws
     * off, the smoke behind it, and the report.
     *
     * <p>The flash sits at the barrel — forward of the eye and below it, where
     * the weapon actually is. A burst at the camera is invisible to the
     * shooter and reads as a puff hanging in the crosshair, and that is what a
     * shot looked like before this was placed.</p>
     *
     * <p>The three layers are ordered by how long they live: the flash blooms
     * in place, the sparks are thrown out of the barrel at speed, and the
     * smoke lingers. A single grey cloud reads as a puff; three layers read
     * as gunpowder.</p>
     */
    private void emitLaunchFx(Player shooter, Location origin, Vector unit, BallisticProfile profile) {
        Location muzzle = origin.clone().add(unit.clone().multiply(0.8)).add(0.0, -0.35, 0.0);
        Particle flash = ParticleFx.particle(profile.flashParticle(), Particle.FLAME);
        Particle smoke = ParticleFx.particle(profile.smokeParticle(), Particle.LARGE_SMOKE);
        Particle sparks = ParticleFx.particle(profile.sparkParticle(), Particle.FLAME);
        ParticleFx.Tier tier = ParticleFx.Tier.CRITICAL;
        particles.burst(tier, muzzle, flash, 10, 0.10, 0.10, 0.10, 0.02);
        particles.burst(tier, muzzle, sparks, 7, 0.06, 0.06, 0.06, 0.16);
        particles.burst(tier, muzzle, smoke, 6, 0.18, 0.18, 0.18, 0.01);
        particles.burst(tier, muzzle, ParticleFx.particle(profile.muzzleParticle(), Particle.SMOKE),
                5, 0.22, 0.22, 0.22, 0.0);
        Sounds.play(origin, profile.fireSound(), profile.soundVolume(),
                Sounds.variance(1.0f, 0.06f), org.bukkit.Sound.ENTITY_ARROW_SHOOT);
    }

    /** The impact: a burst of sparks plus the dust the strike throws up. */
    private void emitImpactFx(Location at, BallisticProfile profile) {
        ParticleFx.Tier tier = ParticleFx.Tier.CRITICAL;
        particles.burst(tier, at, ParticleFx.particle(profile.impactParticle(), Particle.CRIT),
                8, 0.14, 0.14, 0.14, 0.01);
        particles.burst(tier, at, ParticleFx.particle(profile.sparkParticle(), Particle.FLAME),
                5, 0.05, 0.05, 0.05, 0.09);
        particles.burst(tier, at, ParticleFx.particle(profile.impactSmokeParticle(), Particle.CLOUD),
                5, 0.20, 0.20, 0.20, 0.0);
    }

    /**
     * A round stopped by geometry also reports audibly: a thud at the wall is
     * what tells the shooter the shot was not a miss. A profile that names no
     * impact event still gets one — silence on impact reads as a missed hit.
     */
    private void emitImpact(Location at, BallisticProfile profile) {
        com.mineplus.infrastructure.fx.unit.VisualUnitService units = visualUnits;
        if (units != null && units.enabled()
                && units.impact(at, com.mineplus.infrastructure.fx.unit.ImpactProfile.from(profile))) {
            // One composed unit owns the flash, dust, decal and sound; the
            // per-effect path below is the fallback when it is disabled or the
            // budget refused the unit.
            return;
        }
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

    /** Remaining distance from an impact point to the far side of a block. */
    private static double exitDistance(Block block, Location at, Vector direction) {
        org.bukkit.util.BoundingBox box = block.getBoundingBox();
        return Math.min(
                axisExit(at.getX(), direction.getX(), box.getMinX(), box.getMaxX()),
                Math.min(
                        axisExit(at.getY(), direction.getY(), box.getMinY(), box.getMaxY()),
                        axisExit(at.getZ(), direction.getZ(), box.getMinZ(), box.getMaxZ())));
    }

    private static double axisExit(double start, double direction, double min, double max) {
        if (direction > 1.0e-8) {
            return Math.max(0.0, (max - start) / direction);
        }
        if (direction < -1.0e-8) {
            return Math.max(0.0, (min - start) / direction);
        }
        return Double.MAX_VALUE;
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
