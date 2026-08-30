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
 * root folder. Missing or invalid images are remembered so repeated lookups are free;
 * bake failures must never break model load. Decoding goes through
 * {@code javax.imageio} (JDK, zero dependencies); images beyond 4096x4096 are
 * rejected as a sanity guard.
 */
public final class TextureImageStore {

    private static final int MAX_DIMENSION = 4096;

    private final File rootFolder;
    private final Map<String, BufferedImage> cache = new ConcurrentHashMap<>();
    private final Set<String> missing = ConcurrentHashMap.newKeySet();

    public TextureImageStore(File rootFolder) {
        this.rootFolder = rootFolder;
    }

    /**
     * Decoded image for a texture name, or {@code null} when unresolvable. Results
     * (including misses) are cached for the JVM lifetime of the model set; call
     * {@link #clear()} on model reload.
     *
     * @param name      texture name as carried by {@code BakedFace.textureName()}
     * @param modelFile the model file the texture belongs to (next-to-model lookup);
     *                  may be {@code null} for API-registered models
     */
    public BufferedImage texture(String name, File modelFile) {
        String key = normalize(name);
        if (key.isEmpty()) {
            return null;
        }
        BufferedImage cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        if (missing.contains(key)) {
            return null;
        }
        BufferedImage image = load(key, modelFile);
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

    public void clear() {
        cache.clear();
        missing.clear();
    }

    public int cachedImageCount() {
        return cache.size();
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
