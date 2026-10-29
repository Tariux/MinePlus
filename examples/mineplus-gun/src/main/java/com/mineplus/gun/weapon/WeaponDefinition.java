package com.mineplus.gun.weapon;

import com.mineplus.gun.runtime.FireMode;
import com.mineplus.infrastructure.combat.BallisticProfile;
import com.mineplus.infrastructure.content.ContentItem;
import com.mineplus.infrastructure.content.ContentModel;
import com.mineplus.pack.item.ItemFrames;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;

/**
 * The combat description of one weapon: the shared {@link ContentItem}
 * (identity, display, carrier, presentation and shop price) plus everything
 * only the gun runtime cares about.
 *
 * <p>Everything presentation-shaped is discovered from the weapon's own model
 * folder and expressed as Core {@link ItemFrames}; this class only reads that
 * spec to know which <em>optional</em> clips exist. A weapon with no loaded
 * model, no reload frames, no fire frame or no action-cycle frames is fully
 * valid — the runtime checks each clip before playing it, so missing frames
 * degrade to "the weapon keeps its resting pose" instead of breaking the
 * weapon.</p>
 *
 * <p>Ballistics are compiled once at parse time into two immutable profiles
 * (hip and scoped). Movement and recoil penalties are applied by the runtime as
 * an extra cone around the shot direction; the resulting profiles are cached
 * per quarter-degree, so a sustained spray does not allocate a profile per
 * round.</p>
 */
public final class WeaponDefinition {

    private final ContentItem base;
    private final WeaponType type;
    private final FireMode fireMode;

    private final String ammoType;
    private final int magazine;
    private final int reloadTicks;
    private final int cycleTicks;
    private final double damage;
    private final double headshotMultiplier;

    private final double velocity;
    private final double gravity;
    private final double drag;
    private final int penetration;
    private final double ricochet;
    private final double range;
    private final double spreadDegrees;
    private final double falloffStart;
    private final double falloffPerBlock;
    private final double minDamageFraction;
    private final double knockback;
    private final double hitboxExpansion;
    private final double tracerSpacing;
    private final int pellets;

    private final int rpm;
    private final int burstCount;

    private final double moveSpread;
    private final double airSpread;
    private final double sprintSpread;
    private final double recoilPerShot;
    private final double recoilRecovery;
    private final double recoilMax;

    private final boolean scope;
    private final double scopeSpreadMultiplier;
    private final double scopeMovePenaltyMultiplier;
    private final double scopeRecoilMultiplier;

    /** Optional gameplay clips; a blank value disables that pose entirely. */
    private final String reloadClip;
    private final String cycleClip;
    private final String fireClip;
    private final String scopeInClip;
    private final String scopeHoldClip;
    private final String scopeOutClip;
    private final String equipClip;

    private final String muzzleParticle;
    private final String flashParticle;
    private final String smokeParticle;
    private final String tracerParticle;
    private final String impactParticle;
    private final String impactSmokeParticle;
    private final String sparkParticle;

    private final String soundFire;
    private final String soundImpact;
    private final String soundReloadBegin;
    private final String soundReloadMid;
    private final String soundReloadDone;
    private final String soundEmpty;
    private final float soundVolume;

    private final BallisticProfile ballistics;
    private final BallisticProfile scopedBallistics;
    private final Map<Long, BallisticProfile> dynamicProfiles = new ConcurrentHashMap<>();

