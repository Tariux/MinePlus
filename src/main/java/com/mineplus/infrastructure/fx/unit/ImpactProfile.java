package com.mineplus.infrastructure.fx.unit;

import com.mineplus.infrastructure.combat.BallisticProfile;
import com.mineplus.infrastructure.fx.ParticleFx;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;

/**
 * The visual recipe of one impact: what the flash, the dust, the decal and the
 * sound look like. Authored once per weapon profile or built ad hoc for any
 * gameplay event that wants a composed impact.
 *
 * @param flash        bright flash particle (may be {@code null})
 * @param sparks       thrown-out sparks (may be {@code null})
 * @param dust         settling dust/smoke (may be {@code null})
 * @param sound        sound event name; blank = silent
 * @param volume       sound volume
 * @param pitch        sound pitch
 * @param decal        material of the surface mark; {@code null}/air = no decal
 * @param decalScale   decal size as a fraction of a block
 * @param lifetimeTicks how long the unit lives
 * @param priority     higher-priority units survive budget pressure
 */
public record ImpactProfile(
        Particle flash,
        Particle sparks,
        Particle dust,
        String sound,
        float volume,
        float pitch,
        Material decal,
        float decalScale,
        int lifetimeTicks,
        int priority
) {

    /** Ground-impact defaults; the same composition for any surface hit. */
    public static ImpactProfile defaults() {
        return new ImpactProfile(Particle.END_ROD, Particle.FLAME, Particle.CLOUD,
                "block.stone.hit", 0.6f, 1.5f, Material.GRAY_CONCRETE, 0.22f, 30, 10);
    }

    /** Derives the impact look from an existing weapon profile; never changes its math. */
    public static ImpactProfile from(BallisticProfile profile) {
        ImpactProfile defaults = defaults();
        if (profile == null) {
            return defaults;
        }
        String sound = profile.impactSound() == null || profile.impactSound().isBlank()
                ? profile.fallbackSound()
                : profile.impactSound();
        return new ImpactProfile(
                ParticleFx.particle(profile.sparkParticle(), Particle.FLAME),
                ParticleFx.particle(profile.impactParticle(), Particle.CRIT),
                ParticleFx.particle(profile.impactSmokeParticle(), Particle.CLOUD),
                sound,
                Math.max(0.1f, profile.soundVolume() * 0.6f),
                1.5f,
                defaults.decal(),
                defaults.decalScale(),
                defaults.lifetimeTicks(),
                defaults.priority());
    }

    /** The vanilla fallback used when {@link #sound} is a custom event the server cannot resolve. */
    public Sound fallbackSound() {
        return Sound.BLOCK_NOTE_BLOCK_HAT;
    }
}
