package com.mineplus.pack.asset;

import com.mineplus.infrastructure.virtual.ModelMeta;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.pack.compile.ModelJsonWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The geometry asset of a pack block: the same imported {@link VirtualModel}
 * the virtual engine renders, serialized into a vanilla <em>block</em> element
 * model ({@code assets/<ns>/models/block/<id>.json}).
 *
 * <p>The only difference from a {@link ModelAsset} is the coordinate
 * convention: a block model is block-local, so a center-authored model's
 * element coordinates are shifted by {@code +8} on x/z (pixel (0,0,0) = block
 * center becomes the block corner) instead of the {@code -8} item shift, and
 * no item {@code display} transforms are emitted. The client renders the model
 * from a {@code BlockDisplay} whose entity position is the anchor block corner,
 * matching the virtual engine's placement lattice exactly.</p>
 */
public final class BlockModelAsset extends ModelAsset {

    public BlockModelAsset(String namespace, String id, String owner, String modelKey, File sourceFile) {
        this(namespace, id, owner, modelKey, sourceFile, null);
    }

    public BlockModelAsset(
            String namespace,
            String id,
            String owner,
            String modelKey,
            File sourceFile,
            VirtualModel capturedModel
    ) {
        super(namespace, "block/" + id, owner, modelKey, sourceFile, capturedModel);
    }

    @Override
    public byte[] serialize(VirtualModel model, ModelMeta.OriginMode originMode, String outNamespace) throws IOException {
        if (model == null) {
            return new byte[0];
        }
        return ModelJsonWriter.writeBlock(model, originMode, outNamespace).getBytes(StandardCharsets.UTF_8);
    }
}
