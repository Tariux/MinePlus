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
public class TextureImageStore {

    public static final int MAX_DIMENSION = 4096;

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

    /** The models root folder this store falls back to, or {@code null}. */
    public File rootFolder() {
        return rootFolder;
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

    /**
     * The PNG file this store would load for a texture name against a model
     * file (adjacent-then-root resolution), or {@code null}. Pure path
     * resolution — no decode, no caching. Pack asset discovery uses it to
     * embed exactly the file the texel baker reads.
     */
    public File resolveTextureFile(String name, File modelFile) {
        String normalized = normalize(name);
        if (normalized.isEmpty()) {
            return null;
        }
        return resolveFile(normalized, modelFile);
    }

    private BufferedImage load(String name, File modelFile) {
        File file = resolveFile(name, modelFile);
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

    /** Adjacent folder chain, then the models root; null when absent. */
    private File resolveFile(String name, File modelFile) {
        if (modelFile != null && modelFile.getParentFile() != null) {
            String stem = modelFile.getName();
            int dot = stem.lastIndexOf('.');
            if (dot > 0) {
                stem = stem.substring(0, dot);
            }
            // Asset folders are laid out by convention, and all of these shapes
            // are in the wild: <model folder>/<name>.png, <asset>/<name>.png
            // (guns: models/<gun>.bbmodel + <gun>.png one level up),
            // <asset>/textures/<name>.png. Walk upward once, then stop at the
            // models root so a stray PNG elsewhere cannot shadow the model.
            File folder = modelFile.getParentFile();
            for (int level = 0; level < 3 && folder != null; level++) {
                if (rootFolder != null && folder.equals(rootFolder)) {
                    break;
                }
                File hit = lookupIn(folder, name, stem);
                if (hit != null) {
                    return hit;
                }
                folder = folder.getParentFile();
            }
        }
        if (rootFolder != null) {
            File hit = lookupIn(rootFolder, name, stemOf(modelFile));
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** One folder: exact name, case-insensitive name, textures/ subfolder, model stem. */
    private File lookupIn(File folder, String name, String modelStem) {
        if (folder == null || !folder.isDirectory()) {
            return null;
        }
        File exact = new File(folder, name + ".png");
        if (exact.isFile()) {
            return exact;
        }
        File indexed = indexedLookup(folder, name + ".png");
        if (indexed != null) {
            return indexed;
        }
        File textures = new File(folder, "textures");
        if (textures.isDirectory()) {
            File nested = new File(textures, name + ".png");
            if (nested.isFile()) {
                return nested;
            }
            nested = indexedLookup(textures, name + ".png");
            if (nested != null) {
                return nested;
            }
        }
        if (modelStem != null && !modelStem.isBlank() && !modelStem.equalsIgnoreCase(name)) {
            File stemNamed = new File(folder, modelStem + ".png");
            if (stemNamed.isFile()) {
                return stemNamed;
            }
            stemNamed = indexedLookup(folder, modelStem + ".png");
            if (stemNamed != null) {
                return stemNamed;
            }
        }
        return null;
    }

    private static String stemOf(File modelFile) {
        if (modelFile == null) {
            return null;
        }
        String stem = modelFile.getName();
        int dot = stem.lastIndexOf('.');
        return dot > 0 ? stem.substring(0, dot) : stem;
    }

    /** Lowercase filename -> actual filename per directory, built once and cached. */
    private final Map<File, Map<String, String>> directoryIndex = new ConcurrentHashMap<>();

    private File indexedLookup(File folder, String fileName) {
        Map<String, String> index = directoryIndex.computeIfAbsent(folder.getAbsoluteFile(), dir -> {
            File[] entries = dir.listFiles(File::isFile);
            Map<String, String> built = new java.util.HashMap<>();
            if (entries != null) {
                for (File entry : entries) {
                    built.putIfAbsent(entry.getName().toLowerCase(Locale.ROOT), entry.getName());
                }
            }
            return built;
        });
        String actual = index.get(fileName.toLowerCase(Locale.ROOT));
        return actual == null ? null : new File(folder, actual);
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
