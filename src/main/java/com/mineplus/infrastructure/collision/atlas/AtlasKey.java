package com.mineplus.infrastructure.collision.atlas;

import com.mineplus.infrastructure.collision.mask.RasterMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Everything that makes an atlas valid for one server: Minecraft version, API
 * provider, candidate-pool hash, voxel resolution and rasterisation mode.
 *
 * <p>A cache file is only reused when its key equals the running key; any change
 * (new config candidate, server upgrade, Paper instead of Spigot) rebuilds.</p>
 */
public record AtlasKey(
        String mcVersion,
        String provider,
        String poolHash,
        int resolution,
        RasterMode rasterMode) {

    public AtlasKey {
        mcVersion = safe(mcVersion, "unknown");
        provider = safe(provider, "spigot");
        poolHash = safe(poolHash, "none");
        resolution = resolution <= 0 ? 16 : resolution;
        rasterMode = rasterMode == null ? RasterMode.CONSERVATIVE : rasterMode;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    /** Short stable fingerprint of this key. */
    public String fingerprint() {
        return sha256(mcVersion + '|' + provider + '|' + poolHash + '|'
                + resolution + '|' + rasterMode.name()).substring(0, 16);
    }

    /** File name used under {@code plugins/<plugin>/cache/}. */
    public String cacheFileName() {
        String version = mcVersion.replaceAll("[^A-Za-z0-9._-]", "_");
        return "shape-atlas-" + version + "-" + fingerprint() + ".bin";
    }

    /** Stable sha-256 of the given text, lower-case hex. */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** Human-readable key, for logs and debug output. */
    public String describe() {
        return mcVersion + " / " + provider + " / pool " + poolHash.substring(0,
                Math.min(8, poolHash.length())) + " / " + resolution + "³ / " + rasterMode;
    }
}
