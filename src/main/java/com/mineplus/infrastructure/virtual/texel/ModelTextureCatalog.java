package com.mineplus.infrastructure.virtual.texel;

import com.mineplus.infrastructure.virtual.EmbeddedTexture;
import com.mineplus.infrastructure.virtual.TextureMaterialResolver;
import com.mineplus.infrastructure.virtual.VirtualModel;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.bukkit.Material;

/**
 * The single texture seam of the rendering pipeline: one instance per registered
 * model. Every consumer (texel baker, pack writer, validator, diagnostics) asks
 * the catalog and never the filesystem directly.
 *
 * <p>Resolution order per texture name (see
 * {@code docs/design/2026-11-27-model-texture-pipeline.md}):
 * <ol>
 *   <li>{@link Source#EMBEDDED} — base64 pixels carried in the bbmodel; the
 *       authored art, immune to install layout and same-name collisions;</li>
 *   <li>{@link Source#FILE} — a PNG next to the model, in its asset folder, one
 *       level above, or in the models root (case-insensitive);</li>
 *   <li>{@link Source#VANILLA} — no pixels, but the name maps to a block
 *       material, so that face renders with the vanilla block;</li>
 *   <li>{@link Source#MISSING} — nothing matched; material fallback, reported.</li>
 * </ol>
 *
 * <p>It extends the file store so the texel baker and the pack writer keep one
 * contract: {@link #texture(String, File)} and {@link #raster(String, File)} here
 * answer with embedded pixels first and fall back to the disk lookup. Decoding is
 * lazy and cached for the model's lifetime; the catalog is discarded with the
 * model, so nothing accumulates across reloads.
 */
public final class ModelTextureCatalog extends TextureImageStore {

    /** Where a face texture's pixels or material came from. */
    public enum Source {
        EMBEDDED,
        FILE,
        VANILLA,
        MISSING
    }

    /** One resolved texture: its source, pixel availability and material. */
    public record Resolution(
            String name,
            Source source,
            File file,
            Material material,
            int width,
            int height,
            boolean hasPixels
    ) {
        public String describe() {
            return switch (source) {
                case EMBEDDED -> "embedded " + width + "x" + height;
                case FILE -> "file:" + (file == null ? "?" : file.getName());
                case VANILLA -> "vanilla:" + (material == null ? "?" : material.name());
                case MISSING -> "missing";
            };
        }
    }

    private final String modelKey;
    private final VirtualModel model;
    private final File modelFile;
    private final Map<String, Resolution> resolutions = new LinkedHashMap<>();

    private ModelTextureCatalog(String modelKey, VirtualModel model, File modelFile, TextureImageStore files) {
        super(files == null ? null : files.rootFolder());
        this.modelKey = modelKey == null ? (model == null ? "" : model.name()) : modelKey;
        this.model = model;
        this.modelFile = modelFile;
    }

    /** Builds the catalog lazily; nothing is decoded until a face asks. */
    public static ModelTextureCatalog of(String modelKey, VirtualModel model, File modelFile,
            TextureImageStore files) {
        return new ModelTextureCatalog(modelKey, model, modelFile, files);
    }

    /** Convenience overload for callers that only have the models root folder. */
    public static ModelTextureCatalog of(String modelKey, VirtualModel model, File modelFile) {
        return new ModelTextureCatalog(modelKey, model, modelFile, null);
    }

    public String modelKey() {
        return modelKey;
    }

    // ---------------------------------------------------------------- pixels

    /**
     * Pixels for a face with the same priority as {@link #resolution}: the
     * model's own PNG file first, then the base64 embedded in the .bbmodel.
     */
    @Override
    public BufferedImage texture(String name, File modelFile) {
        BufferedImage fromDisk = super.texture(name, modelFile);
        if (fromDisk != null) {
            return fromDisk;
        }
        EmbeddedTexture embedded = embeddedFor(normalize(name));
        if (embedded != null && embedded.hasPixels()) {
            return decode(embedded.png());
        }
        return null;
    }

    @Override
    public boolean isResolvable(String name, File modelFile) {
        return texture(name, modelFile) != null;
    }

    /** True when this face texture has authored pixels (embedded or on disk). */
    public boolean hasPixels(String textureName) {
        return texture(textureName, null) != null;
    }