    private WeaponDefinition(Builder b) {
        this.base = b.base;
        this.type = b.type;
        this.fireMode = b.fireMode;
        this.ammoType = b.ammoType;
        this.magazine = b.magazine;
        this.reloadTicks = b.reloadTicks;
        this.cycleTicks = b.cycleTicks;
        this.damage = b.damage;
        this.headshotMultiplier = b.headshotMultiplier;
        this.velocity = b.velocity;
        this.gravity = b.gravity;
        this.drag = b.drag;
        this.penetration = b.penetration;
        this.ricochet = b.ricochet;
        this.range = b.range;
        this.spreadDegrees = b.spreadDegrees;
        this.falloffStart = b.falloffStart;
        this.falloffPerBlock = b.falloffPerBlock;
        this.minDamageFraction = b.minDamageFraction;
        this.knockback = b.knockback;
        this.hitboxExpansion = b.hitboxExpansion;
        this.tracerSpacing = b.tracerSpacing;
        this.pellets = b.pellets;
        this.rpm = b.rpm;
        this.burstCount = b.burstCount;
        this.moveSpread = b.moveSpread;
        this.airSpread = b.airSpread;
        this.sprintSpread = b.sprintSpread;
        this.recoilPerShot = b.recoilPerShot;
        this.recoilRecovery = b.recoilRecovery;
        this.recoilMax = b.recoilMax;
        this.scope = b.scope;
        this.scopeSpreadMultiplier = b.scopeSpreadMultiplier;
        this.scopeMovePenaltyMultiplier = b.scopeMovePenaltyMultiplier;
        this.scopeRecoilMultiplier = b.scopeRecoilMultiplier;
        this.reloadClip = normalizeClip(b.reloadClip);
        this.cycleClip = normalizeClip(b.cycleClip);
        this.fireClip = normalizeClip(b.fireClip);
        this.scopeInClip = normalizeClip(b.scopeInClip);
        this.scopeHoldClip = normalizeClip(b.scopeHoldClip);
        this.scopeOutClip = normalizeClip(b.scopeOutClip);
        this.equipClip = normalizeClip(b.equipClip);
        this.muzzleParticle = b.muzzleParticle;
        this.flashParticle = b.flashParticle;
        this.smokeParticle = b.smokeParticle;
        this.tracerParticle = b.tracerParticle;
        this.impactParticle = b.impactParticle;
        this.impactSmokeParticle = b.impactSmokeParticle;
        this.sparkParticle = b.sparkParticle;
        this.soundFire = b.soundFire;
        this.soundImpact = b.soundImpact;
        this.soundReloadBegin = b.soundReloadBegin;
        this.soundReloadMid = b.soundReloadMid;
        this.soundReloadDone = b.soundReloadDone;
        this.soundEmpty = b.soundEmpty;
        this.soundVolume = b.soundVolume;
        this.ballistics = buildProfile(spreadDegrees);
        this.scopedBallistics = buildProfile(spreadDegrees * scopeSpreadMultiplier);
    }

    private BallisticProfile buildProfile(double spread) {
        return BallisticProfile.builder()
                .damage(damage)
                .velocity(velocity)
                .gravity(gravity)
                .drag(drag)
                .range(range)
                .spreadDegrees(spread)
                .pellets(pellets)
                .falloff(falloffStart, falloffPerBlock)
                .minDamageFraction(minDamageFraction)
                .headshotMultiplier(headshotMultiplier)
                .penetration(penetration)
                .ricochet(ricochet, ricochet > 0.0 ? 1 : 0)
                .knockback(knockback)
                .hitboxExpansion(hitboxExpansion)
                .tracerSpacing(tracerSpacing)
                .particles(muzzleParticle, tracerParticle, impactParticle)
                .effectParticles(flashParticle, smokeParticle, impactSmokeParticle)
                .sparkParticle(sparkParticle)
                .sounds(soundFire, soundImpact, "block.stone.hit", soundVolume)
                .build();
    }

    public static Builder builder(ContentItem base) {
        return new Builder(base);
    }

    /** The immutable Core profile of one round at the weapon's own spread. */
    public BallisticProfile ballistics() {
        return ballistics;
    }

    /** The same profile with the scope's tighter cone. */
    public BallisticProfile scopedBallistics() {
        return scopedBallistics;
    }

    /**
     * The profile of one shot: the weapon's own cone, tightened by the scope
     * and widened by the current movement/recoil penalty.
     *
     * <p>The penalty is quantised to a quarter degree and cached, so a spray
     * reuses one profile per accuracy step instead of allocating one per round.
     * {@code extraSpread} is clamped at 40 degrees — beyond that the shot is a
     * warning, not a weapon.</p>
     */
    public BallisticProfile profile(boolean scoped, double extraSpread) {
        if (extraSpread <= 0.05) {
            return scoped ? scopedBallistics : ballistics;
        }
        int step = Math.min(160, Math.max(1, (int) Math.round(extraSpread * 4.0)));
        long key = (scoped ? 1L << 32 : 0L) | step;
        return dynamicProfiles.computeIfAbsent(key, ignored -> {
            double base = scoped ? spreadDegrees * scopeSpreadMultiplier : spreadDegrees;
            return buildProfile(base + step / 4.0);
        });
    }

    // ------------------------------------------------------------------
    // Content identity
    // ------------------------------------------------------------------

