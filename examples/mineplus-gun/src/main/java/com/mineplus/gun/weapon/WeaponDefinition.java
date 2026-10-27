package com.mineplus.gun.weapon;

import com.mineplus.gun.runtime.FireMode;
import com.mineplus.infrastructure.combat.BallisticProfile;
import com.mineplus.infrastructure.content.ContentItem;
import com.mineplus.infrastructure.content.ContentModel;
import com.mineplus.pack.item.ItemFrames;
import java.util.Objects;
import org.bukkit.Material;

/**
 * The combat description of one weapon: its shared {@link ContentItem}
 * (identity, display, carrier, presentation — virtual model or Core frame
 * animation — shop price and visibility) plus everything only the gun
 * runtime cares about (ballistics, fire mode, sounds, melee).
 *
 * <p>Everything content-shaped lives in the Core; this class adds only the
 * combat layer, so a weapon is <em>content</em> (shop, pack, HUD) plus
 * <em>behaviour</em> (this class) and never two definitions.</p>
 */
public final class WeaponDefinition {

    private final ContentItem base;
    private final WeaponType type;
    private final FireMode fireMode;

    private final String ammoType;
    private final int magazine;
    private final int reloadTicks;
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

    private final int rpm;
    private final int burstCount;
    private final int maxHoldTicks;
    private final int pellets;

    private final boolean scope;
    private final double scopeSpreadMultiplier;

    private final String muzzleParticle;
    private final String tracerParticle;
    private final String impactParticle;
    private final String soundFire;
    private final String soundReload;
    private final String soundEmpty;

    private final double meleeReach;
    private final double meleeArcDegrees;
    private final double meleeKnockback;

    /**
     * The Core ballistics description of one round of this weapon, and its
     * scoped sibling. Both are built once, at parse time: the firing path must
     * not rebuild an immutable profile per shot, and spread is a property of the
     * profile (the engine samples the cone itself), so the scoped variant is a
     * second prebuilt profile rather than a per-shot copy.
     */
    private final BallisticProfile ballistics;
    private final BallisticProfile scopedBallistics;

    private WeaponDefinition(Builder b) {
        this.base = b.base;
        this.type = b.type;
        this.fireMode = b.fireMode;
        this.ammoType = b.ammoType;
        this.magazine = b.magazine;
        this.reloadTicks = b.reloadTicks;
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
        this.rpm = b.rpm;
        this.burstCount = b.burstCount;
        this.maxHoldTicks = b.maxHoldTicks;
        this.pellets = b.pellets;
        this.scope = b.scope;
        this.scopeSpreadMultiplier = b.scopeSpreadMultiplier;
        this.muzzleParticle = b.muzzleParticle;
        this.tracerParticle = b.tracerParticle;
        this.impactParticle = b.impactParticle;
        this.soundFire = b.soundFire;
        this.soundReload = b.soundReload;
        this.soundEmpty = b.soundEmpty;
        this.meleeReach = b.meleeReach;
        this.meleeArcDegrees = b.meleeArcDegrees;
        this.meleeKnockback = b.meleeKnockback;
        this.ballistics = buildProfile(spreadDegrees);
        this.scopedBallistics = buildProfile(spreadDegrees * scopeSpreadMultiplier);
    }

    /** The immutable Core profile of one round, at the weapon's own spread. */
    public BallisticProfile ballistics() {
        return ballistics;
    }

    /** The same profile with the scope's tighter cone. */
    public BallisticProfile scopedBallistics() {
        return scopedBallistics;
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
                // A weapon configures one ricochet probability; the engine also
                // caps how many bounces one round may spend, so a ricochet-able
                // gun gets one.
                .ricochet(ricochet, ricochet > 0.0 ? 1 : 0)
                .particles(muzzleParticle, tracerParticle, impactParticle)
                .sounds(soundFire, null, "block.note_block.basedrum", 1.0f)
                .build();
    }

    public static Builder builder(ContentItem base) {
        return new Builder(base);
    }

    /** The shared content item this weapon wraps (identity/display/pricing/presentation). */
    public ContentItem base() { return base; }
    public String id() { return base.id(); }
    public String displayName() { return base.displayName(); }
    public Material backingMaterial() { return base.carrier(); }
    public double price() { return base.price(); }
    public boolean hidden() { return base.hidden(); }
    public com.mineplus.infrastructure.definition.ItemCategory category() { return base.category(); }

    /** The weapon's presentation, or {@code null} when it has no custom model. */
    public ContentModel model() { return base.model(); }

    /** The virtual-model stem (knife and friends), or {@code null} on framed weapons. */
    public String modelKey() {
        return base.model() == null ? null : base.model().modelKey();
    }

    /** True when this weapon renders through Core frame states (no virtual model binding). */
    public boolean hasFrames() {
        return base.model() != null && base.model().framed();
    }

    /** The frame spec of a framed weapon, or {@code null}. */
    public ItemFrames frames() {
        return base.model() == null ? null : base.model().frames();
    }

    /**
     * The {@code custom_model_data} stack value that renders one frame state
     * of this weapon — the value the generated pack dispatch/host overrides
     * read on every server version. Frame states are {@code 0} (idle) through
     * {@link ItemFrames#loadedState()} (loaded), per the weapon's spec.
     */
    public int frameStackValue(int state) {
        return com.mineplus.pack.asset.ItemModelAsset.frameStackValue(base.namespace(), base.id(), state);
    }

    public WeaponType type() { return type; }
    public FireMode fireMode() { return fireMode; }

