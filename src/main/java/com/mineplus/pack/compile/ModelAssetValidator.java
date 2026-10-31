package com.mineplus.pack.compile;

import com.mineplus.infrastructure.virtual.VirtualBlockManager;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.pack.asset.ModelAsset;
import com.mineplus.pack.asset.PackAsset;
import com.mineplus.pack.asset.TextureAsset;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Content diagnostics for the pack compiler ({@code PACK.VALIDATE_ASSETS}).
 *
 * <p>Every rule is a warning, never fatal: a model over the reference-project
 * budgets, a texture larger than the client handles well, a source file too
 * big to re-import comfortably, an unresolved texture name, a duplicate asset
 * identity or two assets writing the same zip entry are all reported and the
 * artifact still builds. A content mistake degrades to a warning plus whatever
 * the client's fallback renders — never a failed compile or a missing-model
 * white cube.</p>
 */
public final class ModelAssetValidator {

    public static final int MAX_BONES = 128;
    public static final int MAX_CUBES = 512;
    public static final int MAX_TEXTURE_EDGE = 2048;
    public static final long MAX_SOURCE_BYTES = 2L * 1024L * 1024L;
    public static final int MAX_ANIMATIONS = 64;

    /**
     * @param checked  assets the validator examined
     * @param skipped  assets the compiler could not serialize (already omitted)
     * @param warnings non-fatal findings, in discovery order
     * @param outOfWindow     elements the geometry guard had to re-base into the client window
     * @param unresolvedRefs  model references (texture or parent) with no entry in the artifact
     * @param uvOutOfRange    face UV windows outside the client's {@code 0..16} model space
     * @param duplicateCommands two models dispatched by the same {@code custom_model_data} in one host file
     */
    public record Report(int checked, int skipped, List<String> warnings, int outOfWindow,
                         int unresolvedRefs, int uvOutOfRange, int duplicateCommands) {
        public static final Report EMPTY = new Report(0, 0, List.of(), 0, 0, 0, 0);

        public Report {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public boolean clean() {
            return warnings.isEmpty() && skipped == 0 && outOfWindow == 0
                    && unresolvedRefs == 0 && uvOutOfRange == 0 && duplicateCommands == 0;
        }
    }

    private final VirtualBlockManager virtualBlockManager;
    private final boolean enabled;
    private final List<String> warnings = new ArrayList<>();
    private int outOfWindow;
    private int unresolvedRefs;
    private int uvOutOfRange;
    private int duplicateCommands;
    /** The artifact's own entry paths, so references resolve against this pack. */
    private Set<String> artifactEntries = Set.of();

    public ModelAssetValidator(VirtualBlockManager virtualBlockManager, boolean enabled) {
        this.virtualBlockManager = virtualBlockManager;
        this.enabled = enabled;
    }

    /** Non-fatal finding; a no-op when validation is disabled. */
    public void warn(String message) {
        if (enabled) {
            warnings.add(message);
        }
    }

    /** Two assets wrote the same zip entry; the last one wins. */
    public void entryCollision(String path) {
        warn("Pack entry collision: '" + path + "' is written by more than one asset (last one wins).");
    }

    /**
     * Checks every asset's content budgets. Never throws: one malformed asset
     * can only lose its own warning.
     */
    public void checkAssets(List<PackAsset> assets) {
        if (!enabled || assets == null || assets.isEmpty()) {
            return;
        }
        Set<String> registeredTextures = new java.util.HashSet<>();
        for (PackAsset asset : assets) {
            if (asset instanceof TextureAsset texture) {
                String path = texture.path();
                registeredTextures.add(path.substring(path.lastIndexOf('/') + 1));
            }
        }
        Map<String, String> ownersById = new HashMap<>();
        for (PackAsset asset : assets) {
            try {
                String previousOwner = ownersById.putIfAbsent(asset.id(), asset.owner());
                if (previousOwner != null && !previousOwner.equals(asset.owner())) {
                    warn("Duplicate asset id '" + asset.id() + "' registered by '" + previousOwner
                            + "' and '" + asset.owner() + "'.");
                }
                if (asset instanceof ModelAsset model) {
                    checkModel(model, registeredTextures);
                } else if (asset instanceof TextureAsset texture) {
                    checkTexture(texture);
                }
            } catch (RuntimeException malformed) {
                warn("Asset '" + safeId(asset) + "' could not be validated: " + malformed.getMessage());
            }
        }
    }

    private void checkModel(ModelAsset asset, Set<String> registeredTextures) {
        VirtualModel model = asset.capturedModel() != null
                ? asset.capturedModel()
                : virtualBlockManager.getModel(asset.modelKey());
        if (model == null) {
            // The compiler already reports a missing model; it owns that path.
            return;
        }
        if (model.cubes().size() > MAX_CUBES) {
            warn("Model '" + asset.id() + "' has " + model.cubes().size()
                    + " cubes (soft budget " + MAX_CUBES + "); displays and packets scale with cubes.");
        }
        if (model.bones().size() > MAX_BONES) {
            warn("Model '" + asset.id() + "' has " + model.bones().size()
                    + " bones (soft budget " + MAX_BONES + ").");
        }
        if (model.animations().size() > MAX_ANIMATIONS) {
            warn("Model '" + asset.id() + "' has " + model.animations().size()
                    + " animations (soft budget " + MAX_ANIMATIONS + ").");
        }
        File source = asset.sourceFile();
        if (source != null && source.isFile() && source.length() > MAX_SOURCE_BYTES) {
            warn("Model '" + asset.id() + "' source file is " + source.length()
                    + " bytes (soft budget " + MAX_SOURCE_BYTES + ").");
        }
        for (String textureName : model.textureNames()) {
            if (textureName == null || textureName.isBlank()) {
                continue;
            }
            String normalized = TextureAsset.normalizePath(textureName);
            if (normalized.isEmpty() || registeredTextures.contains(normalized)) {
                continue;
            }
            try {
                if (virtualBlockManager.resolveTextureFile(asset.modelKey(), textureName) == null) {
                    warn("Model '" + asset.id() + "' references unresolved texture '" + textureName + "'.");
                }
            } catch (RuntimeException failure) {
                warn("Model '" + asset.id() + "' texture '" + textureName
                        + "' could not be resolved: " + failure.getMessage());
            }
        }
    }

    private void checkTexture(TextureAsset asset) {
        int[] size = pngSize(asset.file());
        if (size == null) {
            return;
        }
        if (size[0] > MAX_TEXTURE_EDGE || size[1] > MAX_TEXTURE_EDGE) {
            warn("Texture '" + asset.id() + "' is " + size[0] + "x" + size[1]
                    + " (soft budget " + MAX_TEXTURE_EDGE + " px per edge).");
        }
    }

    /** Immutable snapshot of this pass. */
    public Report report(int checked, int skipped) {
        return new Report(checked, skipped, List.copyOf(warnings), outOfWindow,
                unresolvedRefs, uvOutOfRange, duplicateCommands);
    }

    // ------------------------------------------------------------------
    // Hard rules over the artifact's own entries
    // ------------------------------------------------------------------

    /**
     * Checks what the artifact actually says about itself: every model
     * reference resolves against an entry of this same pack, every face UV sits
     * in the client's {@code 0..16} model space, no host file dispatches two
     * models from one {@code custom_model_data} value, and the geometry guard's
     * work is counted. Runs on every compile regardless of
     * {@code PACK.VALIDATE_ASSETS} — these are defects, not budget advice.
     */
    public void checkArtifactEntries(Map<String, byte[]> entries, ModelGeometryGuard.Report guard) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        outOfWindow += guard.elements() + guard.clamped();
        artifactEntries = Set.copyOf(entries.keySet());
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            String path = entry.getKey();
            if (!path.startsWith("assets/") || !path.endsWith(".json")) {
                continue;
            }
            if (path.contains("/models/")) {
                checkModelEntries(path, entry.getValue());
            }
            if (isLegacyHost(path)) {
                checkHostDispatch(path, entry.getValue());
            }
        }
        for (String finding : guard.findings()) {
            issue(finding);
        }
    }

    /** Parses one model file and audits its references and UV windows. */
    private void checkModelEntries(String path, byte[] bytes) {
        com.google.gson.JsonElement parsed;
        try {
            parsed = com.google.gson.JsonParser.parseString(new String(bytes,
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException malformed) {
            issue("Model '" + path + "' is not parseable JSON: " + malformed.getMessage());
            return;
        }
        if (parsed == null || !parsed.isJsonObject()) {
            return;
        }
        com.google.gson.JsonObject root = parsed.getAsJsonObject();
        Map<String, String> textures = textureMap(root);
        String parent = text(root, "parent");
        if (parent != null) {
            checkReference(path, parent, "parent", "models", ".json");
        }
        for (String reference : textures.values()) {
            checkReference(path, reference, "texture", "textures", ".png");
        }
        if (!root.has("elements") || !root.get("elements").isJsonArray()) {
            return;
        }
        for (com.google.gson.JsonElement elementValue : root.getAsJsonArray("elements")) {
            if (!elementValue.isJsonObject()) {
                continue;
            }
            com.google.gson.JsonObject element = elementValue.getAsJsonObject();
            com.google.gson.JsonObject faces = element.has("faces")
                    && element.get("faces").isJsonObject()
                    ? element.getAsJsonObject("faces") : null;
            if (faces == null) {
                continue;
            }
            for (Map.Entry<String, com.google.gson.JsonElement> faceEntry : faces.entrySet()) {
                if (!faceEntry.getValue().isJsonObject()) {
                    continue;
                }
                com.google.gson.JsonObject face = faceEntry.getValue().getAsJsonObject();
                checkFaceUv(path, faceEntry.getKey(), face);
                String faceTexture = text(face, "texture");
                if (faceTexture != null && faceTexture.startsWith("#")
                        && !textures.containsKey(faceTexture.substring(1))) {
                    // Inherits through the parent chain, which the reference
                    // check on 'parent' already covers; only a local miss with
                    // no parent is a broken model.
                    if (parent == null) {
                        issue("Model '" + path + "' face '" + faceEntry.getKey()
                                + "' references undefined texture '" + faceTexture + "'.");
                    }
                }
            }
        }
    }

    private void checkFaceUv(String path, String direction, com.google.gson.JsonObject face) {
        if (!face.has("uv") || !face.get("uv").isJsonArray()) {
            return;
        }
        com.google.gson.JsonArray uv = face.getAsJsonArray("uv");
        if (uv.size() < 4) {
            return;
        }
        for (int index = 0; index < 4; index++) {
            float value;
            try {
                value = uv.get(index).getAsFloat();
            } catch (RuntimeException notANumber) {
                return;
            }
            if (value < -0.001f || value > 16.001f) {
                uvOutOfRange++;
                issue("Model '" + path + "' face '" + direction + "' UV is outside the client's"
                        + " 0..16 model space (" + value + "); it needs a resource-pack crop and"
                        + " renders mis-mapped.");
                return;
            }
        }
    }

    /** Two overrides of one host file dispatching the same {@code custom_model_data}. */
    private void checkHostDispatch(String path, byte[] bytes) {
        try {
            com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(
                    new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            if (parsed == null || !parsed.isJsonObject()
                    || !parsed.getAsJsonObject().has("overrides")
                    || !parsed.getAsJsonObject().get("overrides").isJsonArray()) {
                return;
            }
            Set<Integer> seen = new java.util.HashSet<>();
            for (com.google.gson.JsonElement value : parsed.getAsJsonObject().getAsJsonArray("overrides")) {
                if (!value.isJsonObject()) {
                    continue;
                }
                com.google.gson.JsonObject override = value.getAsJsonObject();
                if (!override.has("predicate") || !override.get("predicate").isJsonObject()) {
                    continue;
                }
                com.google.gson.JsonObject predicate = override.getAsJsonObject("predicate");
                if (!predicate.has("custom_model_data") || predicate.getAsJsonObject("custom_model_data")
                        .isJsonObject()) {
                    continue;
                }
                int cmd = (int) predicate.get("custom_model_data").getAsDouble();
                if (!seen.add(cmd)) {
                    duplicateCommands++;
                    issue("Host file '" + path + "' dispatches two models from custom_model_data="
                            + cmd + "; the client keeps only the last one.");
                }
            }
        } catch (RuntimeException malformed) {
            // Unreadable host files are reported by the model pass above.
        }
    }

    private void checkReference(String modelPath, String reference, String kind,
                                String folder, String extension) {
        if (reference == null || reference.isBlank() || reference.startsWith("#")) {
            return;
        }
        int colon = reference.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : reference.substring(0, colon);
        String path = colon < 0 ? reference : reference.substring(colon + 1);
        if ("minecraft".equals(namespace)) {
            // Vanilla-owned: the client always has it, and the pack must not shadow it.
            return;
        }
        String expected = "assets/" + namespace + "/" + folder + "/" + path + extension;
        if (!artifactEntries.contains(expected)) {
            unresolvedRefs++;
            issue("Model '" + modelPath + "' " + kind + " reference '" + reference
                    + "' has no '" + expected + "' entry in this pack.");
        }
    }

    private static Map<String, String> textureMap(com.google.gson.JsonObject root) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (!root.has("textures") || !root.get("textures").isJsonObject()) {
            return out;
        }
        for (Map.Entry<String, com.google.gson.JsonElement> entry
                : root.getAsJsonObject("textures").entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                out.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        return out;
    }

    private static String text(com.google.gson.JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            return null;
        }
        return object.get(key).getAsString();
    }

    private static boolean isLegacyHost(String path) {
        return path.startsWith("assets/minecraft/models/item/") || path.startsWith("assets/minecraft/models/block/");
    }

    /** Non-fatal finding that is a defect, not budget advice: always recorded. */
    private void issue(String message) {
        warnings.add(message);
    }

    /** PNG IHDR width/height, or {@code null} when the file is not a readable PNG. */
    private static int[] pngSize(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        byte[] header = new byte[24];
        try (InputStream in = Files.newInputStream(file.toPath())) {
            if (in.readNBytes(header, 0, header.length) < header.length) {
                return null;
            }
        } catch (IOException unreadable) {
            return null;
        }
        if ((header[0] & 0xFF) != 0x89 || header[1] != 'P' || header[2] != 'N' || header[3] != 'G') {
            return null;
        }
        int width = intAt(header, 16);
        int height = intAt(header, 20);
        if (width <= 0 || height <= 0) {
            return null;
        }
        return new int[]{width, height};
    }

    private static int intAt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    private static String safeId(PackAsset asset) {
        try {
            return asset.id();
        } catch (RuntimeException failure) {
            return asset.getClass().getSimpleName();
        }
    }
}
