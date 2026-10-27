package com.mineplus.infrastructure.combat;

import org.bukkit.configuration.file.FileConfiguration;

/**
 * Runtime tuning for the combat services ({@code BALLISTICS} and
 * {@code PARTICLE_FX} sections of settings.mp.yml). Both bound the cost of
 * instantaneous gameplay feedback — simulated rounds and the particles that
 * make them legible — because that feedback is what a server pays for per
 * shot, not per second.
 *
 * @param enabled               master switch; when false {@link Ballistics#fire}
 *                              refuses and weapons fall back to their host's
 *                              own no-op path
 * @param maxActiveRounds       rounds simulated concurrently; a shot over the cap
 *                              degrades to one instant trace (same hit, same
 *                              damage) instead of queueing
 * @param tracers               emit the dashed tracer along a round's path
 * @param intervalTicks         simulation cadence in server ticks
 * @param criticalParticlesPerTick  per-tick budget for confirmation effects
 *                              (muzzle flash, impact)
 * @param ambientParticlesPerTick   per-tick budget for decoration (tracers,
 *                              smoke, rings) — the channel that degrades first
 * @param particlesPerEmit      ceiling a single emission request may spend
 * @param particleRadius        how far a particle packet reaches, in blocks;
 *                              the vanilla world-wide broadcast is the single
 *                              largest client cost of a shot
 */
public record CombatSettings(
        boolean enabled,
        int maxActiveRounds,
        boolean tracers,
        int intervalTicks,
        int criticalParticlesPerTick,
        int ambientParticlesPerTick,
        int particlesPerEmit,
        double particleRadius
) {

    public static CombatSettings defaults() {
        return new CombatSettings(true, 128, true, 1, 96, 128, 32, 48.0);
    }

    /** The ballistics-only slice of these settings. */
    public Ballistics.Settings ballistics() {
        return new Ballistics.Settings(maxActiveRounds, tracers, Math.max(1, intervalTicks));
    }

    public static CombatSettings parse(FileConfiguration yaml, CombatSettings fallback) {
        if (yaml == null) {
            return fallback;
        }
        CombatSettings defaults = defaults();
        var ballistics = yaml.isConfigurationSection("BALLISTICS")
                ? yaml.getConfigurationSection("BALLISTICS") : null;
        var particles = yaml.isConfigurationSection("PARTICLE_FX")
                ? yaml.getConfigurationSection("PARTICLE_FX") : null;
        return new CombatSettings(
                ballistics == null
                        ? defaults.enabled()
                        : ballistics.getBoolean("ENABLED", defaults.enabled()),
                ballistics == null
                        ? defaults.maxActiveRounds()
                        : clamp(ballistics.getInt("MAX_ACTIVE_ROUNDS", defaults.maxActiveRounds()), 1, 4096),
                ballistics == null
                        ? defaults.tracers()
                        : ballistics.getBoolean("TRACERS", defaults.tracers()),
                ballistics == null
                        ? defaults.intervalTicks()
                        : clamp(ballistics.getInt("INTERVAL_TICKS", defaults.intervalTicks()), 1, 5),
                particles == null
                        ? defaults.criticalParticlesPerTick()
                        : clamp(particles.getInt("CRITICAL_PER_TICK", defaults.criticalParticlesPerTick()), 0, 4096),
                particles == null
                        ? defaults.ambientParticlesPerTick()
                        : clamp(particles.getInt("AMBIENT_PER_TICK", defaults.ambientParticlesPerTick()), 0, 4096),
                particles == null
                        ? defaults.particlesPerEmit()
                        : clamp(particles.getInt("MAX_PER_EMIT", defaults.particlesPerEmit()), 1, 256),
                particles == null
                        ? defaults.particleRadius()
                        : Math.max(1.0, particles.getDouble("RADIUS", defaults.particleRadius()))
        );
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
