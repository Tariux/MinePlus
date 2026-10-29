package com.mineplus.gun.config;

import com.mineplus.gun.runtime.FireMode;
import com.mineplus.gun.weapon.WeaponDefinition;
import com.mineplus.gun.weapon.WeaponFrames;
import com.mineplus.gun.weapon.WeaponType;
import com.mineplus.infrastructure.content.ContentItem;
import com.mineplus.infrastructure.content.ContentItems;
import com.mineplus.infrastructure.content.ContentModel;
import com.mineplus.infrastructure.definition.ItemCategory;
import com.mineplus.util.DebugLogger;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Parses {@code config.yml} into a {@link GunConfig}.
 *
 * <p>Presentation is mostly discovered, not configured: identity, carrier,
 * price and display text come from the Core {@code ContentItems} parser, and
 * when a weapon declares no explicit {@code frames} block its animation is
 * read from the model files the module installed for that id
 * ({@code <id>_idle}, {@code <id>_primed}, {@code <id>_loading_N}, …). An
 * explicit block still wins, so a server owner can override a discovered
 * animation without touching the jar.</p>
 *
 * <p>Never throws on a bad weapon: a typo is logged and that weapon falls
 * back to a documented default, so one bad entry cannot disable the module.</p>
 */
public final class WeaponConfigLoader {

    /** The pack namespace every MineplusGun item and raw asset lives under. */
    public static final String NAMESPACE = WeaponFrames.NAMESPACE;

    private WeaponConfigLoader() {
    }

