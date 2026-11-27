package com.mineplus.infrastructure.collision.fit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * On-disk cache of baked fits, keyed by {@link FitResult#cacheKey()}.
 *
 * <p>Files live under {@code plugins/<plugin>/cache/fits/<key>.json}. A cached
 * fit is only reusable when its model, atlas key, profile and yaw match the
 * request; the key is the file name, so a mismatch cannot be loaded. Corrupt
 * files are ignored and overwritten on the next bake.</p>
 *
 * <p>Pure IO; safe to use off the main thread.</p>
 */
public final class FitCache {

    private static final Logger LOGGER = Logger.getLogger("Mineplus");

    private final Path directory;

    public FitCache(Path directory) {
        this.directory = directory;
    }

    public Path directory() {
        return directory;
    }

    /** Loads a fit for the given model key and dimensions if present and valid. */
    public Optional<FitResult> load(String modelKey, String atlasKey, String profileName,
            double yaw, int resolution, int sizeX, int sizeY, int sizeZ) {
        if (directory == null) {
            return Optional.empty();
        }
        String key = key(modelKey, atlasKey, profileName, yaw, resolution, sizeX, sizeY, sizeZ);
        Path file = directory.resolve(key + ".json");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            FitResult result = FitResult.fromJson(Files.readString(file, StandardCharsets.UTF_8));
            if (!result.modelKey().equals(modelKey) || !result.atlasKey().equals(atlasKey)
                    || result.sizeX() != sizeX || result.sizeY() != sizeY
                    || result.sizeZ() != sizeZ) {
                return Optional.empty();
            }
            return Optional.of(result);
        } catch (IOException | RuntimeException broken) {
            LOGGER.fine("[collision] ignoring unreadable fit cache " + file.getFileName()
                    + ": " + broken.getMessage());
            return Optional.empty();
        }
    }

    /** Writes a fit atomically; failures are logged and swallowed. */
    public void save(FitResult result) {
        if (directory == null || result == null) {
            return;
        }
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve(result.cacheKey() + ".json");
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, result.toJson(), StandardCharsets.UTF_8);
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            LOGGER.warning("[collision] could not write fit cache: " + failure.getMessage());
        }
    }

    /** Key used as the file name; mirrors {@link FitResult#cacheKey()}. */
    public static String key(String modelKey, String atlasKey, String profileName, double yaw,
            int resolution, int sizeX, int sizeY, int sizeZ) {
        return com.mineplus.infrastructure.collision.atlas.AtlasKey.sha256(
                modelKey + '|' + atlasKey + '|' + profileName + '|' + yaw + '|'
                        + resolution + '|' + sizeX + 'x' + sizeY + 'x' + sizeZ);
    }
}
