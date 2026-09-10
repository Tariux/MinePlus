package com.mineplus.pack;

import java.util.Locale;

/**
 * Minecraft-version-specific pack output, isolated in one place: the
 * {@code pack.mcmeta} pack_format and the item representation strategy.
 * Nothing else in the pack subsystem branches on the server version.
 *
 * <p>Pack formats drift with every Minecraft release; when the table lags,
 * operators pin the value with {@code PACK.PACK_FORMAT_OVERRIDE} until the
 * table catches up.</p>
 */
public final class PackFormat {

    private PackFormat() {
    }

    /**
     * The item representation strategy for the running server.
     *
     * <ul>
     *   <li>{@code MODERN_ITEM_MODEL} (1.21.4+) — the {@code item_model}
     *       component points at {@code assets/<ns>/items/<id>.json}. Strictly
     *       additive: vanilla items without a Mineplus component render
     *       exactly as before.</li>
     *   <li>{@code LEGACY_CUSTOM_MODEL_DATA} (older servers) —
     *       {@code custom_model_data} item metadata plus predicate overrides
     *       on the backing vanilla item model. The override only redirects
     *       items carrying the Mineplus custom-model-data value (never unset
     *       vanilla items), but the vanilla item model file is regenerated —
     *       the classic cross-plugin conflict surface of the era.</li>
     * </ul>
     */
    public enum ItemRepresentation {
        MODERN_ITEM_MODEL,
        LEGACY_CUSTOM_MODEL_DATA
    }

    /**
     * Resolves the {@code pack.mcmeta} {@code pack_format} for the given
     * Bukkit version string (e.g. {@code 1.21.4-R0.1-SNAPSHOT}).
     *
     * @param bukkitVersion {@code Bukkit.getBukkitVersion()}
     * @param override      operator override from settings; 0 = use the table
     */
    public static int packFormat(String bukkitVersion, int override) {
        if (override > 0) {
            return override;
        }
        int[] version = parseVersion(bukkitVersion);
        if (version == null) {
            return 46;
        }
        int minor = version[1];
        int patch = version[2];
        if (minor < 21) {
            return 34;
        }
        return switch (minor) {
            case 21 -> switch (patch) {
                case 0, 1 -> 34;
                case 2, 3 -> 42;
                case 4 -> 46;
                case 5 -> 55;
                case 6 -> 63;
                default -> 64; // 1.21.7+ and forward-compatible fallback
            };
            default -> 64; // future minors: latest known format until the table catches up
        };
    }

    /** True when the server supports the modern {@code item_model} component (1.21.4+). */
    public static ItemRepresentation itemRepresentation(String bukkitVersion) {
        int[] version = parseVersion(bukkitVersion);
        if (version == null) {
            return ItemRepresentation.MODERN_ITEM_MODEL;
        }
        boolean modern = version[1] > 21 || (version[1] == 21 && version[2] >= 4);
        return modern ? ItemRepresentation.MODERN_ITEM_MODEL : ItemRepresentation.LEGACY_CUSTOM_MODEL_DATA;
    }

    /** Parses {@code 1.21.4-R0.1-SNAPSHOT} into {@code [21, 4]}; null when unparseable. */
    private static int[] parseVersion(String bukkitVersion) {
        if (bukkitVersion == null || bukkitVersion.isBlank()) {
            return null;
        }
        String release = bukkitVersion.split("-", 2)[0].trim();
        String[] parts = release.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            return new int[]{
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    parts.length > 2 ? Integer.parseInt(parts[2]) : 0
            };
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /** Lowercased locale key of a version string, for diagnostics. */
    public static String describe(String bukkitVersion) {
        return bukkitVersion == null ? "unknown" : bukkitVersion.toLowerCase(Locale.ROOT);
    }
}
