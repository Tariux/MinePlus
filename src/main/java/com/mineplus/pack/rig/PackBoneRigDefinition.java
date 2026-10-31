package com.mineplus.pack.rig;

import java.util.Locale;

/**
 * A content definition rendered as a bone rig on the pack axis: the model that
 * supplies the bone hierarchy and its generated per-bone item models.
 *
 * <p>Like a {@link com.mineplus.pack.block.PackBlockDefinition}, this only
 * declares <em>how</em> the content is rendered; placement, collision and
 * persistence stay with the ordinary multiblock lifecycle. The level selects
 * it with {@code "renderBackend": "pack"} and {@code "renderKind": "bones"}
 * (or {@code "renderMode": "pack_bones"}).</p>
 *
 * <p><b>Two keys, same convention as pack blocks.</b> {@link #modelKey()} is
 * the render-time key the engine derives for a multiblock level
 * ({@code <typeId>_lvl_<level>}); {@link #geometryModelKey()} is the loaded
 * model file's key the bone assets attach under.</p>
 */
public final class PackBoneRigDefinition {

    private final String namespace;
    private final String id;
    private final String modelKey;
    private final String geometryModelKey;

    private PackBoneRigDefinition(String namespace, String id, String modelKey, String geometryModelKey) {
        this.namespace = requireToken(namespace, "namespace");
        this.id = requireToken(id, "rig id");
        this.modelKey = requireModelKey(modelKey);
        this.geometryModelKey = geometryModelKey == null || geometryModelKey.isBlank()
                ? this.modelKey
                : requireModelKey(geometryModelKey);
    }

    public static Builder builder(String namespace, String id, String modelKey) {
        return new Builder(namespace, id, modelKey);
    }

    public String contentId() {
        return namespace + ":" + id;
    }

    public String namespace() {
        return namespace;
    }

    public String id() {
        return id;
    }

    /** Render-time model key (the derived multiblock key). */
    public String modelKey() {
        return modelKey;
    }

    /** Key the bone geometry assets attach under — the loaded model's stem key. */
    public String geometryModelKey() {
        return geometryModelKey;
    }

    private static String requireModelKey(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Pack bone rig model key cannot be blank");
        }
        return normalized;
    }

    private static String requireToken(String value, String what) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || !normalized.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid pack bone rig " + what + ": '" + value + "'");
        }
        return normalized;
    }

    public static final class Builder {

        private final String namespace;
        private final String id;
        private final String modelKey;
        private String geometryModelKey;

        private Builder(String namespace, String id, String modelKey) {
            this.namespace = namespace;
            this.id = id;
            this.modelKey = modelKey;
        }

        /** The loaded model key the bone assets attach under (defaults to the render key). */
        public Builder geometryModelKey(String value) {
            this.geometryModelKey = value;
            return this;
        }

        public PackBoneRigDefinition build() {
            return new PackBoneRigDefinition(namespace, id, modelKey, geometryModelKey);
        }
    }
}
