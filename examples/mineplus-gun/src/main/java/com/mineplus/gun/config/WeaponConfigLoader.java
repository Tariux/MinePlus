package com.mineplus.gun.config;

import com.mineplus.gun.runtime.FireMode;
import com.mineplus.gun.weapon.WeaponDefinition;
import com.mineplus.gun.weapon.WeaponType;
import com.mineplus.infrastructure.content.ContentItem;
import com.mineplus.infrastructure.content.ContentItems;
import com.mineplus.infrastructure.definition.ItemCategory;
import com.mineplus.util.DebugLogger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Parses {@code config.yml} into a {@link GunConfig}. Presentation (display
 * name, category, virtual model or Core frame animation, carrier, price,
 * visibility) is delegated to the Core {@code ContentItems} parser; only the
 * combat fields are read here.
 *
 * <p>Never throws on a bad weapon: a typo is logged and that weapon falls
 * back to a documented default, so one bad entry cannot disable the module.</p>
 */
public final class WeaponConfigLoader {

    /** The pack namespace every MineplusGun item and raw asset lives under. */
    public static final String NAMESPACE = "mineplusgun";

    private WeaponConfigLoader() {
    }

    public static GunConfig load(FileConfiguration config) {
        GunConfig.Settings settings = new GunConfig.Settings(
                config.getString("settings.language", "en"),
                GunConfig.HudMode.fromKey(config.getString("settings.hud.mode", "ACTIONBAR"),
                        GunConfig.HudMode.ACTIONBAR),
                config.getString("settings.hud.template", null),
                (int) clamp(config.getDouble("settings.hud.low-ammo-threshold", 3), 0, 64),
                worldSet(config.getStringList("settings.disabled-worlds")),
                config.getBoolean("settings.pvp-only", false)
        );

        Material ammoMaterial = material(config.getString("ammo.material", "GOLD_NUGGET"),
                Material.GOLD_NUGGET, "ammo.material");
        GunConfig.Ammo ammo = new GunConfig.Ammo(
                ammoMaterial,
                (int) clamp(config.getDouble("ammo.items-per-round", 1), 1, 9)
        );

        GunConfig.Economy economy = new GunConfig.Economy(
                GunConfig.EconomyMode.fromKey(config.getString("economy.mode", "VAULT"),
                        GunConfig.EconomyMode.VAULT),
                clamp(config.getDouble("economy.price-multiplier", 1.0), 0.0, 1000.0),
                (int) clamp(config.getDouble("economy.price-scale", 1), 0, 3)
        );

        GunConfig.Shop shop = new GunConfig.Shop(
                config.getString("shop.title", "Weapon Store"),
                (int) clamp(config.getDouble("shop.rows", 6), 1, 6),
                config.getBoolean("shop.admin-bypass-payment", true)
        );

        List<WeaponDefinition> weapons = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("weapons");
        if (section != null) {
            for (String rawId : section.getKeys(false)) {
                try {
                    weapons.add(parseWeapon(rawId, section));
                } catch (Exception exception) {
                    DebugLogger.warning("[MineplusGun] Weapon '" + rawId + "' is invalid and was skipped: "
                            + exception.getMessage());
                }
            }
        }
        return new GunConfig(settings, ammo, economy, shop, weapons);
    }