    public ContentItem base() { return base; }
    public String id() { return base.id(); }
    /**
     * The translated display name. Content YAML authors {@code &}-codes; the
     * pack layer translates them for item meta, so everything this module
     * prints (chat, HUD flashes) reads the same literal name.
     */
    public String displayName() {
        return org.bukkit.ChatColor.translateAlternateColorCodes('&', base.displayName());
    }
    public Material backingMaterial() { return base.carrier(); }
    public double price() { return base.price(); }
    public boolean hidden() { return base.hidden(); }
    public com.mineplus.infrastructure.definition.ItemCategory category() { return base.category(); }

    /** The weapon's presentation, or {@code null} when it has no model at all. */
    public ContentModel model() { return base.model(); }

    /** True when the weapon renders through Core frame states. */
    public boolean hasFrames() {
        return base.model() != null && base.model().framed();
    }

    /** The frame spec of a framed weapon, or {@code null}. */
    public ItemFrames frames() {
        return base.model() == null ? null : base.model().frames();
    }

    /** The {@code custom_model_data} stack value that renders one frame state. */
    public int frameStackValue(int state) {
        return com.mineplus.pack.asset.ItemModelAsset.frameStackValue(base.namespace(), base.id(), state);
    }

    // ------------------------------------------------------------------
    // Behaviour
    // ------------------------------------------------------------------

    public WeaponType type() { return type; }
    public FireMode fireMode() { return fireMode; }

    public String ammoType() { return ammoType; }
    public int magazine() { return magazine; }
    public int reloadTicks() { return reloadTicks; }
    public int cycleTicks() { return cycleTicks; }
    public double damage() { return damage; }
    public double headshotMultiplier() { return headshotMultiplier; }

    public double velocity() { return velocity; }
    public double gravity() { return gravity; }
    public double drag() { return drag; }
    public int penetration() { return penetration; }
    public double ricochet() { return ricochet; }
    public double range() { return range; }
    public double spreadDegrees() { return spreadDegrees; }
    public double falloffStart() { return falloffStart; }
    public double falloffPerBlock() { return falloffPerBlock; }
    public double minDamageFraction() { return minDamageFraction; }
    public double knockback() { return knockback; }
    public double hitboxExpansion() { return hitboxExpansion; }
    public double tracerSpacing() { return tracerSpacing; }
    public int pellets() { return pellets; }

    public int rpm() { return rpm; }
    public int burstCount() { return burstCount; }

    /** Extra cone (degrees) while moving on the ground. */
    public double moveSpread() { return moveSpread; }

    /** Extra cone (degrees) while airborne. */
    public double airSpread() { return airSpread; }

    /** Extra cone (degrees) while sprinting. */
    public double sprintSpread() { return sprintSpread; }

    /** Recoil cone (degrees) added per shot, recovered over time. */
    public double recoilPerShot() { return recoilPerShot; }
    public double recoilRecovery() { return recoilRecovery; }
    public double recoilMax() { return recoilMax; }

    /** Extra cone a scope adds to every movement penalty. */
    public double scopeMovePenaltyMultiplier() { return scopeMovePenaltyMultiplier; }

    /** Recoil multiplier while scoped. */
    public double scopeRecoilMultiplier() { return scopeRecoilMultiplier; }

    /** True when the weapon can scope (left click toggles it). */
    public boolean scope() { return scope; }

    /** Spread multiplier applied while scoped; {@code 1.0} means no benefit. */
    public double scopeSpreadMultiplier() { return scopeSpreadMultiplier; }

    /**
     * The total movement-based cone penalty for one shot, in degrees.
     * Movement inaccuracy is what makes a scoped rifle unusable while
     * sprinting and a pistol usable while strafing.
     */
    public double movementPenalty(boolean scoped, boolean sprinting, boolean airborne, boolean moving) {
        double penalty;
        if (sprinting) {
            penalty = sprintSpread;
        } else if (airborne) {
            penalty = airSpread;
        } else if (moving) {
            penalty = moveSpread;
        } else {
            return 0.0;
        }
        return scoped ? penalty * scopeMovePenaltyMultiplier : penalty;
    }

    public String reloadClip() { return reloadClip; }
    public String cycleClip() { return cycleClip; }
    public String fireClip() { return fireClip; }
    public String scopeInClip() { return scopeInClip; }
    public String scopeHoldClip() { return scopeHoldClip; }
    public String scopeOutClip() { return scopeOutClip; }
    public String equipClip() { return equipClip; }

