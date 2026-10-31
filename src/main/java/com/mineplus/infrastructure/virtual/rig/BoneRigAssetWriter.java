package com.mineplus.infrastructure.virtual.rig;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mineplus.infrastructure.virtual.BakedCube;
import com.mineplus.infrastructure.virtual.ElementGeometry;
import com.mineplus.infrastructure.virtual.ModelMeta;
import com.mineplus.infrastructure.virtual.VirtualModel;
import com.mineplus.pack.PackApi;
import com.mineplus.pack.compile.ModelJsonWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Compiles a {@link BoneRigBlueprint} into pack assets: one item model per
 * bone that owns geometry, each re-based around that bone's pivot, plus the
 * item definitions that let an {@code ItemDisplay} carry them.
 *
 * <p>The geometry split is spatial, not authored: every cube already knows its
 * owning bone ({@link BakedCube#boneIndex()}), so a bone model is exactly its
 * cubes translated back to the bone's pivot. At render time the display sits at
 * the pose-resolved pivot, which makes the shifted geometry land exactly where
 * the unshifted model would draw it — a 60-cube model therefore renders with
 * one display per bone instead of one per cube.</p>
 *
 * <p>Bone geometry is re-based on the pivot, so the source model's anchor
 * convention no longer describes it: the emitted models are element-space
 * geometry, written without an anchor shift. The client's item renderer draws
 * every element translated by half a block, so the re-based geometry carries a
 * {@code +8} px compensation — the display's pivot then maps exactly onto
 * element origin, and rotating a bone rotates its geometry in place.</p>
 *
 * <p>Textures are referenced under the client's {@code item} atlas (the same
 * folder the pack texture pipeline registers them in), so the generated model
 * and the texture assets can never disagree.</p>
 */
public final class BoneRigAssetWriter {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * Half a block in pixels: the client's item renderer draws element space
     * translated by {@code (-0.5, -0.5, -0.5)} block, so pivot-centered
     * geometry must be authored half a block off to land on the display.
     */
    private static final float ITEM_RENDER_OFFSET_PX = 8.0f;

    private BoneRigAssetWriter() {
    }

    /** One generated pack file, path relative to {@code assets/<namespace>/}. */
    public record Entry(String path, byte[] bytes) {
    }

    /**
     * Writes the per-bone model and definition files of one rig.
     *
     * @param model     the imported model the blueprint was derived from
     * @param blueprint the shared blueprint
     * @param warnings  sink for serialization warnings (may be {@code null})
     * @return the generated entries, in bone order
     */
    public static List<Entry> writeRig(
            VirtualModel model,
            BoneRigBlueprint blueprint,
            List<String> warnings
    ) {
        List<Entry> entries = new ArrayList<>();
        if (model == null || blueprint == null || blueprint.namespace() == null) {
            return entries;
        }
        List<String> sink = warnings == null ? new ArrayList<>() : warnings;
        for (int bone = 0; bone < blueprint.boneCount(); bone++) {
            String path = blueprint.geometryPath(bone);
            if (path == null) {
                continue;
            }
            List<BakedCube> cubes = cubesFor(model, blueprint, bone);
            if (cubes.isEmpty()) {
                continue;
            }
            VirtualModel boneModel = new VirtualModel(
                    blueprint.modelKey() + "/" + blueprint.name(bone),
                    cubes,
                    model.textureMappings(),
                    model.resolution(),
                    model.modelFormat());
            // The geometry is pivot-local, not anchored in a block: GRID (no
            // shift) is the one origin mode that leaves it exactly as authored.
            String json = ModelJsonWriter.write(boneModel, ModelMeta.OriginMode.GRID, blueprint.namespace(), sink);
            entries.add(new Entry("models/item/" + path + ".json",
                    json.getBytes(StandardCharsets.UTF_8)));
            entries.add(new Entry("items/" + path + ".json",
                    itemDefinitionJson(blueprint.namespace(), path).getBytes(StandardCharsets.UTF_8)));
        }
        return entries;
    }

    private static List<BakedCube> cubesFor(VirtualModel model, BoneRigBlueprint blueprint, int bone) {
        List<BakedCube> cubes = new ArrayList<>();
        for (BakedCube cube : model.cubes()) {
            if (blueprint.boneForCube(cube) == bone) {
                cubes.add(shifted(cube, blueprint.pivot(bone)));
            }
        }
        return cubes;
    }

    /** The cube translated into the bone's pivot-local, item-element space. */
    private static BakedCube shifted(BakedCube cube, Vector3f pivotPixels) {
        Vector3f shift = new Vector3f(pivotPixels).div(16.0f);
        Vector3f itemOffset = new Vector3f(ITEM_RENDER_OFFSET_PX, ITEM_RENDER_OFFSET_PX, ITEM_RENDER_OFFSET_PX);
        Vector3f translation = new Vector3f(cube.translation()).sub(shift).add(
                ITEM_RENDER_OFFSET_PX / 16.0f,
                ITEM_RENDER_OFFSET_PX / 16.0f,
                ITEM_RENDER_OFFSET_PX / 16.0f);
        ElementGeometry geometry = cube.geometry();
        ElementGeometry localGeometry = geometry == null ? null : new ElementGeometry(
                new Vector3f(geometry.from()).sub(pivotPixels).add(itemOffset),
                new Vector3f(geometry.to()).sub(pivotPixels).add(itemOffset),
                new Vector3f(geometry.origin()).sub(pivotPixels).add(itemOffset),
                geometry.rotation(),
                geometry.rescale());
        return new BakedCube(
                cube.name(),
                translation,
                new Quaternionf(cube.leftRotation()),
                new Vector3f(cube.scale()),
                new Quaternionf(cube.rightRotation()),
                cube.faces(),
                cube.primaryTexture(),
                cube.lightEmission(),
                -1,
                localGeometry);
    }

    /** The modern item definition pointing an {@code item_model} path at its model. */
    public static String itemDefinitionJson(String namespace, String componentPath) {
        JsonObject model = new JsonObject();
        model.addProperty("type", "minecraft:model");
        model.addProperty("model", namespace + ":item/" + componentPath);
        JsonObject root = new JsonObject();
        root.add("model", model);
        return GSON.toJson(root);
    }

    /**
     * Registers a generated bone model plus its item definition through the
     * pack API — the one path both this writer and the custom-mob asset writer
     * use, so both representations stay identical.
     */
    public static void registerGenerated(
            PackApi pack,
            String namespace,
            String componentPath,
            byte[] modelJson
    ) {
        if (pack == null || !pack.isAvailable() || componentPath == null || modelJson == null) {
            return;
        }
        pack.registerGeneratedAsset(namespace, "models/item/" + componentPath + ".json", modelJson);
        pack.registerGeneratedAsset(namespace, "items/" + componentPath + ".json",
                itemDefinitionJson(namespace, componentPath).getBytes(StandardCharsets.UTF_8));
        // Also reserve a legacy display predicate: the rig spawns ItemDisplays
        // of these paths, and a pre-1.21.4 client can only resolve a model
        // through a custom_model_data override. On modern servers this is a
        // no-op (the item_model component names the path directly).
        pack.registerDisplayModel(org.bukkit.Material.PAPER, namespace, componentPath);
    }
}