    /** Pixels in the baker's packed form, or {@code null} for a material face. */
    public TextureRaster rasterFor(String textureName) {
        BufferedImage image = texture(textureName, null);
        if (image == null) {
            return null;
        }
        return new TextureRaster(
                image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth()),
                image.getWidth(),
                image.getHeight());
    }

    // ------------------------------------------------------------ resolution

    /** Resolves one texture name (normalising it first) and caches the outcome. */
    public Resolution resolution(String textureName) {
        String key = normalize(textureName);
        if (key.isEmpty()) {
            return new Resolution(textureName, Source.MISSING, null, TextureMaterialResolver.fallback(), 0, 0, false);
        }
        Resolution cached = resolutions.get(key);
        if (cached != null) {
            return cached;
        }
        Resolution computed = compute(key, textureName);
        resolutions.put(key, computed);
        return computed;
    }

    /** Material a pixel-less face renders with; never {@code null}. */
    public Material material(String textureName) {
        return resolution(textureName).material();
    }

    /**
     * Raw PNG bytes for the pack writer: the model's own PNG file first, else
     * the base64 embedded in the .bbmodel. {@code null} for vanilla-only textures.
     */
    public byte[] pngBytes(String textureName) {
        String key = normalize(textureName);
        Resolution resolution = resolution(textureName);
        if (resolution.source() == Source.FILE && resolution.file() != null) {
            try {
                return Files.readAllBytes(resolution.file().toPath());
            } catch (Exception ignored) {
                // fall through to the embedded copy
            }
        }
        EmbeddedTexture embedded = embeddedFor(key);
        if (embedded != null && embedded.hasPixels()) {
            return embedded.png();
        }
        return null;
    }

    /** Per-texture outcome for diagnostics ({@code /mineplus blocks diagnose}). */
    public Map<String, Resolution> report() {
        return new LinkedHashMap<>(resolutions);
    }

    /** Pre-resolves every texture the model carries or references. */
    public ModelTextureCatalog prime() {
        if (model != null) {
            if (model.textureMappings() != null) {
                for (String name : model.textureMappings().values()) {
                    resolution(name);
                }
            }
            for (String name : model.embeddedTextures().keySet()) {
                resolution(name);
            }
        }
        return this;
    }

    public Optional<EmbeddedTexture> embedded(String textureName) {
        return Optional.ofNullable(embeddedFor(normalize(textureName)));
    }

    // --------------------------------------------------------------- private

    private Resolution compute(String key, String originalName) {
        // External file first, then the base64 inside the .bbmodel: an artist
        // who ships a PNG next to the model overrides the copy embedded in it.
        File file = resolveTextureFile(key, modelFile);
        if (file != null) {
            int[] size = dimensions(file);
            return new Resolution(originalName, Source.FILE, file, null, size[0], size[1], true);
        }

        EmbeddedTexture embedded = embeddedFor(key);
        if (embedded != null && embedded.hasPixels()) {
            return new Resolution(originalName, Source.EMBEDDED, null, null,
                    embedded.width(), embedded.height(), true);
        }

        TextureMaterialResolver.Resolution material = TextureMaterialResolver.resolveDetailed(originalName);
        if (!material.isFallback()) {
            return new Resolution(originalName, Source.VANILLA, null, material.material(), 0, 0, false);
        }
        return new Resolution(originalName, Source.MISSING, null, TextureMaterialResolver.fallback(), 0, 0, false);
    }

    private static int[] dimensions(File file) {
        try {
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(file);
            if (image != null && image.getWidth() > 0 && image.getHeight() > 0) {
                return new int[]{image.getWidth(), image.getHeight()};
            }
        } catch (Exception ignored) {
            // Broken PNG: UV scaling falls back to the model resolution below.
        }
        return new int[]{16, 16};
    }

    private EmbeddedTexture embeddedFor(String key) {
        if (model == null || model.embeddedTextures().isEmpty() || key.isEmpty()) {
            return null;
        }
        EmbeddedTexture direct = model.embeddedTextures().get(key);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, EmbeddedTexture> entry : model.embeddedTextures().entrySet()) {
            if (normalize(entry.getKey()).equals(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private BufferedImage decode(byte[] png) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null) {
                return null;
            }
            if (image.getWidth() > MAX_DIMENSION || image.getHeight() > MAX_DIMENSION) {
                return null;
            }
            return image;
        } catch (Exception exception) {
            return null;
        }
    }

    /** Same normalisation the importer and the file store use. */
    static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String value = name.replace('\\', '/').trim();
        int slash = value.lastIndexOf('/');
        if (slash >= 0) {
            value = value.substring(slash + 1);
        }
        int colon = value.lastIndexOf(':');
        if (colon >= 0) {
            value = value.substring(colon + 1);
        }
        if (value.endsWith(".png")) {
            value = value.substring(0, value.length() - 4);
        }
        if (value.endsWith(".mcmeta")) {
            value = value.substring(0, value.length() - 7);
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
