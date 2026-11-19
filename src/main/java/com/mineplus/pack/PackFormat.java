package com.mineplus.pack;

import java.util.Locale;
import com.mineplus.util.ServerVersion;

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
        int[] version = ServerVersion.parseVersion(bukkitVersion);
        if (version[0] <= 0) {
            return 46;
        }
        if (version[0] == 1) {
            int minor = version[1];
            int patch = version[2];
            if (minor < 20) {
                return 15; // pre-1.20 servers: closest supported era
            }
            return switch (minor) {
                case 20 -> switch (patch) {
                    case 0, 1 -> 15;
                    case 2 -> 18;
                    case 3, 4 -> 22;
                    default -> 32; // 1.20.5+
                };
                case 21 -> switch (patch) {
                    case 0, 1 -> 34;
                    case 2, 3 -> 42;
                    case 4 -> 46;
                    case 5 -> 55;
                    case 6 -> 63;
                    default -> 64; // 1.21.7+ and forward-compatible fallback
                };
                default -> latestKnownPackFormat();
            };
        }
        // Calendar versions (26.x and beyond): the newest known format until
        // the table catches up. Never the 1.x-minor branches.
        return latestKnownPackFormat();
    }

    /**
     * The newest {@code pack_format} this plugin's table knows. Used as the
     * {@code supported_formats} ceiling in {@code pack.mcmeta} so newer
     * clients (whose format drifted above the detected value) still accept
     * the pack instead of rejecting it as incompatible.
     */
    public static int latestKnownPackFormat() {
        return 64;
    }

    /** True when the server supports the modern {@code item_model} component (1.21.4+). */
    public static ItemRepresentation itemRepresentation(String bukkitVersion) {
        int[] version = ServerVersion.parseVersion(bukkitVersion);
        if (version[0] <= 0) {
            return ItemRepresentation.MODERN_ITEM_MODEL;
        }
        return ServerVersion.atLeast(version, 1, 21, 4)
                ? ItemRepresentation.MODERN_ITEM_MODEL
                : ItemRepresentation.LEGACY_CUSTOM_MODEL_DATA;
    }

    /** Lowercased locale key of a version string, for diagnostics. */
    public static String describe(String bukkitVersion) {
        return bukkitVersion == null ? "unknown" : bukkitVersion.toLowerCase(Locale.ROOT);
    }
}
