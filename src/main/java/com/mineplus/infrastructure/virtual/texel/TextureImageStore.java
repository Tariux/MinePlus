package com.mineplus.infrastructure.virtual.texel;

import com.mineplus.util.DebugLogger;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;

/**
 * Loads and caches decoded PNG textures by name for texel surface baking, mirroring
 * the importer's texture-name resolution (last path segment, extension stripped).
 *
 * <p>Lookup order: a {@code <name>.png} next to the model file, then in the models
 * root folder. Everything is cached per <i>(texture name, model file)</i> — the same
 * texture name may resolve to different PNGs in different model folders, so a
 * name-only cache would cross-contaminate bakes between models. Missing or invalid
 * lookups are remembered so repeats are free; bake failures must never break model
 * load. Decoding goes through {@code javax.imageio} (JDK, zero dependencies); images
 * beyond 4096x4096 are rejected as a sanity guard.</p>
 */
public final class TextureImageStore {

    private static final int MAX_DIMENSION = 4096;

    /** Cache identity: a texture name resolved against one model file's lookup order. */
    private record CacheKey(String name, String modelPath) {
    }

    private final File rootFolder;
    private final Map<CacheKey, BufferedImage> cache = new ConcurrentHashMap<>();
    private final Set<CacheKey> missing = ConcurrentHashMap.newKeySet();
    private final Map<CacheKey, TextureRaster> rasters = new ConcurrentHashMap<>();

    /**
     * Bulk-extracted ARGB pixel array of a texture: one linear {@code getRGB} bulk
     * copy per texture, consumed by {@link TexelSampler}'s hot sampling loop. Per-
     * sample {@code BufferedImage.getRGB(x, y)} calls route through the raster's
     * color model per invocation; the array form indexes directly and makes
     * sampling allocation-free.
     */
    public record TextureRaster(int[] argb, int width, int height) {
    }

    public TextureImageStore(File rootFolder) {
        this.rootFolder = rootFolder;
    }

    /**
     * Bulk ARGB raster for a texture name, or {@code null} when unresolvable.
     * Extracted once per (name, model file) and cached alongside the decoded image.
     */
    public TextureRaster raster(String name, File modelFile) {
        CacheKey key = cacheKey(name, modelFile);
        if (key == null) {
            return null;
        }
        TextureRaster cached = rasters.get(key);
        if (cached != null) {
            return cached;
        }
        BufferedImage image = texture(name, modelFile);
        if (image == null) {
            return null;
        }
        int[] argb = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        TextureRaster raster = new TextureRaster(argb, image.getWidth(), image.getHeight());
        rasters.put(key, raster);
        return raster;
    }

    /**
     * Decoded image for a texture name, or {@code null} when unresolvable. Results
     * (including misses) are cached per (name, model file) for the JVM lifetime of
     * the model set; call {@link #clear()} on model reload.
     *
     * @param name      texture name as carried by {@code BakedFace.textureName()}
     * @param modelFile the model file the texture belongs to (next-to-model lookup);
     *                  may be {@code null} for API-registered models
     */
    public BufferedImage texture(String name, File modelFile) {
        CacheKey key = cacheKey(name, modelFile);
        if (key == null) {
            return null;
        }
        BufferedImage cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        if (missing.contains(key)) {
            return null;
        }
        BufferedImage image = load(key.name(), modelFile);
        if (image == null) {
            missing.add(key);
            return null;
        }
        cache.put(key, image);
        return image;
    }

    /** True when a decodable PNG exists for the texture name. */
    public boolean isResolvable(String name, File modelFile) {
        return texture(name, modelFile) != null;
    }

    /**
     * The PNG file this store would load for a normalized texture name from its
     * root folder, or {@code null} when absent. Pure path resolution — no decode,
     * no caching; used by dev tooling that needs the same file the baker reads.
     */
    public File rootFolderFile(String normalizedName) {
        if (rootFolder == null || normalizedName == null || normalizedName.isEmpty()) {
            return null;
        }
        return new File(rootFolder, normalizedName + ".png");
    }

    public void clear() {
        cache.clear();
        missing.clear();
        rasters.clear();
    }

    public int cachedImageCount() {
        return cache.size();
    }

    private static CacheKey cacheKey(String name, File modelFile) {
        String normalized = normalize(name);
        if (normalized.isEmpty()) {
            return null;
        }
        return new CacheKey(normalized, modelFile == null ? "" : modelFile.getAbsolutePath());
    }

    private BufferedImage load(String name, File modelFile) {
        File file = null;
        if (modelFile != null && modelFile.getParentFile() != null) {
            File adjacent = new File(modelFile.getParentFile(), name + ".png");
            if (adjacent.isFile()) {
                file = adjacent;
            }
        }
        if (file == null && rootFolder != null) {
            File rooted = new File(rootFolder, name + ".png");
            if (rooted.isFile()) {
                file = rooted;
            }
        }
        if (file == null) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(file);
            if (image == null) {
                DebugLogger.warning("[TexelBaking] Not a decodable image: " + file.getAbsolutePath());
                return null;
            }
            if (image.getWidth() > MAX_DIMENSION || image.getHeight() > MAX_DIMENSION) {
                DebugLogger.warning("[TexelBaking] Texture " + file.getAbsolutePath()
                        + " exceeds the " + MAX_DIMENSION + "x" + MAX_DIMENSION + " guard; ignored.");
                return null;
            }
            return image;
        } catch (Exception exception) {
            DebugLogger.warning("[TexelBaking] Failed to read texture " + file.getAbsolutePath()
                    + ": " + exception.getMessage());
            return null;
        }
    }

    private static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String key = name.trim().toLowerCase(Locale.ROOT).replace('\\', '/');
        if (key.endsWith(".png")) {
            key = key.substring(0, key.length() - 4);
        }
        if (key.endsWith(".mcmeta")) {
            key = key.substring(0, key.length() - 7);
        }
        if (key.contains(":")) {
            key = key.substring(key.lastIndexOf(':') + 1);
        }
        if (key.contains("/")) {
            key = key.substring(key.lastIndexOf('/') + 1);
        }
        return key;
    }
}
