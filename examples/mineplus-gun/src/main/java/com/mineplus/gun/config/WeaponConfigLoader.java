package com.mineplus.gun.config;

import com.mineplus.gun.runtime.FireMode;
import com.mineplus.gun.weapon.WeaponDefinition;
import com.mineplus.gun.weapon.WeaponType;
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
 * Validates and clamps {@code config.yml} into a {@link GunConfig}. Never
 * throws on a bad weapon: a typo is logged and that weapon falls back to a
 * documented default, so one bad entry cannot disable the module.
 */
public final class WeaponConfigLoader {

    /**
     * Default carrier for firearms. A gun's backing material is a carrier only
     * (the additive {@code item_model} draws the weapon), but its vanilla
     * behaviour is NOT replaced: {@code FLINT_AND_STEEL} ignites fire on a
     * right-click and {@code SPYGLASS} zooms the camera, so neither may be used
     * for a normal weapon. This item has no use action at all.
     */
    public static final Material DEFAULT_CARRIER = Material.IRON_INGOT;

    /**
     * Carriers with no server-side use action and no client-side effect, so a
     * mis-cancelled interaction (or a pack-less client) can never produce an
     * accidental vanilla action. Only scoped weapons are allowed to opt into
     * {@code SPYGLASS} (whose zoom is the scope).
     */
    private static final Set<Material> SAFE_CARRIERS = Set.of(
            Material.IRON_INGOT, Material.GOLD_INGOT, Material.COPPER_INGOT, Material.NETHERITE_INGOT,
            Material.IRON_NUGGET, Material.GOLD_NUGGET, Material.STICK, Material.BONE, Material.FEATHER,
            Material.FLINT, Material.STRING, Material.PAPER, Material.BOOK, Material.BLAZE_ROD,
            Material.BREEZE_ROD, Material.GUNPOWDER, Material.GLOWSTONE_DUST, Material.SUGAR,
            Material.PRISMARINE_SHARD, Material.PRISMARINE_CRYSTALS, Material.NAUTILUS_SHELL,
            Material.PHANTOM_MEMBRANE, Material.SHULKER_SHELL, Material.ECHO_SHARD, Material.AMETHYST_SHARD,
            Material.QUARTZ, Material.CHARCOAL, Material.COAL, Material.NETHER_STAR, Material.NETHERITE_SCRAP,
            Material.RAW_IRON, Material.RAW_COPPER, Material.RAW_GOLD, Material.LEATHER, Material.RABBIT_HIDE,
            Material.SLIME_BALL, Material.MAGMA_CREAM, Material.GHAST_TEAR, Material.BRICK, Material.NETHER_BRICK,
            Material.CLAY_BALL, Material.INK_SAC, Material.GLOW_INK_SAC);

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
                config.getBoolean("settings.pvp-only", false),
                config.getBoolean("settings.block-damage", true)
        );

        GunConfig.Ballistics ballistics = new GunConfig.Ballistics(
                (int) clamp(config.getDouble("ballistics.max-active-bullets", 96), 1, 4096),
                (int) clamp(config.getDouble("ballistics.max-particles-per-tick", 240), 0, 4096)
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
            for (String id : section.getKeys(false)) {
                try {
                    weapons.add(parseWeapon(id, section));
                } catch (Exception exception) {
                    DebugLogger.warning("[MineplusGun] Weapon '" + id + "' is invalid and was skipped: "
                            + exception.getMessage());
                }
            }
        }
        return new GunConfig(settings, ballistics, economy, shop, weapons);
    }

    private static WeaponDefinition parseWeapon(String rawId, ConfigurationSection parent) {
        String id = rawId.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        ConfigurationSection s = parent.getConfigurationSection(rawId);
        if (s == null) {
            s = parent;
        }

        WeaponType type = WeaponType.fromKey(s.getString("type", s.getString("category", "PISTOL")),
                WeaponType.PISTOL);
        FireMode fireMode = FireMode.fromKey(s.getString("fire.mode", defaultFireMode(type)), FireMode.SEMI);
        boolean scope = s.getBoolean("aim.scope", false);
        double scopeSpread = clamp(s.getDouble("aim.spread-multiplier", 0.35), 0.0, 1.0);
        Material backing = resolveCarrier(s, id);

        ItemCategory category = switch (type) {
            case GRENADE, MELEE -> ItemCategory.WEAPON;
            default -> ItemCategory.WEAPON;
        };

        WeaponDefinition.Builder b = WeaponDefinition.builder(id)
                .displayName(s.getString("display-name", id))
                .modelKey(s.getString("model", "gun/" + id))
                .type(type)
                .category(category)
                .fireMode(fireMode)
                .backingMaterial(backing)
                .hidden(s.getBoolean("hidden", false))
                .price(Math.max(0.0, s.getDouble("price", 0.0)))
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
                .scope(scope)
                .scopeSpreadMultiplier(scopeSpread)
                .muzzleParticle(s.getString("fx.muzzle", "SMOKE"))
                .tracerParticle(s.getString("fx.tracer", "CRIT"))
                .impactParticle(s.getString("fx.impact", "CRIT"))
                .soundFire(s.getString("fx.sound-fire", "mineplusgun:shot"))
                .soundReload(s.getString("fx.sound-reload", "mineplusgun:reload"))
                .soundEmpty(s.getString("fx.sound-empty", "mineplusgun:empty"))
                .grenadeFuseTicks((int) clamp(s.getDouble("grenade.fuse-ticks", 60), 1, 600))
                .grenadeRadius(clamp(s.getDouble("grenade.radius", 4.0), 0.5, 20.0))
                .grenadeDamage(clamp(s.getDouble("grenade.damage", 12.0), 0.0, 200.0))
                .grenadeBounces((int) clamp(s.getDouble("grenade.bounces", 3), 0, 20))
                .meleeReach(clamp(s.getDouble("melee.reach", 3.0), 0.5, 6.0))
                .meleeArcDegrees(clamp(s.getDouble("melee.arc-degrees", 60.0), 1.0, 180.0))
                .meleeKnockback(clamp(s.getDouble("melee.knockback", 0.4), 0.0, 5.0));

        return b.build();
    }

    private static String defaultFireMode(WeaponType type) {
        return switch (type) {
            case PISTOL, SNIPER -> "SEMI";
            case SMG, RIFLE -> "AUTO";
            case SHOTGUN -> "PUMP";
            case MELEE -> "MELEE";
            case GRENADE -> "THROW";
        };
    }

    private static double defaultDamage(WeaponType type) {
        return switch (type) {
            case PISTOL -> 5.0;
            case SMG -> 4.0;
            case RIFLE -> 7.0;
            case SHOTGUN -> 9.0;
            case SNIPER -> 14.0;
            case MELEE -> 6.0;
            case GRENADE -> 12.0;
        };
    }

    private static int defaultMagazine(WeaponType type) {
        return switch (type) {
            case PISTOL -> 12;
            case SMG -> 30;
            case RIFLE -> 30;
            case SHOTGUN -> 6;
            case SNIPER -> 5;
            default -> 0;
        };
    }

    private static int defaultRpm(WeaponType type) {
        return switch (type) {
            case PISTOL -> 400;
            case SMG -> 800;
            case RIFLE -> 600;
            case SHOTGUN -> 90;
            case SNIPER -> 45;
            case MELEE -> 120;
            case GRENADE -> 60;
        };
    }

    /**
     * Resolves the carrier material, refusing any material whose vanilla
     * behaviour would leak through. A gun's backing material is only a carrier
     * (the additive {@code item_model} draws the weapon), but its vanilla
     * behaviour is NOT replaced: {@code SPYGLASS} zooms the camera and
     * {@code FLINT_AND_STEEL} ignites fire. If the model override is not applied
     * (pack disabled / stale client) the player must still see a harmless item,
     * never a working spyglass. Anything outside {@link #SAFE_CARRIERS} is
     * substituted with {@link #DEFAULT_CARRIER} and reported.
     */
    private static Material resolveCarrier(ConfigurationSection s, String id) {
        Material configured = material(s.getString("backing-material", null), null, id);
        if (configured == null) {
            return DEFAULT_CARRIER;
        }
        if (!SAFE_CARRIERS.contains(configured)) {
            DebugLogger.warning("[MineplusGun] Weapon '" + id + "' backing-material " + configured
                    + " has a vanilla use behaviour (e.g. SPYGLASS zooms, FLINT_AND_STEEL ignites) and was "
                    + "replaced with " + DEFAULT_CARRIER + ".");
            return DEFAULT_CARRIER;
        }
        return configured;
    }

    /** Tolerant material lookup; an unknown or blank name yields {@code fallback}. */
    private static Material material(String name, Material fallback, String id) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
        if (material == null) {
            DebugLogger.warning("[MineplusGun] Weapon '" + id + "' backing-material '" + name
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
