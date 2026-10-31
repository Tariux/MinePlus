package com.mineplus.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;

/**
 * Runtime Minecraft/Paper version of this server, parsed once from
 * {@code Bukkit#getBukkitVersion()} (e.g. {@code "1.21.1-R0.1-SNAPSHOT"}), with
 * the feature flags the rendering subsystems branch on.
 *
 * <p>Feature detection is by version table, never by "class exists" probes: a
 * version whose API surface changed shape reports the same flag on every
 * vendor build. A flag that is {@code false} disables its feature with a clear
 * log line at startup rather than failing.
 */
public final class ServerVersion {

    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    private static volatile int[] version;
    private static volatile String raw = "unknown";

    private ServerVersion() {
    }

    /** Parses a {@code bukkitVersion} string ({@code major, minor, patch}); test entry point. */
    public static int[] parseVersion(String bukkitVersion) {
        if (bukkitVersion == null) {
            return new int[]{0, 0, 0};
        }
        Matcher matcher = VERSION_PATTERN.matcher(bukkitVersion);
        if (!matcher.find()) {
            return new int[]{0, 0, 0};
        }
        return new int[]{
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3))
        };
    }

    /** The server's version triple, probed on first use. */
    public static int[] version() {
        int[] current = version;
        if (current == null) {
            synchronized (ServerVersion.class) {
                current = version;
                if (current == null) {
                    try {
                        raw = Bukkit.getBukkitVersion();
                    } catch (Throwable unavailable) {
                        raw = "unknown";
                    }
                    current = parseVersion(raw);
                    version = current;
                }
            }
        }
        return current;
    }

    /** True when major > {@code major}, or equal with a larger-or-equal minor. */
    public static boolean atLeast(int major, int minor) {
        int[] v = version();
        if (v[0] != major) {
            return v[0] > major;
        }
        return v[1] >= minor;
    }

    private static boolean atLeast(int major, int minor, int patch) {
        int[] v = version();
        if (v[0] != major) {
            return v[0] > major;
        }
        if (v[1] != minor) {
            return v[1] > minor;
        }
        return v[2] >= patch;
    }

    /** Display entities ({@code ItemDisplay}/{@code BlockDisplay}, 1.19.4+). */
    public static boolean displayEntities() {
        return atLeast(1, 19, 4);
    }

    /** The {@code item_model} item component and item definitions (1.21.4+). */
    public static boolean itemModelComponent() {
        return atLeast(1, 21, 4);
    }

    /**
     * Data-driven entity variants as datapack registries ({@code pig_variant},
     * {@code cow_variant}, …, 1.21.5+).
     */
    public static boolean dataDrivenVariants() {
        return atLeast(1, 21, 5);
    }

    /** The raw {@code bukkitVersion} string, for diagnostics. */
    public static String rawVersion() {
        version();
        return raw;
    }
}
