package com.mineplus.infrastructure.combat;

/**
 * The immutable ballistic description of one projectile type: how fast it
 * leaves the muzzle, how it decays in flight, what it does when it lands.
 *
 * <p>Units are the simulation's own, so a module never converts anything:
 * {@code velocity} is blocks per tick, {@code gravity} is blocks per tick
 * subtracted from the vertical component each tick, {@code drag} is the
 * per-tick fraction of speed lost, and every distance ({@code range},
 * {@code falloffStart}) is in blocks.
 *
 * <p>The visual fields ({@code muzzleParticle}, {@code flashParticle},
 * {@code smokeParticle}, {@code sparkParticle}, {@code tracerParticle},
 * {@code impactParticle}, {@code impactSmokeParticle}) and the audio fields
 * are names, resolved lazily and tolerantly by {@link Ballistics}: an unknown
 * particle degrades to the documented default, an unknown sound event
 * degrades to {@code fallbackSound} instead of throwing.
 */
public final class BallisticProfile {

    /** The default cone a module gets when it does not care about aim assist. */
    public static final double DEFAULT_HITBOX_EXPANSION = 0.35;

    private final double damage;
    private final double velocity;
    private final double gravity;
    private final double drag;
    private final double range;
    private final double spreadDegrees;
    private final int pellets;
    private final double falloffStart;
    private final double falloffPerBlock;
    private final double minDamageFraction;
    private final double headshotMultiplier;
    private final int penetration;
    private final double ricochetChance;
    private final int ricochetCount;
    private final double knockback;
    private final double hitboxExpansion;
    private final double tracerSpacing;
    private final String muzzleParticle;
    private final String flashParticle;
    private final String smokeParticle;
    private final String sparkParticle;
    private final String tracerParticle;
    private final String impactParticle;
    private final String impactSmokeParticle;
    private final String fireSound;
    private final String impactSound;
    private final String fallbackSound;
    private final float soundVolume;