    public String ammoType() { return ammoType; }
    public int magazine() { return magazine; }
    public int reloadTicks() { return reloadTicks; }
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

    public int rpm() { return rpm; }
    public int burstCount() { return burstCount; }
    public int maxHoldTicks() { return maxHoldTicks; }

    /** Rounds discharged per trigger pull (shotgun scatter); the damage field is per pellet. */
    public int pellets() { return pellets; }

    /** True when the weapon can toggle a scope (tightens spread on right-click). */
    public boolean scope() { return scope; }

    /** Spread multiplier applied while scoped; {@code 1.0} means no benefit. */
    public double scopeSpreadMultiplier() { return scopeSpreadMultiplier; }

    public String muzzleParticle() { return muzzleParticle; }
    public String tracerParticle() { return tracerParticle; }
    public String impactParticle() { return impactParticle; }
    public String soundFire() { return soundFire; }
    public String soundReload() { return soundReload; }
    public String soundEmpty() { return soundEmpty; }

    public double meleeReach() { return meleeReach; }
    public double meleeArcDegrees() { return meleeArcDegrees; }
    public double meleeKnockback() { return meleeKnockback; }

    /** True when firing consumes or checks ammunition. */
    public boolean usesAmmo() {
        return fireMode != FireMode.MELEE;
    }

    public boolean isMelee() {
        return fireMode == FireMode.MELEE;
    }

    /** Ticks between shots from {@code rpm}, never below one tick. */
    public int shotIntervalTicks() {
        return Math.max(1, (int) Math.ceil(1200.0 / Math.max(1, rpm)));
    }

    public static final class Builder {
        private final ContentItem base;
        private WeaponType type = WeaponType.PISTOL;
        private FireMode fireMode = FireMode.SEMI;

        private String ammoType = "";
        private int magazine = 0;
        private int reloadTicks = 40;
        private double damage = 5.0;
        private double headshotMultiplier = 1.5;

        private double velocity = 3.0;
        private double gravity = 0.03;
        private double drag = 0.01;
        private int penetration = 0;
        private double ricochet = 0.0;
        private double range = 100.0;
        private double spreadDegrees = 1.0;
        private double falloffStart = 40.0;
        private double falloffPerBlock = 0.02;
        private double minDamageFraction = 0.25;

        private int rpm = 600;
        private int burstCount = 3;
        private int maxHoldTicks = 120;
        private int pellets = 1;

        private boolean scope = false;
        private double scopeSpreadMultiplier = 0.35;

        private String muzzleParticle = "SMOKE";
        private String tracerParticle = "CRIT";
        private String impactParticle = "CRIT";
        private String soundFire = "mineplusgun:shot";
        private String soundReload = "mineplusgun:reload";
        private String soundEmpty = "mineplusgun:empty";

        private double meleeReach = 3.0;
        private double meleeArcDegrees = 60.0;
        private double meleeKnockback = 0.4;

        private Builder(ContentItem base) {
            this.base = Objects.requireNonNull(base, "base");
        }

        public Builder type(WeaponType v) { this.type = v; return this; }
        public Builder fireMode(FireMode v) { this.fireMode = v; return this; }

        public Builder ammoType(String v) { this.ammoType = v; return this; }
        public Builder magazine(int v) { this.magazine = v; return this; }
        public Builder reloadTicks(int v) { this.reloadTicks = v; return this; }
        public Builder damage(double v) { this.damage = v; return this; }
        public Builder headshotMultiplier(double v) { this.headshotMultiplier = v; return this; }

        public Builder velocity(double v) { this.velocity = v; return this; }
        public Builder gravity(double v) { this.gravity = v; return this; }
        public Builder drag(double v) { this.drag = v; return this; }
        public Builder penetration(int v) { this.penetration = v; return this; }
        public Builder ricochet(double v) { this.ricochet = v; return this; }
        public Builder range(double v) { this.range = v; return this; }
        public Builder spreadDegrees(double v) { this.spreadDegrees = v; return this; }
        public Builder falloffStart(double v) { this.falloffStart = v; return this; }
        public Builder falloffPerBlock(double v) { this.falloffPerBlock = v; return this; }
        public Builder minDamageFraction(double v) { this.minDamageFraction = v; return this; }

        public Builder rpm(int v) { this.rpm = v; return this; }
        public Builder burstCount(int v) { this.burstCount = v; return this; }
        public Builder maxHoldTicks(int v) { this.maxHoldTicks = v; return this; }
        public Builder pellets(int v) { this.pellets = Math.max(1, v); return this; }

        public Builder scope(boolean v) { this.scope = v; return this; }
        public Builder scopeSpreadMultiplier(double v) { this.scopeSpreadMultiplier = v; return this; }

        public Builder muzzleParticle(String v) { this.muzzleParticle = v; return this; }
        public Builder tracerParticle(String v) { this.tracerParticle = v; return this; }
        public Builder impactParticle(String v) { this.impactParticle = v; return this; }
        public Builder soundFire(String v) { this.soundFire = v; return this; }
        public Builder soundReload(String v) { this.soundReload = v; return this; }
        public Builder soundEmpty(String v) { this.soundEmpty = v; return this; }

        public Builder meleeReach(double v) { this.meleeReach = v; return this; }
        public Builder meleeArcDegrees(double v) { this.meleeArcDegrees = v; return this; }
        public Builder meleeKnockback(double v) { this.meleeKnockback = v; return this; }

        public WeaponDefinition build() {
            return new WeaponDefinition(this);
        }
    }
}
