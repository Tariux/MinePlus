package com.mineplus.gun.weapon;

import com.mineplus.gun.runtime.FireMode;
import com.mineplus.infrastructure.definition.ItemCategory;
import java.util.Objects;
import org.bukkit.Material;

/**
 * Immutable, config-driven description of one weapon. Every gameplay value the
 * runtime reads comes from here; adding a weapon is a {@code config.yml} edit,
 * never code.
 */
public final class WeaponDefinition {

    private final String id;
    private final String displayName;
    private final String modelKey;
    private final WeaponType type;
    private final ItemCategory category;
    private final FireMode fireMode;
    private final Material backingMaterial;
    private final boolean hidden;
    private final double price;

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

    private final String muzzleParticle;
    private final String tracerParticle;
    private final String impactParticle;
    private final String soundFire;
    private final String soundReload;
    private final String soundEmpty;

    private final int grenadeFuseTicks;
    private final double grenadeRadius;
    private final double grenadeDamage;
    private final int grenadeBounces;

    private final double meleeReach;
    private final double meleeArcDegrees;
    private final double meleeKnockback;

    private WeaponDefinition(Builder b) {
        this.id = b.id;
        this.displayName = b.displayName;
        this.modelKey = b.modelKey;
        this.type = b.type;
        this.category = b.category;
        this.fireMode = b.fireMode;
        this.backingMaterial = b.backingMaterial;
        this.hidden = b.hidden;
        this.price = b.price;
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
        this.muzzleParticle = b.muzzleParticle;
        this.tracerParticle = b.tracerParticle;
        this.impactParticle = b.impactParticle;
        this.soundFire = b.soundFire;
        this.soundReload = b.soundReload;
        this.soundEmpty = b.soundEmpty;
        this.grenadeFuseTicks = b.grenadeFuseTicks;
        this.grenadeRadius = b.grenadeRadius;
        this.grenadeDamage = b.grenadeDamage;
        this.grenadeBounces = b.grenadeBounces;
        this.meleeReach = b.meleeReach;
        this.meleeArcDegrees = b.meleeArcDegrees;
        this.meleeKnockback = b.meleeKnockback;
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public String modelKey() { return modelKey; }
    public WeaponType type() { return type; }
    public ItemCategory category() { return category; }
    public FireMode fireMode() { return fireMode; }
    public Material backingMaterial() { return backingMaterial; }
    public boolean hidden() { return hidden; }
    public double price() { return price; }

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

    public String muzzleParticle() { return muzzleParticle; }
    public String tracerParticle() { return tracerParticle; }
    public String impactParticle() { return impactParticle; }
    public String soundFire() { return soundFire; }
    public String soundReload() { return soundReload; }
    public String soundEmpty() { return soundEmpty; }

    public int grenadeFuseTicks() { return grenadeFuseTicks; }
    public double grenadeRadius() { return grenadeRadius; }
    public double grenadeDamage() { return grenadeDamage; }
    public int grenadeBounces() { return grenadeBounces; }

    public double meleeReach() { return meleeReach; }
    public double meleeArcDegrees() { return meleeArcDegrees; }
    public double meleeKnockback() { return meleeKnockback; }

    /** True when firing consumes or checks ammunition. */
    public boolean usesAmmo() {
        return fireMode != FireMode.MELEE && fireMode != FireMode.THROW;
    }

    public boolean isMelee() {
        return fireMode == FireMode.MELEE;
    }

    public boolean isThrowable() {
        return fireMode == FireMode.THROW;
    }

    /** Ticks between shots from {@code rpm}, never below one tick. */
    public int shotIntervalTicks() {
        return Math.max(1, (int) Math.ceil(1200.0 / Math.max(1, rpm)));
    }

    public static final class Builder {
        private final String id;
        private String displayName;
        private String modelKey;
        private WeaponType type = WeaponType.PISTOL;
        private ItemCategory category = ItemCategory.WEAPON;
        private FireMode fireMode = FireMode.SEMI;
        private Material backingMaterial = Material.FLINT_AND_STEEL;
        private boolean hidden;
        private double price;

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

        private String muzzleParticle = "SMOKE";
        private String tracerParticle = "CRIT";
        private String impactParticle = "CRIT";
        private String soundFire = "mineplusgun:shot";
        private String soundReload = "mineplusgun:reload";
        private String soundEmpty = "mineplusgun:empty";

        private int grenadeFuseTicks = 60;
        private double grenadeRadius = 4.0;
        private double grenadeDamage = 12.0;
        private int grenadeBounces = 3;

        private double meleeReach = 3.0;
        private double meleeArcDegrees = 60.0;
        private double meleeKnockback = 0.4;

        private Builder(String id) {
            this.id = Objects.requireNonNull(id, "id");
        }

        public Builder displayName(String v) { this.displayName = v; return this; }
        public Builder modelKey(String v) { this.modelKey = v; return this; }
        public Builder type(WeaponType v) { this.type = v; return this; }
        public Builder category(ItemCategory v) { this.category = v; return this; }
        public Builder fireMode(FireMode v) { this.fireMode = v; return this; }
        public Builder backingMaterial(Material v) { this.backingMaterial = v; return this; }
        public Builder hidden(boolean v) { this.hidden = v; return this; }
        public Builder price(double v) { this.price = v; return this; }

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

        public Builder muzzleParticle(String v) { this.muzzleParticle = v; return this; }
        public Builder tracerParticle(String v) { this.tracerParticle = v; return this; }
        public Builder impactParticle(String v) { this.impactParticle = v; return this; }
        public Builder soundFire(String v) { this.soundFire = v; return this; }
        public Builder soundReload(String v) { this.soundReload = v; return this; }
        public Builder soundEmpty(String v) { this.soundEmpty = v; return this; }

        public Builder grenadeFuseTicks(int v) { this.grenadeFuseTicks = v; return this; }
        public Builder grenadeRadius(double v) { this.grenadeRadius = v; return this; }
        public Builder grenadeDamage(double v) { this.grenadeDamage = v; return this; }
        public Builder grenadeBounces(int v) { this.grenadeBounces = v; return this; }

        public Builder meleeReach(double v) { this.meleeReach = v; return this; }
        public Builder meleeArcDegrees(double v) { this.meleeArcDegrees = v; return this; }
        public Builder meleeKnockback(double v) { this.meleeKnockback = v; return this; }

        public WeaponDefinition build() {
            if (displayName == null || displayName.isBlank()) {
                displayName = id;
            }
            if (modelKey == null || modelKey.isBlank()) {
                modelKey = "gun/" + id;
            }
            return new WeaponDefinition(this);
        }
    }
}