    private BallisticProfile(Builder builder) {
        this.damage = builder.damage;
        this.velocity = builder.velocity;
        this.gravity = builder.gravity;
        this.drag = builder.drag;
        this.range = builder.range;
        this.spreadDegrees = builder.spreadDegrees;
        this.pellets = builder.pellets;
        this.falloffStart = builder.falloffStart;
        this.falloffPerBlock = builder.falloffPerBlock;
        this.minDamageFraction = builder.minDamageFraction;
        this.headshotMultiplier = builder.headshotMultiplier;
        this.penetration = builder.penetration;
        this.ricochetChance = builder.ricochetChance;
        this.ricochetCount = builder.ricochetCount;
        this.knockback = builder.knockback;
        this.hitboxExpansion = builder.hitboxExpansion;
        this.tracerSpacing = builder.tracerSpacing;
        this.muzzleParticle = builder.muzzleParticle;
        this.flashParticle = builder.flashParticle;
        this.smokeParticle = builder.smokeParticle;
        this.sparkParticle = builder.sparkParticle;
        this.tracerParticle = builder.tracerParticle;
        this.impactParticle = builder.impactParticle;
        this.impactSmokeParticle = builder.impactSmokeParticle;
        this.fireSound = builder.fireSound;
        this.impactSound = builder.impactSound;
        this.fallbackSound = builder.fallbackSound;
        this.soundVolume = builder.soundVolume;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A plain hitscan-like round: fast, flat, no drop, no ricochet. */
    public static BallisticProfile basic(double damage, double velocity, double range) {
        return builder().damage(damage).velocity(velocity).range(range).build();
    }

    public double damage() {
        return damage;
    }

    /** Blocks per tick at the muzzle. */
    public double velocity() {
        return velocity;
    }

    public double gravity() {
        return gravity;
    }

    /** Fraction of speed lost per tick (0 = vacuum). */
    public double drag() {
        return drag;
    }

    public double range() {
        return range;
    }

    /** Full cone width in degrees; 0 is a perfect shot. */
    public double spreadDegrees() {
        return spreadDegrees;
    }

    public int pellets() {
        return pellets;
    }

    public double falloffStart() {
        return falloffStart;
    }

    public double falloffPerBlock() {
        return falloffPerBlock;
    }

    public double minDamageFraction() {
        return minDamageFraction;
    }

    public double headshotMultiplier() {
        return headshotMultiplier;
    }

    /** Extra living targets a round passes through before it dies. */
    public int penetration() {
        return penetration;
    }

    public double ricochetChance() {
        return ricochetChance;
    }

    public int ricochetCount() {
        return ricochetCount;
    }

    /**
     * Horizontal push applied to a struck living target, in blocks per tick.
     * The engine adds a small vertical lift so the target is visibly moved
     * rather than slid along the floor. {@code 0} disables knockback.
     */
    public double knockback() {
        return knockback;
    }

    /** Ray sweep radius around the round's line, in blocks. */
    public double hitboxExpansion() {
        return hitboxExpansion;
    }

    public double tracerSpacing() {
        return tracerSpacing;
    }

    public String muzzleParticle() {
        return muzzleParticle;
    }

    /** The bright flash at the muzzle; defaults to a flame-coloured burst. */
    public String flashParticle() {
        return flashParticle;
    }

    /** The smoke that lingers at the muzzle; defaults to a dark cloud. */
    public String smokeParticle() {
        return smokeParticle;
    }

    /**
     * The burning sparks the shot throws off; defaults to a bright ember.
     *
     * <p>They are the fastest-moving part of the muzzle effect: the flash
     * blooms, the sparks leave the barrel, and only then does the smoke hang
     * there. Without them a shot reads as a grey cloud instead of fire.</p>
     */
    public String sparkParticle() {
        return sparkParticle;
    }

    public String tracerParticle() {
        return tracerParticle;
    }

    public String impactParticle() {
        return impactParticle;
    }

    /** The dust cloud an impact leaves behind; defaults to a pale puff. */
    public String impactSmokeParticle() {
        return impactSmokeParticle;
    }

    public String fireSound() {
        return fireSound;
    }

    public String impactSound() {
        return impactSound;
    }

    /** Vanilla sound key used when {@code fireSound}/{@code impactSound} is unresolvable. */
    public String fallbackSound() {
        return fallbackSound;
    }

    public float soundVolume() {
        return soundVolume;
    }

    /**
     * Damage actually dealt at {@code travelled} blocks of flight: full value
     * until the falloff knee, then linear decay clamped at
     * {@link #minDamageFraction()}.
     */
    public double damageAt(double travelled) {
        if (travelled <= falloffStart) {
            return damage;
        }
        double lost = (travelled - falloffStart) * falloffPerBlock;
        return damage * Math.max(minDamageFraction, 1.0 - lost);
    }

    /** Fluent construction; every field has a playable default. */
    public static final class Builder {
        private double damage = 5.0;
        private double velocity = 3.0;
        private double gravity = 0.0;
        private double drag = 0.0;
        private double range = 80.0;
        private double spreadDegrees = 0.0;
        private int pellets = 1;
        private double falloffStart = Double.MAX_VALUE;
        private double falloffPerBlock = 0.0;
        private double minDamageFraction = 0.25;
        private double headshotMultiplier = 1.0;
        private int penetration = 0;
        private double ricochetChance = 0.0;
        private int ricochetCount = 0;
        private double knockback = 0.0;
        private double hitboxExpansion = DEFAULT_HITBOX_EXPANSION;
        private double tracerSpacing = 5.0;
        private String muzzleParticle = "SMOKE";
        private String flashParticle = "FLAME";
        private String smokeParticle = "LARGE_SMOKE";
        private String sparkParticle = "SMALL_FLAME";
        private String tracerParticle = "CRIT";
        private String impactParticle = "CRIT";
        private String impactSmokeParticle = "CLOUD";
        private String fireSound;
        private String impactSound;
        private String fallbackSound = "block.note_block.basedrum";
        private float soundVolume = 1.0f;

        public Builder damage(double value) {
            this.damage = Math.max(0.0, value);
            return this;
        }

        public Builder velocity(double value) {
            this.velocity = Math.max(0.05, value);
            return this;
        }

        public Builder gravity(double value) {
            this.gravity = Math.max(0.0, value);
            return this;
        }

        public Builder drag(double value) {
            this.drag = Math.min(0.95, Math.max(0.0, value));
            return this;
        }

        public Builder range(double value) {
            this.range = Math.max(1.0, value);
            return this;
        }

        public Builder spreadDegrees(double value) {
            this.spreadDegrees = Math.max(0.0, value);
            return this;
        }

        public Builder pellets(int value) {
            this.pellets = Math.max(1, value);
            return this;
        }

        public Builder falloff(double start, double perBlock) {
            this.falloffStart = Math.max(0.0, start);
            this.falloffPerBlock = Math.max(0.0, perBlock);
            return this;
        }

        public Builder minDamageFraction(double value) {
            this.minDamageFraction = Math.min(1.0, Math.max(0.0, value));
            return this;
        }

        public Builder headshotMultiplier(double value) {
            this.headshotMultiplier = Math.max(1.0, value);
            return this;
        }

        public Builder penetration(int value) {
            this.penetration = Math.max(0, value);
            return this;
        }

        public Builder ricochet(double chance, int count) {
            this.ricochetChance = Math.min(1.0, Math.max(0.0, chance));
            this.ricochetCount = Math.max(0, count);
            return this;
        }

        /** Horizontal push per landed round, in blocks per tick. */
        public Builder knockback(double value) {
            this.knockback = Math.min(4.0, Math.max(0.0, value));
            return this;
        }

        public Builder hitboxExpansion(double value) {
            this.hitboxExpansion = Math.max(0.0, value);
            return this;
        }

        public Builder tracerSpacing(double value) {
            this.tracerSpacing = Math.max(1.0, value);
            return this;
        }

        public Builder particles(String muzzle, String tracer, String impact) {
            this.muzzleParticle = muzzle;
            this.tracerParticle = tracer;
            this.impactParticle = impact;
            return this;
        }

        /**
         * The layered effect particles of a shot: the bright flash at the
         * muzzle, the smoke that follows it, and the dust an impact leaves
         * behind. A blank name falls back to the built-in default.
         */
        public Builder effectParticles(String flash, String smoke, String impactSmoke) {
            this.flashParticle = flash;
            this.smokeParticle = smoke;
            this.impactSmokeParticle = impactSmoke;
            return this;
        }

        /**
         * The burning sparks a shot throws off; a blank name falls back to
         * the built-in default.
         */
        public Builder sparkParticle(String spark) {
            this.sparkParticle = spark;
            return this;
        }

        public Builder sounds(String fire, String impact, String fallback, float volume) {
            this.fireSound = fire;
            this.impactSound = impact;
            this.fallbackSound = fallback;
            this.soundVolume = Math.max(0.0f, volume);
            return this;
        }

        public BallisticProfile build() {
            return new BallisticProfile(this);
        }
    }
}