    public static GunConfig load(FileConfiguration config, File dataFolder) {
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
                    weapons.add(parseWeapon(rawId, section, dataFolder));
                } catch (Exception exception) {
                    DebugLogger.warning("[MineplusGun] Weapon '" + rawId + "' is invalid and was skipped: "
                            + exception.getMessage());
                }
            }
        }
        return new GunConfig(settings, ammo, economy, shop, weapons);
    }

    private static WeaponDefinition parseWeapon(String rawId, ConfigurationSection parent, File dataFolder) {
        String id = rawId.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        ConfigurationSection s = parent.getConfigurationSection(rawId);
        if (s == null) {
            s = parent;
        }

        // Identity + presentation through the Core parser; a generic arsenal
        // entry defaults to the WEAPON category.
        ContentItem base = ContentItems.parse(NAMESPACE, id, s);
        if (base.category() == ItemCategory.UTILITY) {
            base = base.withCategory(ItemCategory.WEAPON);
        }

        // No explicit frames block: use the models that shipped with the gun.
        // A gun with no models at all still works on its vanilla carrier.
        if (base.model() == null) {
            ContentModel discovered = WeaponFrames.discover(dataFolder, id);
            if (discovered != null) {
                base = withModel(base, discovered);
            }
        }

        WeaponType type = WeaponType.fromKey(s.getString("type", s.getString("category", "PISTOL")),
                WeaponType.PISTOL);

        return WeaponDefinition.builder(base)
                .type(type)
                .fireMode(FireMode.fromKey(s.getString("fire.mode", defaultFireMode(type)),
                        FireMode.fromKey(defaultFireMode(type), FireMode.SEMI)))
                .ammoType(s.getString("ammo.type", ""))
                .magazine((int) clamp(s.getDouble("ammo.magazine", defaultMagazine(type)), 0, 500))
                .reloadTicks((int) clamp(s.getDouble("ammo.reload-ticks", defaultReload(type)), 0, 400))
                .cycleTicks((int) clamp(s.getDouble("fire.cycle-ticks", 0), 0, 200))
                .damage(clamp(s.getDouble("ballistics.damage", defaultDamage(type)), 0.0, 1000.0))
                .headshotMultiplier(clamp(s.getDouble("ballistics.headshot-multiplier", 2.0), 1.0, 10.0))
                .velocity(clamp(s.getDouble("ballistics.velocity", 4.5), 0.1, 40.0))
                .gravity(clamp(s.getDouble("ballistics.gravity", 0.015), 0.0, 2.0))
                .drag(clamp(s.getDouble("ballistics.drag", 0.01), 0.0, 0.9))
                .penetration((int) clamp(s.getDouble("ballistics.penetration", 0), 0, 10))
                .ricochet(clamp(s.getDouble("ballistics.ricochet", 0.0), 0.0, 1.0))
                .range(clamp(s.getDouble("ballistics.range", 120.0), 1.0, 500.0))
                .spreadDegrees(clamp(s.getDouble("ballistics.spread-degrees", 1.0), 0.0, 45.0))
                .falloffStart(clamp(s.getDouble("ballistics.falloff-start", 50.0), 0.0, 500.0))
                .falloffPerBlock(clamp(s.getDouble("ballistics.falloff-per-block", 0.02), 0.0, 1.0))
                .minDamageFraction(clamp(s.getDouble("ballistics.min-damage-fraction", 0.3), 0.0, 1.0))
                .knockback(clamp(s.getDouble("ballistics.knockback", 0.3), 0.0, 4.0))
                .hitboxExpansion(clamp(s.getDouble("ballistics.hitbox-expansion", 0.35), 0.0, 2.0))
                .tracerSpacing(clamp(s.getDouble("ballistics.tracer-spacing", 4.0), 1.0, 32.0))
                .rpm((int) clamp(s.getDouble("fire.rpm", defaultRpm(type)), 30, 1200))
                .burstCount((int) clamp(s.getDouble("fire.burst", 3), 1, 50))
                .pellets((int) clamp(s.getDouble("fire.pellets", defaultPellets(type)), 1, 32))
                .moveSpread(clamp(s.getDouble("accuracy.move", 0.9), 0.0, 30.0))
                .airSpread(clamp(s.getDouble("accuracy.air", 3.0), 0.0, 30.0))
                .sprintSpread(clamp(s.getDouble("accuracy.sprint", 4.5), 0.0, 30.0))
                .recoilPerShot(clamp(s.getDouble("accuracy.recoil-per-shot", 0.0), 0.0, 10.0))
                .recoilRecovery(clamp(s.getDouble("accuracy.recoil-recovery", 0.15), 0.0, 5.0))
                .recoilMax(clamp(s.getDouble("accuracy.recoil-max", 2.5), 0.0, 20.0))
                .scope(s.getBoolean("aim.scope", false))
                .scopeSpreadMultiplier(clamp(s.getDouble("aim.spread-multiplier", 0.3), 0.0, 2.0))
                .scopeMovePenaltyMultiplier(clamp(s.getDouble("aim.move-multiplier", 1.5), 0.0, 5.0))
                .scopeRecoilMultiplier(clamp(s.getDouble("aim.recoil-multiplier", 0.7), 0.0, 5.0))
                .reloadClip(s.getString("animation.reload", "reload"))
                .cycleClip(s.getString("animation.cycle", "cycle"))
                .fireClip(s.getString("animation.fire", "fire"))
                .scopeInClip(s.getString("animation.scope-in", "scope_in"))
                .scopeHoldClip(s.getString("animation.scope-hold", "scope_hold"))
                .scopeOutClip(s.getString("animation.scope-out", "scope_out"))
                .equipClip(s.getString("animation.equip", "equip"))
                .muzzleParticle(s.getString("fx.muzzle", "SMOKE"))
                .flashParticle(s.getString("fx.flash", "FLAME"))
                .smokeParticle(s.getString("fx.smoke", "LARGE_SMOKE"))
                .tracerParticle(s.getString("fx.tracer", "CRIT"))
                .impactParticle(s.getString("fx.impact", "CRIT"))
                .impactSmokeParticle(s.getString("fx.impact-smoke", "CLOUD"))
                .sparkParticle(s.getString("fx.spark", "SMALL_FLAME"))
                .soundFire(s.getString("fx.sound-fire", defaultSound(type)))
                .soundImpact(s.getString("fx.sound-impact", "block.stone.hit"))
                .soundReloadBegin(s.getString("fx.sound-reload-begin", "mineplusgun:reload_begin"))
                .soundReloadMid(s.getString("fx.sound-reload-mid", "mineplusgun:reload_mid"))
                .soundReloadDone(s.getString("fx.sound-reload-done", "mineplusgun:reload_done"))
                .soundEmpty(s.getString("fx.sound-empty", "mineplusgun:empty"))
                .soundVolume((float) clamp(s.getDouble("fx.volume", 1.0), 0.0, 4.0))
                .build();
    }

    private static ContentItem withModel(ContentItem base, ContentModel model) {
        return new ContentItem(base.namespace(), base.id(), base.displayName(), base.descriptionLines(),
                base.category(), base.carrier(), base.price(), base.hidden(), model);
    }

    private static String defaultFireMode(WeaponType type) {
        return switch (type) {
            case PISTOL -> "SEMI";
            case SMG, RIFLE -> "AUTO";
            case SHOTGUN -> "PUMP";
            case SNIPER -> "BOLT";
        };
    }

    private static double defaultDamage(WeaponType type) {
        return switch (type) {
            case PISTOL -> 8.0;
            case SMG -> 6.5;
            case RIFLE -> 16.0;
            case SHOTGUN -> 6.5;
            case SNIPER -> 20.0;
        };
    }

    private static int defaultMagazine(WeaponType type) {
        return switch (type) {
            case PISTOL -> 8;
            case SMG -> 24;
            case RIFLE -> 6;
            case SHOTGUN -> 6;
            case SNIPER -> 5;
        };
    }

    private static int defaultReload(WeaponType type) {
        return switch (type) {
            case PISTOL -> 32;
            case SMG -> 45;
            case RIFLE -> 46;
            case SHOTGUN -> 55;
            case SNIPER -> 52;
        };
    }

    private static int defaultRpm(WeaponType type) {
        return switch (type) {
            case PISTOL -> 300;
            case SMG -> 650;
            case RIFLE -> 60;
            case SHOTGUN -> 70;
            case SNIPER -> 45;
        };
    }

    private static int defaultPellets(WeaponType type) {
        return type == WeaponType.SHOTGUN ? 8 : 1;
    }

    private static String defaultSound(WeaponType type) {
        return switch (type) {
            case PISTOL -> "mineplusgun:pistol_shot";
            case SMG -> "mineplusgun:battle_rifle_shot";
            case RIFLE, SNIPER -> "mineplusgun:rifle_shot";
            case SHOTGUN -> "mineplusgun:shotgun_shot";
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
