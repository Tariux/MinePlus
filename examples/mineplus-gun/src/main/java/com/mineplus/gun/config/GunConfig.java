package com.mineplus.gun.config;

import com.mineplus.gun.weapon.WeaponDefinition;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Material;

/**
 * Immutable, fully-validated module configuration parsed from the module's
 * {@code config.yml}. Every value is clamped by {@link WeaponConfigLoader};
 * a bad value degrades to a documented default instead of disabling the
 * feature.
 *
 * <p>There is no ballistics envelope here on purpose: the round budget and the
 * particle budgets are Core engine settings ({@code BALLISTICS} and
 * {@code PARTICLE_FX} in {@code settings.mp.yml}), metered across every module
 * on the server rather than negotiated between them.
 */
public record GunConfig(
        Settings settings,
        Ammo ammo,
        Economy economy,
        Shop shop,
        List<WeaponDefinition> weapons
) {

    public GunConfig {
        weapons = weapons == null ? List.of() : List.copyOf(weapons);
    }

    public enum HudMode {
        ACTIONBAR,
        BOSSBAR,
        OFF;

        public static HudMode fromKey(String key, HudMode fallback) {
            if (key == null || key.isBlank()) {
                return fallback;
            }
            try {
                return HudMode.valueOf(key.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }
    }

    public enum EconomyMode {
        VAULT,
        FREE;

        public static EconomyMode fromKey(String key, EconomyMode fallback) {
            if (key == null || key.isBlank()) {
                return fallback;
            }
            try {
                return EconomyMode.valueOf(key.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return fallback;
            }
        }
    }

    public record Settings(
            String language,
            HudMode hudMode,
            String hudTemplate,
            int lowAmmoThreshold,
            Set<String> disabledWorlds,
            boolean pvpOnly
    ) {
        public Settings {
            language = language == null || language.isBlank() ? "en" : language;
            hudMode = hudMode == null ? HudMode.ACTIONBAR : hudMode;
            hudTemplate = hudTemplate == null || hudTemplate.isBlank()
                    ? "&e{weapon} &7| &f{mag}&7/&f{reserve} &7| &b{mode}"
                    : hudTemplate;
            disabledWorlds = disabledWorlds == null ? Set.of() : Set.copyOf(disabledWorlds);
        }

        /** True when gameplay is disabled in the given world. */
        public boolean worldDisabled(String worldName) {
            return worldName != null && disabledWorlds.contains(worldName.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * The ammunition supply: reload pulls rounds out of the player's inventory
     * one item (nugget) at a time. The material is config-driven — gold
     * nuggets by default, matching the Western economy.
     */
    public record Ammo(Material material, int itemsPerRound) {
        public Ammo {
            material = material == null ? Material.GOLD_NUGGET : material;
            itemsPerRound = Math.max(1, Math.min(9, itemsPerRound));
        }
    }

    public record Economy(EconomyMode mode, double priceMultiplier, int priceScale) {
        public Economy {
            mode = mode == null ? EconomyMode.VAULT : mode;
            priceMultiplier = Math.max(0.0, priceMultiplier);
            priceScale = Math.max(0, Math.min(3, priceScale));
        }

        /** Applies the global multiplier/rounding to a raw configured price. */
        public double apply(double raw) {
            double scaled = raw * priceMultiplier;
            double factor = switch (priceScale) {
                case 0 -> 1.0;
                case 1 -> 10.0;
                case 2 -> 100.0;
                default -> 1000.0;
            };
            return Math.round(scaled * factor) / factor;
        }
    }

    public record Shop(String title, int rows, boolean adminBypassPayment) {
        public Shop {
            title = title == null || title.isBlank() ? "Weapon Store" : title;
            rows = Math.max(1, Math.min(6, rows));
        }
    }
}
