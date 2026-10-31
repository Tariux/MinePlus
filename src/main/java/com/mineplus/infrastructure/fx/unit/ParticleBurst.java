package com.mineplus.infrastructure.fx.unit;

import com.mineplus.infrastructure.fx.ParticleFx;
import org.bukkit.Particle;

/**
 * A point particle burst that fires once when its unit starts. It routes
 * through the shared {@link ParticleFx} budget, so a unit can never outspend
 * the tick's particle allowance.
 */
public final class ParticleBurst implements VisualComponent {

    private final ParticleFx.Tier tier;
    private final Particle particle;
    private final int count;
    private final double spreadX;
    private final double spreadY;
    private final double spreadZ;
    private final double extra;
    private final int priority;

    public ParticleBurst(ParticleFx.Tier tier, Particle particle, int count,
                         double spreadX, double spreadY, double spreadZ, double extra) {
        this(tier, particle, count, spreadX, spreadY, spreadZ, extra, 0);
    }

    public ParticleBurst(ParticleFx.Tier tier, Particle particle, int count,
                         double spreadX, double spreadY, double spreadZ, double extra, int priority) {
        this.tier = tier == null ? ParticleFx.Tier.CRITICAL : tier;
        this.particle = particle;
        this.count = Math.max(0, count);
        this.spreadX = spreadX;
        this.spreadY = spreadY;
        this.spreadZ = spreadZ;
        this.extra = extra;
        this.priority = priority;
    }

    @Override
    public void start(VisualUnitService service) {
        if (particle == null || count == 0) {
            return;
        }
        service.particles().burst(tier, service.anchorOfCurrent(), particle,
                count, spreadX, spreadY, spreadZ, extra);
    }

    @Override
    public int priority() {
        return priority;
    }
}
