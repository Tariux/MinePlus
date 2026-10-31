package com.mineplus.infrastructure.fx.unit;

import com.mineplus.infrastructure.fx.ParticleFx;
import org.bukkit.Particle;

/**
 * A short bright flash at the anchor — the visual "pop" that reads as a light
 * source without placing a real light block (the Core never writes blocks for
 * presentation). Emitted through the shared particle budget.
 */
public final class LightFlash implements VisualComponent {

    private static final Particle DEFAULT_FLASH = Particle.END_ROD;

    private final Particle particle;
    private final int brightness;
    private final int priority;

    public LightFlash() {
        this(DEFAULT_FLASH, 3, 0);
    }

    public LightFlash(Particle particle, int brightness, int priority) {
        this.particle = particle == null ? DEFAULT_FLASH : particle;
        this.brightness = Math.max(1, brightness);
        this.priority = priority;
    }

    @Override
    public void start(VisualUnitService service) {
        service.particles().burst(ParticleFx.Tier.CRITICAL, service.anchorOfCurrent(),
                particle, brightness, 0.18, 0.18, 0.18, 0.0);
    }

    @Override
    public int priority() {
        return priority;
    }
}