    private static WeaponDefinition parseWeapon(String rawId, ConfigurationSection parent) {
        String id = rawId.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        ConfigurationSection s = parent.getConfigurationSection(rawId);
        if (s == null) {
            s = parent;
        }

        // Identity + presentation + pricing through the Core content parser;
        // a generic arsenal entry defaults to the WEAPON category.
        ContentItem base = ContentItems.parse(NAMESPACE, id, s);
        if (base.category() == ItemCategory.UTILITY) {
            base = base.withCategory(ItemCategory.WEAPON);
        }

        WeaponType type = WeaponType.fromKey(s.getString("type", s.getString("category", "PISTOL")),
                WeaponType.PISTOL);

        return WeaponDefinition.builder(base)
                .type(type)
                .fireMode(FireMode.fromKey(s.getString("fire.mode", defaultFireMode(type)), FireMode.SEMI))
                .ammoType(s.getString("ammo.ammo-type", ""))
                .magazine((int) clamp(s.getDouble("ammo.magazine", defaultMagazine(type)), 0, 500))
                .reloadTicks((int) clamp(s.getDouble("ammo.reload-ticks", 40), 0, 400))
                .damage(clamp(s.getDouble("ballistics.damage", defaultDamage(type)), 0.0, 1000.0))
                .headshotMultiplier(clamp(s.getDouble("ballistics.headshot-multiplier", 1.5), 1.0, 10.0))
                .velocity(clamp(s.getDouble("ballistics.velocity", 3.0), 0.1, 40.0))
                .gravity(clamp(s.getDouble("ballistics.gravity", 0.03), 0.0, 2.0))
                .drag(clamp(s.getDouble("ballistics.drag", 0.01), 0.0, 0.9))
                .penetration((int) clamp(s.getDouble("ballistics.penetration", 0), 0, 10))
                .ricochet(clamp(s.getDouble("ballistics.ricochet", 0.0), 0.0, 1.0))
                .range(clamp(s.getDouble("ballistics.range", 100.0), 1.0, 500.0))
                .spreadDegrees(clamp(s.getDouble("ballistics.spread-degrees", 1.0), 0.0, 45.0))
                .falloffStart(clamp(s.getDouble("ballistics.falloff-start", 40.0), 0.0, 500.0))
                .falloffPerBlock(clamp(s.getDouble("ballistics.falloff-per-block", 0.02), 0.0, 1.0))
                .minDamageFraction(clamp(s.getDouble("ballistics.min-damage-fraction", 0.25), 0.0, 1.0))
                .rpm((int) clamp(s.getDouble("fire.rpm", defaultRpm(type)), 30, 1200))
                .burstCount((int) clamp(s.getDouble("fire.burst", 3), 1, 50))
                .maxHoldTicks((int) clamp(s.getDouble("fire.max-hold-ticks", 120), 1, 1200))
                .pellets((int) clamp(s.getDouble("fire.pellets", 1), 1, 9))
                .scope(s.getBoolean("aim.scope", false))
                .scopeSpreadMultiplier(clamp(s.getDouble("aim.spread-multiplier", 0.35), 0.0, 1.0))
                .muzzleParticle(s.getString("fx.muzzle", "SMOKE"))
                .flashParticle(s.getString("fx.flash", "FLAME"))
                .smokeParticle(s.getString("fx.smoke", "LARGE_SMOKE"))
                .tracerParticle(s.getString("fx.tracer", "CRIT"))
                .impactParticle(s.getString("fx.impact", "CRIT"))
                .impactSmokeParticle(s.getString("fx.impact-smoke", "CLOUD"))
                .sparkParticle(s.getString("fx.spark", "SMALL_FLAME"))
                .soundFire(s.getString("fx.sound-fire", defaultSound(type)))
                .soundReload(s.getString("fx.sound-reload", "mineplusgun:reload"))
                .soundEmpty(s.getString("fx.sound-empty", "mineplusgun:empty"))
                .meleeReach(clamp(s.getDouble("melee.reach", 3.0), 0.5, 6.0))
                .meleeArcDegrees(clamp(s.getDouble("melee.arc-degrees", 60.0), 1.0, 180.0))
                .meleeKnockback(clamp(s.getDouble("melee.knockback", 0.4), 0.0, 5.0))
                .build();
    }

    private static String defaultFireMode(WeaponType type) {
        return switch (type) {
            case PISTOL -> "SEMI";
            case SMG, RIFLE -> "AUTO";
            case SHOTGUN -> "PUMP";
            case SNIPER -> "BOLT";
            case MELEE -> "MELEE";
        };
    }

    private static double defaultDamage(WeaponType type) {
        return switch (type) {
            case PISTOL -> 9.0;
            case SMG -> 5.0;
            case RIFLE -> 11.0;
            case SHOTGUN -> 6.0;
            case SNIPER -> 20.0;
            case MELEE -> 7.0;
        };
    }

    private static int defaultMagazine(WeaponType type) {
        return switch (type) {
            case PISTOL -> 6;
            case SMG -> 30;
            case RIFLE -> 8;
            case SHOTGUN -> 6;
            case SNIPER -> 5;
            default -> 0;
        };
    }

    private static int defaultRpm(WeaponType type) {
        return switch (type) {
            case PISTOL -> 280;
            case SMG -> 800;
            case RIFLE -> 150;
            case SHOTGUN -> 80;
            case SNIPER -> 45;
            case MELEE -> 120;
        };
    }

    private static String defaultSound(WeaponType type) {
        return switch (type) {
            case PISTOL, SMG, MELEE -> "mineplusgun:shot";
            case RIFLE, SHOTGUN, SNIPER -> "mineplusgun:heavy";
        };
    }

    /** Tolerant material lookup; an unknown or blank name yields {@code fallback}. */
    private static Material material(String name, Material fallback, String id) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
        if (material == null) {
            DebugLogger.warning("[MineplusGun] '" + id + "' material '" + name
                    + "' is unknown; using " + fallback + ".");
            return fallback;
        }
        return material;
    }

    private static Set<String> worldSet(List<String> worlds) {
        Set<String> set = new LinkedHashSet<>();
        if (worlds != null) {
            for (String world : worlds) {
                if (world != null && !world.isBlank()) {
                    set.add(world.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return set;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
