package com.mineplus.pack.asset;

import com.mineplus.infrastructure.virtual.ModelMeta;
import com.mineplus.infrastructure.virtual.VirtualModel;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/**
 * A geometry asset derived from a registered {@link VirtualModel} — the pack
 * pipeline's only model source. The bbmodel stays the authoring input; the
 * pack compiler serializes the Mineplus asset graph (the already-imported
 * {@code VirtualModel}) into vanilla element-model JSON. No second model
 * loader exists.
 *
 * <p>Content hash = the model's source-file bytes, so a re-imported identical
 * model never invalidates the compiled artifact, while serialization itself
 * is memoized by the compiler through that hash.</p>
 */
public final class ModelAsset extends PackAsset {

    private final String modelKey;
    private final File sourceFile;

    /**
     * @param namespace asset namespace (e.g. {@code fun})
     * @param path      output path under {@code assets/<ns>/models/} (e.g. {@code item/strad_wine})
     * @param owner     registering module name
     * @param modelKey  key of the registered virtual model this asset renders
     * @param sourceFile the model's bbmodel source file (hash identity; may be null for
     *                  API-built models, which hash over the model key)
     */
    public ModelAsset(String namespace, String path, String owner, String modelKey, File sourceFile) {
        super(namespace, path, owner);
        this.modelKey = Objects.requireNonNull(modelKey, "modelKey").trim().toLowerCase(Locale.ROOT);
        this.sourceFile = sourceFile;
    }

    public String modelKey() {
        return modelKey;
    }

    public File sourceFile() {
        return sourceFile;
    }

    @Override
    public String zipEntryPath() {
        return "assets/" + namespace() + "/models/" + path() + ".json";
    }

    @Override
    public byte[] serialize() throws IOException {
        return serialize(null, ModelMeta.OriginMode.CENTER, namespace());
    }

    /**
     * Serializes the model into vanilla element-model JSON.
     *
     * @param model      the imported model (the compiler resolves it by {@link #modelKey()})
     * @param originMode the resolved anchor convention for {@code model}
     * @param outNamespace namespace texture references point into
     */
    public byte[] serialize(VirtualModel model, ModelMeta.OriginMode originMode, String outNamespace) throws IOException {
        if (model == null) {
            return new byte[0];
        }
        String json = com.mineplus.pack.compile.ModelJsonWriter.write(model, originMode, outNamespace);
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected byte[] hashSource() throws IOException {
        if (sourceFile != null && sourceFile.isFile()) {
            return fileBytes(sourceFile);
        }
        return modelKey.getBytes(StandardCharsets.UTF_8);
    }
}
