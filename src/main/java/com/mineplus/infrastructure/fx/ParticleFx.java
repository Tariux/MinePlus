package com.mineplus.infrastructure.fx;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;

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
 * <p><b>The audience is explicit.</b> A particle is sent to the players who can
 * actually see it — the ones within the configured radius of the emission —
 * not to everybody in the world. The vanilla world-wide call does not reach
 * far: the server hands a particle only to the players standing around it, so
 * a tracer that leaves the muzzle is dropped on the floor and the shooter
 * fires a gun that draws nothing. Addressing the packet per viewer makes the
 * effect reach the players it is meant for and keeps the cost proportional to
 * how many people are actually looking.
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

    /**
     * How far an effect reaches, in blocks. A muzzle flash is read by the
     * shooter and by whoever stands next to them; the far side of the map does
     * not need it, and a world-wide broadcast is the single largest client
     * cost of a shot on a busy server.
     */
    private static final double DEFAULT_RADIUS = 48.0;

    /** One server tick: the window a self-healing budget reopens after. */
    private static final long WINDOW_NANOS = 50_000_000L;

    private final Map<Tier, Integer> limits = new EnumMap<>(Tier.class);
    private final Map<Tier, Integer> used = new EnumMap<>(Tier.class);
    private int maxPerEmit;
    private double radius = DEFAULT_RADIUS;

    private long tickStamp = -1L;
    private long windowOpenedAtNs = System.nanoTime();
    private long emitted;
    private long dropped;
    private long viewers;

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
        // One audience for the whole streak: the path does not leave the circle
        // the audience was computed for.
        List<Player> audience = audience(world, from.getX(), from.getY(), from.getZ());
        for (int i = 1; i <= puffs; i++) {
            double t = i / (double) puffs;
            spawn(Tier.AMBIENT, world, from.getX() + dx * t, from.getY() + dy * t, from.getZ() + dz * t,
                    particle, 1, 0.0, 0.0, 0.0, 0.0, audience);
        }
    }

    /** A point burst with explicit spread. */
    public void burst(Tier tier, Location origin, Particle particle, int count,
                      double spreadX, double spreadY, double spreadZ, double extra) {
        World world = origin.getWorld();
        if (world == null) {
            return;
        }
        List<Player> audience = audience(world, origin.getX(), origin.getY(), origin.getZ());
        spawn(tier, world, origin.getX(), origin.getY(), origin.getZ(), particle,
                count, spreadX, spreadY, spreadZ, extra, audience);
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
        List<Player> audience = audience(world, center.getX(), center.getY(), center.getZ());
        for (int i = 0; i < points; i++) {
            double angle = (2 * Math.PI * i) / points;
            spawn(Tier.AMBIENT, world, center.getX() + Math.cos(angle) * radius,
                    center.getY() + 0.2, center.getZ() + Math.sin(angle) * radius,
                    particle, 1, 0.0, 0.02, 0.0, 0.0, audience);
        }
    }

    /** Emits {@code count} particles under {@code tier}'s budget. */
    public void spawn(Tier tier, World world, double x, double y, double z, Particle particle,
                      int count, double spreadX, double spreadY, double spreadZ, double extra) {
        spawn(tier, world, x, y, z, particle, count, spreadX, spreadY, spreadZ, extra, null);
    }

    /**
     * Emits {@code count} particles under {@code tier}'s budget to an explicit
     * audience (or to the whole world when none was computed).
     */
    private void spawn(Tier tier, World world, double x, double y, double z, Particle particle,
                       int count, double spreadX, double spreadY, double spreadZ, double extra,
                       List<Player> audience) {
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
        if (audience != null) {
            deliver(particle, x, y, z, emit, spreadX, spreadY, spreadZ, extra, audience);
            return;
        }
        try {
            world.spawnParticle(particle, x, y, z, emit, spreadX, spreadY, spreadZ, extra);
        } catch (Throwable rejected) {
            // A particle this server refuses is a presentation detail, not a
            // reason to unwind the caller's simulation step.
            dropped += emit;
        }
    }

    /** Sends one emission to every viewer in the audience, one packet each. */
    private void deliver(Particle particle, double x, double y, double z, int count,
                         double spreadX, double spreadY, double spreadZ, double extra,
                         List<Player> audience) {
        for (Player viewer : audience) {
            try {
                viewer.spawnParticle(particle, x, y, z, count, spreadX, spreadY, spreadZ, extra);
                viewers++;
            } catch (Throwable rejected) {
                // One viewer that cannot take the packet does not silence the
                // others.
            }
        }
    }

    /**
     * The players who can see an emission at this point: the ones in the same
     * world within the configured radius. An empty list is a legitimate answer
     * (nobody is looking), not a failure.
     */
    private List<Player> audience(World world, double x, double y, double z) {
        List<Player> inWorld = world.getPlayers();
        if (inWorld.isEmpty()) {
            return List.of();
        }
        double limit = radius * radius;
        List<Player> audience = null;
        for (Player viewer : inWorld) {
            Location at = viewer.getLocation();
            double dx = at.getX() - x;
            double dy = at.getY() - y;
            double dz = at.getZ() - z;
            if (dx * dx + dy * dy + dz * dz > limit) {
                continue;
            }
            if (audience == null) {
                audience = new ArrayList<>(4);
            }
            audience.add(viewer);
        }
        return audience == null ? List.of() : audience;
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

    /** Particle packets delivered to viewers since startup. */
    public long delivered() {
        return viewers;
    }

    /** Applies new budget limits (config reload); values below one disable the channel. */
    public void configure(int criticalPerTick, int ambientPerTick, int maxPerEmit) {
        configure(criticalPerTick, ambientPerTick, maxPerEmit, radius);
    }

    /**
     * Applies new budget limits and the emission radius (config reload); values
     * below one disable the channel.
     */
    public void configure(int criticalPerTick, int ambientPerTick, int maxPerEmit, double radiusBlocks) {
        limits.put(Tier.CRITICAL, Math.max(0, criticalPerTick));
        limits.put(Tier.AMBIENT, Math.max(0, ambientPerTick));
        this.maxPerEmit = Math.max(1, maxPerEmit);
        this.radius = Math.max(1.0, radiusBlocks);
    }

    /** The emission radius in blocks: the reach of one particle packet. */
    public double radius() {
        return radius;
    }
}