    public String muzzleParticle() { return muzzleParticle; }
    public String flashParticle() { return flashParticle; }
    public String smokeParticle() { return smokeParticle; }
    public String tracerParticle() { return tracerParticle; }
    public String impactParticle() { return impactParticle; }
    public String impactSmokeParticle() { return impactSmokeParticle; }
    public String sparkParticle() { return sparkParticle; }

    public String soundFire() { return soundFire; }
    public String soundImpact() { return soundImpact; }
    public String soundReloadBegin() { return soundReloadBegin; }
    public String soundReloadMid() { return soundReloadMid; }
    public String soundReloadDone() { return soundReloadDone; }
    public String soundEmpty() { return soundEmpty; }
    public float soundVolume() { return soundVolume; }

    /** True when firing consumes or checks ammunition. */
    public boolean usesAmmo() {
        return magazine > 0;
    }

    /** Ticks between shots from {@code rpm}, never below one tick. */
    public int shotIntervalTicks() {
        return Math.max(1, (int) Math.ceil(1200.0 / Math.max(1, rpm)));
    }

    private static String normalizeClip(String name) {
        return name == null || name.isBlank() ? null : name.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public static final class Builder {
        private final ContentItem base;
        private WeaponType type = WeaponType.PISTOL;
        private FireMode fireMode = FireMode.SEMI;

        private String ammoType = "";
        private int magazine = 0;
        private int reloadTicks = 30;
        private int cycleTicks = 0;
        private double damage = 5.0;
        private double headshotMultiplier = 1.5;

        private double velocity = 4.0;
        private double gravity = 0.02;
        private double drag = 0.01;
        private int penetration = 0;
        private double ricochet = 0.0;
        private double range = 100.0;
        private double spreadDegrees = 1.0;
        private double falloffStart = 40.0;
        private double falloffPerBlock = 0.02;
        private double minDamageFraction = 0.3;
        private double knockback = 0.25;
        private double hitboxExpansion = BallisticProfile.DEFAULT_HITBOX_EXPANSION;
        private double tracerSpacing = 4.0;
        private int pellets = 1;

        private int rpm = 300;
        private int burstCount = 3;

        private double moveSpread = 0.8;
        private double airSpread = 3.0;
        private double sprintSpread = 4.0;
        private double recoilPerShot = 0.0;
        private double recoilRecovery = 0.15;
        private double recoilMax = 2.5;

        private boolean scope = false;
        private double scopeSpreadMultiplier = 0.3;
        private double scopeMovePenaltyMultiplier = 1.5;
        private double scopeRecoilMultiplier = 0.7;

        private String reloadClip = "reload";
        private String cycleClip = "cycle";
        private String fireClip = "fire";
        private String scopeInClip = "scope_in";
        private String scopeHoldClip = "scope_hold";
        private String scopeOutClip = "scope_out";
        private String equipClip = "equip";

        private String muzzleParticle = "SMOKE";
        private String flashParticle = "FLAME";
        private String smokeParticle = "LARGE_SMOKE";
        private String tracerParticle = "CRIT";
        private String impactParticle = "CRIT";
        private String impactSmokeParticle = "CLOUD";
        private String sparkParticle = "SMALL_FLAME";

        private String soundFire = "mineplusgun:shot";
        private String soundImpact = "block.stone.hit";
        private String soundReloadBegin = "mineplusgun:reload_begin";
        private String soundReloadMid = "mineplusgun:reload_mid";
        private String soundReloadDone = "mineplusgun:reload_done";
        private String soundEmpty = "mineplusgun:empty";
        private float soundVolume = 1.0f;

        private Builder(ContentItem base) {
            this.base = Objects.requireNonNull(base, "base");
        }

        public Builder type(WeaponType v) { this.type = v; return this; }
        public Builder fireMode(FireMode v) { this.fireMode = v; return this; }
        public Builder ammoType(String v) { this.ammoType = v == null ? "" : v; return this; }
        public Builder magazine(int v) { this.magazine = Math.max(0, v); return this; }
        public Builder reloadTicks(int v) { this.reloadTicks = Math.max(0, v); return this; }
        public Builder cycleTicks(int v) { this.cycleTicks = Math.max(0, v); return this; }
        public Builder damage(double v) { this.damage = Math.max(0.0, v); return this; }
        public Builder headshotMultiplier(double v) { this.headshotMultiplier = Math.max(1.0, v); return this; }

        public Builder velocity(double v) { this.velocity = Math.max(0.05, v); return this; }
        public Builder gravity(double v) { this.gravity = Math.max(0.0, v); return this; }
        public Builder drag(double v) { this.drag = Math.min(0.95, Math.max(0.0, v)); return this; }
        public Builder penetration(int v) { this.penetration = Math.max(0, v); return this; }
        public Builder ricochet(double v) { this.ricochet = Math.min(1.0, Math.max(0.0, v)); return this; }
        public Builder range(double v) { this.range = Math.max(1.0, v); return this; }
        public Builder spreadDegrees(double v) { this.spreadDegrees = Math.max(0.0, v); return this; }
        public Builder falloffStart(double v) { this.falloffStart = Math.max(0.0, v); return this; }
        public Builder falloffPerBlock(double v) { this.falloffPerBlock = Math.max(0.0, v); return this; }
        public Builder minDamageFraction(double v) { this.minDamageFraction = Math.min(1.0, Math.max(0.0, v)); return this; }
        public Builder knockback(double v) { this.knockback = Math.min(4.0, Math.max(0.0, v)); return this; }
        public Builder hitboxExpansion(double v) { this.hitboxExpansion = Math.max(0.0, v); return this; }
        public Builder tracerSpacing(double v) { this.tracerSpacing = Math.max(1.0, v); return this; }

        public Builder rpm(int v) { this.rpm = Math.max(30, v); return this; }
        public Builder burstCount(int v) { this.burstCount = Math.max(1, v); return this; }
        public Builder pellets(int v) { this.pellets = Math.max(1, v); return this; }

        public Builder moveSpread(double v) { this.moveSpread = Math.max(0.0, v); return this; }
        public Builder airSpread(double v) { this.airSpread = Math.max(0.0, v); return this; }
        public Builder sprintSpread(double v) { this.sprintSpread = Math.max(0.0, v); return this; }
        public Builder recoilPerShot(double v) { this.recoilPerShot = Math.max(0.0, v); return this; }
        public Builder recoilRecovery(double v) { this.recoilRecovery = Math.max(0.0, v); return this; }
        public Builder recoilMax(double v) { this.recoilMax = Math.max(0.0, v); return this; }

        public Builder scope(boolean v) { this.scope = v; return this; }
        public Builder scopeSpreadMultiplier(double v) { this.scopeSpreadMultiplier = Math.min(2.0, Math.max(0.0, v)); return this; }
        public Builder scopeMovePenaltyMultiplier(double v) { this.scopeMovePenaltyMultiplier = Math.max(0.0, v); return this; }
        public Builder scopeRecoilMultiplier(double v) { this.scopeRecoilMultiplier = Math.max(0.0, v); return this; }

        public Builder reloadClip(String v) { this.reloadClip = v; return this; }
        public Builder cycleClip(String v) { this.cycleClip = v; return this; }
        public Builder fireClip(String v) { this.fireClip = v; return this; }
        public Builder scopeInClip(String v) { this.scopeInClip = v; return this; }
        public Builder scopeHoldClip(String v) { this.scopeHoldClip = v; return this; }
        public Builder scopeOutClip(String v) { this.scopeOutClip = v; return this; }
        public Builder equipClip(String v) { this.equipClip = v; return this; }

        public Builder muzzleParticle(String v) { this.muzzleParticle = v; return this; }
        public Builder flashParticle(String v) { this.flashParticle = v; return this; }
        public Builder smokeParticle(String v) { this.smokeParticle = v; return this; }
        public Builder tracerParticle(String v) { this.tracerParticle = v; return this; }
        public Builder impactParticle(String v) { this.impactParticle = v; return this; }
        public Builder impactSmokeParticle(String v) { this.impactSmokeParticle = v; return this; }
        public Builder sparkParticle(String v) { this.sparkParticle = v; return this; }

        public Builder soundFire(String v) { this.soundFire = v; return this; }
        public Builder soundImpact(String v) { this.soundImpact = v; return this; }
        public Builder soundReloadBegin(String v) { this.soundReloadBegin = v; return this; }
        public Builder soundReloadMid(String v) { this.soundReloadMid = v; return this; }
        public Builder soundReloadDone(String v) { this.soundReloadDone = v; return this; }
        public Builder soundEmpty(String v) { this.soundEmpty = v; return this; }
        public Builder soundVolume(float v) { this.soundVolume = Math.max(0.0f, v); return this; }

        public WeaponDefinition build() {
            return new WeaponDefinition(this);
        }
    }
}
