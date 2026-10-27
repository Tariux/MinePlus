package com.mineplus.infrastructure.fx;

import java.util.EnumMap;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;

/**
 * Budgeted particle emission for transient gameplay effects (muzzle flash,
 * bullet tracers, impact puffs, potion rings).
 *
 * <p>Two independent per-tick budgets: <b>critical</b> (the feedback a player
 * needs to understand what their action did — muzzle, hit) and
 * <b>ambient</b> (decorative streaks and smoke). A burst of shots can therefore
 * never starve the confirmation puff of the shot that mattered, and the
 * decorative channel is what degrades when a server is loaded.
 *
 * <p>Calls are cheap and never throw: every emission runs through
 * {@link #spawn} which swallows any runtime rejection, so a client-visible
 * particle that an exotic server refuses can never kill the caller's tick loop.
 * {@link #beginTick(long)} opens each window, but a caller that never drives
 * one still gets its effects: the budget self-heals on a 50 ms rolling window,
 * so an undriven instance degrades to per-tick metering rather than silence.
 */
public final class ParticleFx {

    /** Emission groups with independent budgets. */
    public enum Tier {
        /** Muzzle flash and impact confirmation: never starved by decoration. */
        CRITICAL,
        /** Tracers, smoke rings, ambient plumes: the degradable channel. */
        AMBIENT
    }

    private static final int DEFAULT_CRITICAL_PER_TICK = 96;
    private static final int DEFAULT_AMBIENT_PER_TICK = 128;
    private static final int DEFAULT_MAX_PARTICLES_PER_EMIT = 32;
    /** One server tick: the window a self-healing budget reopens after. */
    private static final long WINDOW_NANOS = 50_000_000L;

    private final Map<Tier, Integer> limits = new EnumMap<>(Tier.class);
    private final Map<Tier, Integer> used = new EnumMap<>(Tier.class);
    private int maxPerEmit;

    private long tickStamp = -1L;
    private long windowOpenedAtNs = System.nanoTime();
    private long emitted;
    private long dropped;

    public ParticleFx() {
        this(DEFAULT_CRITICAL_PER_TICK, DEFAULT_AMBIENT_PER_TICK, DEFAULT_MAX_PARTICLES_PER_EMIT);
    }

    public ParticleFx(int criticalPerTick, int ambientPerTick, int maxPerEmit) {
        limits.put(Tier.CRITICAL, Math.max(0, criticalPerTick));
        limits.put(Tier.AMBIENT, Math.max(0, ambientPerTick));
        used.put(Tier.CRITICAL, 0);
        used.put(Tier.AMBIENT, 0);
        this.maxPerEmit = Math.max(1, maxPerEmit);
    }

    /** Opens a new tick window. Call once per server tick, before any emission. */
    public void beginTick(long tick) {
        if (tick == tickStamp) {
            return;
        }
        tickStamp = tick;
        openWindow();
    }

    private void openWindow() {
        used.put(Tier.CRITICAL, 0);
        used.put(Tier.AMBIENT, 0);
        windowOpenedAtNs = System.nanoTime();
    }

    /** Muzzle flash: one tight, low-cost puff at the barrel tip. */
    public void muzzle(Location origin, Particle particle, int count) {
        burst(Tier.CRITICAL, origin, particle, count, 0.05, 0.05, 0.05, 0.02);
    }

    /** Impact confirmation: a small spray where the round landed. */
    public void impact(Location origin, Particle particle, int count) {
        burst(Tier.CRITICAL, origin, particle, count, 0.08, 0.08, 0.08, 0.01);
    }

    /**
     * A dashed tracer along a resolved path: one puff every {@code spacing}
     * blocks, hard-capped so a long shot costs a bounded number of particles
     * no matter its range.
     */
    public void tracer(Location from, Location to, Particle particle, double spacing) {
        World world = from.getWorld();
        if (world == null || to.getWorld() != world) {
            return;
        }
        double distance = from.distance(to);
        if (distance < 0.5) {
            return;
        }
        double stride = Math.max(1.0, spacing);
        int puffs = (int) Math.min(16, Math.ceil(distance / stride));
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        for (int i = 1; i <= puffs; i++) {
            double t = i / (double) puffs;
            spawn(Tier.AMBIENT, world, from.getX() + dx * t, from.getY() + dy * t, from.getZ() + dz * t,
                    particle, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /** A point burst with explicit spread. */
    public void burst(Tier tier, Location origin, Particle particle, int count,
                      double spreadX, double spreadY, double spreadZ, double extra) {
        World world = origin.getWorld();
        if (world == null) {
            return;
        }
        spawn(tier, world, origin.getX(), origin.getY(), origin.getZ(), particle,
                count, spreadX, spreadY, spreadZ, extra);
    }

    /**
     * A horizontal ring of puffs (detonation markers, landing zones): the
     * point count grows with radius but stays bounded.
     */
    public void ring(Location center, Particle particle, double radius, int pointsPerBlock) {
        World world = center.getWorld();
        if (world == null || radius <= 0) {
            return;
        }
        int points = (int) Math.max(8, Math.min(48, Math.round(radius * Math.max(1, pointsPerBlock))));
        for (int i = 0; i < points; i++) {
            double angle = (2 * Math.PI * i) / points;
            spawn(Tier.AMBIENT, world, center.getX() + Math.cos(angle) * radius,
                    center.getY() + 0.2, center.getZ() + Math.sin(angle) * radius,
                    particle, 1, 0.0, 0.02, 0.0, 0.0);
        }
    }

    /** Emits {@code count} particles under {@code tier}'s budget. */
    public void spawn(Tier tier, World world, double x, double y, double z, Particle particle,
                      int count, double spreadX, double spreadY, double spreadZ, double extra) {
        if (world == null || particle == null || count <= 0) {
            return;
        }
        if (System.nanoTime() - windowOpenedAtNs >= WINDOW_NANOS) {
            openWindow();
        }
        int budget = limits.getOrDefault(tier, 0);
        int room = budget - used.getOrDefault(tier, 0);
        if (room <= 0) {
            dropped += count;
            return;
        }
        int emit = Math.min(Math.min(count, maxPerEmit), room);
        used.put(tier, used.getOrDefault(tier, 0) + emit);
        emitted += emit;
        if (emit < count) {
            dropped += count - emit;
        }
        try {
            world.spawnParticle(particle, x, y, z, emit, spreadX, spreadY, spreadZ, extra);
        } catch (Throwable rejected) {
            // A particle this server refuses is a presentation detail, not a
            // reason to unwind the caller's simulation step.
            dropped += emit;
        }
    }

    /**
     * Tolerant particle lookup: an unknown name falls back to a safe default
     * instead of throwing, so a hand-edited config never silences a weapon.
     */
    public static Particle particle(String name, Particle fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            return Particle.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }

    /** Particles actually emitted since startup. */
    public long emitted() {
        return emitted;
    }

    /** Particles withheld by the per-tick budgets since startup. */
    public long dropped() {
        return dropped;
    }

    /** Applies new budget limits (config reload); values below one disable the channel. */
    public void configure(int criticalPerTick, int ambientPerTick, int maxPerEmit) {
        limits.put(Tier.CRITICAL, Math.max(0, criticalPerTick));
        limits.put(Tier.AMBIENT, Math.max(0, ambientPerTick));
        this.maxPerEmit = Math.max(1, maxPerEmit);
    }
}
