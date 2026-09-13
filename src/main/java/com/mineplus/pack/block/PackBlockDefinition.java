package com.mineplus.pack.block;

import java.util.Locale;

/**
 * A custom block as pack content: Mineplus identity ({@code namespace:id}),
 * the registered virtual model key that supplies its geometry, and the
 * {@link PackBlockCarrier} pool its rendered {@code BlockDisplay} state is
 * allocated from.
 *
 * <p>A pack block is placed and collision-owned by the ordinary multiblock
 * lifecycle (create/place/remove, barriers, persistence); this definition only
 * declares how it is <em>rendered</em>. The geometry model and its textures are
 * consumed through the same model/texture systems the virtual engine and texel
 * baker read — no second model loader, no texel or virtual internals touched.</p>
 *
 * <p><b>Two keys for multiblock content.</b> {@link #modelKey()} is the key the
 * renderer resolves at render time — the engine's derived multiblock key
 * ({@code <typeId>_lvl_<level>}), which {@code ModelRenderingManager} creates
 * lazily. {@link #geometryModelKey()} is the key the geometry/texture assets
 * attach under — normally the model file's loaded stem key (e.g.
 * {@code alchemy-table}), which exists at the coordinated reload. They differ
 * for multiblock content and are the same for standalone blocks.</p>
 */
public final class PackBlockDefinition {

    private final String namespace;
    private final String id;
    private final String modelKey;
    private final String geometryModelKey;
    private final String displayName;
    private final PackBlockCarrier carrier;

    private PackBlockDefinition(
            String namespace,
            String id,
            String modelKey,
            String geometryModelKey,
            String displayName,
            PackBlockCarrier carrier
    ) {
        this.namespace = requireToken(namespace, "namespace");
        this.id = requireToken(id, "block id");
        this.modelKey = requireModelKey(modelKey, "model key");
        this.geometryModelKey = geometryModelKey == null || geometryModelKey.isBlank()
                ? this.modelKey
                : requireModelKey(geometryModelKey, "geometry model key");
        this.displayName = displayName == null ? "" : displayName;
        this.carrier = carrier == null ? PackBlockCarrier.NOTE_BLOCK : carrier;
    }

    /** Builder for the common case. */
    public static Builder builder(String namespace, String id, String modelKey) {
        return new Builder(namespace, id, modelKey);
    }

    /** Logical identity, e.g. {@code fun:alchemy_table}. */
    public String contentId() {
        return namespace + ":" + id;
    }

    public String namespace() {
        return namespace;
    }

    public String id() {
        return id;
    }

    /** Render-time key the renderer resolves (the derived multiblock key). */
    public String modelKey() {
        return modelKey;
    }

    /**
     * Key the geometry/texture assets attach under — the loaded model's stem
     * key for multiblock content, or {@link #modelKey()} when unset.
     */
    public String geometryModelKey() {
        return geometryModelKey;
    }

    public String displayName() {
        return displayName;
    }

    public PackBlockCarrier carrier() {
        return carrier;
    }

    private static String requireModelKey(String value, String what) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Pack block " + what + " cannot be blank");
        }
        return normalized;
    }

    private static String requireToken(String value, String what) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || !normalized.matches("[a-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid pack block " + what + ": '" + value + "'");
        }
        return normalized;
    }

    public static final class Builder {
        private final String namespace;
        private final String id;
        private final String modelKey;
        private String geometryModelKey;
        private String displayName = "";
        private PackBlockCarrier carrier = PackBlockCarrier.NOTE_BLOCK;

        private Builder(String namespace, String id, String modelKey) {
            this.namespace = namespace;
            this.id = id;
            this.modelKey = modelKey;
        }

        public Builder displayName(String value) {
            this.displayName = value == null ? "" : value;
            return this;
        }

        /**
         * Key the geometry/texture assets attach under. Required for multiblock
         * content whose model file stem differs from the derived render key
         * (the usual case); defaults to the render key.
         */
        public Builder geometryModelKey(String value) {
            this.geometryModelKey = value;
            return this;
        }

        public Builder carrier(PackBlockCarrier value) {
            this.carrier = value;
            return this;
        }

        public PackBlockDefinition build() {
            return new PackBlockDefinition(namespace, id, modelKey, geometryModelKey, displayName, carrier);
        }
    }
}
