package com.mineplus.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineplus.infrastructure.mob.custom.model.BbModelParser;
import com.mineplus.infrastructure.mob.custom.model.ModelAnimation;
import com.mineplus.infrastructure.mob.custom.model.ModelBone;
import com.mineplus.infrastructure.mob.custom.model.ModelCube;
import com.mineplus.infrastructure.mob.custom.model.ModelData;
import com.mineplus.infrastructure.mob.custom.model.ModelTexture;
import com.mineplus.infrastructure.mob.custom.render.ModelBundle;
import com.mineplus.infrastructure.mob.custom.render.ModelBundleCompiler;
import com.mineplus.infrastructure.mob.custom.render.PngUtil;
import com.mineplus.infrastructure.mob.custom.rig.BoneTransformMath;
import com.mineplus.pack.PackApi;
import com.mineplus.pack.PlayerPackState;
import com.mineplus.pack.block.PackBlockDefinition;
import com.mineplus.pack.compile.PackArtifact;
import com.mineplus.pack.item.PackItemDefinition;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Plain-Java test runner for the custom-mob pipeline (no Bukkit server, no
 * framework): run {@code main} after compiling; a non-zero exit code means a
 * check failed. Covers the transform conventions, keyframe sampling, the
 * bbmodel parser (fixture), the pack emitter's window fitting and the PNG
 * placeholder round-trip.
 */
public final class CustomMobPipelineTest {

    private static final float EPS = 1.0e-3f;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        pngRoundTrip();
        curveKeyframes();
        loopAndHold();
        eulerSigns();
        pivotAndNesting();
        bbmodelFixture();
        emitterWindowFitting();
        if (failures.isEmpty()) {
            System.out.println("CustomMobPipelineTest: all checks passed");
            return;
        }
        System.out.println("CustomMobPipelineTest: " + failures.size() + " failure(s)");
        for (String failure : failures) {
            System.out.println("  FAIL " + failure);
        }
        System.exit(1);
    }

    // ------------------------------------------------------------------

    private static void pngRoundTrip() {
        byte[] png = PngUtil.neutralPlaceholder(64, 32);
        check("placeholder is a PNG", PngUtil.isPng(png));
        int[] dims = PngUtil.dimensions(png);
        check("placeholder dims 64x32", dims != null && dims[0] == 64 && dims[1] == 32);
        check("garbage is not a PNG", !PngUtil.isPng(new byte[]{1, 2, 3, 4}));
        check("null is not a PNG", !PngUtil.isPng(null));
        check("dimensions of garbage is null", PngUtil.dimensions(new byte[]{1, 2, 3, 4}) == null);
    }

    private static void curveKeyframes() {
        ModelAnimation.Curve linear = curve(ModelAnimation.Interpolation.LINEAR,
                0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 10.0f, 20.0f, 30.0f);
        float[] out = new float[3];
        linear.sample(0.5f, out);
        check("linear midpoint", near(out[0], 5.0f) && near(out[1], 10.0f) && near(out[2], 15.0f));
        linear.sample(-1.0f, out);
        check("linear clamps before start", near(out[0], 0.0f));
        linear.sample(2.0f, out);
        check("linear clamps after end", near(out[0], 10.0f) && near(out[2], 30.0f));

        ModelAnimation.Curve step = curve(ModelAnimation.Interpolation.STEP,
                0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 10.0f, 0.0f, 0.0f);
        step.sample(0.99f, out);
        check("step holds previous key", near(out[0], 0.0f));

        // Keys 0,10,10,0 at t=0..3: the classic smooth plateau; midpoint of the
        // middle segment overshoots slightly (11.25), which is catmullrom.
        ModelAnimation.Curve smooth = curve(ModelAnimation.Interpolation.CATMULLROM,
                0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 10.0f, 0.0f, 0.0f,
                2.0f, 10.0f, 0.0f, 0.0f, 3.0f, 0.0f, 0.0f, 0.0f);
        smooth.sample(1.5f, out);
        check("catmullrom midpoint", near(out[0], 11.25f));
        smooth.sample(0.0f, out);
        check("catmullrom touches first key", near(out[0], 0.0f));
        smooth.sample(3.0f, out);
        check("catmullrom touches last key", near(out[0], 0.0f));
    }

    private static ModelAnimation.Curve curve(ModelAnimation.Interpolation interpolation,
                                              float... packed) {
        int keys = packed.length / 4;
        float[] times = new float[keys];
        float[][] values = new float[keys][3];
        ModelAnimation.Interpolation[] interps = new ModelAnimation.Interpolation[keys];
        for (int i = 0; i < keys; i++) {
            times[i] = packed[i * 4];
            values[i][0] = packed[i * 4 + 1];
            values[i][1] = packed[i * 4 + 2];
            values[i][2] = packed[i * 4 + 3];
            interps[i] = interpolation;
        }
        return new ModelAnimation.Curve(times, values, interps);
    }

    private static void loopAndHold() {
        ModelAnimation loop = ModelAnimation.builder("walk")
                .lengthSeconds(2.0f).loopMode(ModelAnimation.LoopMode.LOOP).build();
        check("loop wraps forward", near(loop.wrap(2.5f), 0.5f));
        check("loop wraps negative", near(loop.wrap(-0.5f), 1.5f));

        ModelAnimation once = ModelAnimation.builder("die")
                .lengthSeconds(1.0f).loopMode(ModelAnimation.LoopMode.ONCE).build();
        check("once clamps", near(once.wrap(3.0f), 1.0f));

        ModelAnimation hold = ModelAnimation.builder("die")
                .lengthSeconds(1.0f).loopMode(ModelAnimation.LoopMode.HOLD).build();
        check("hold clamps", near(hold.wrap(3.0f), 1.0f));

        ModelAnimation untimed = ModelAnimation.builder("x").build();
        check("zero-length wraps to zero", near(untimed.wrap(5.0f), 0.0f));
    }

    private static void eulerSigns() {
        Quaternionf q = new Quaternionf();
        Vector3f out = new Vector3f();

        BoneTransformMath.eulerZyxDegrees(90, 0, 0, q);
        q.transform(new Vector3f(0, 1, 0), out);
        check("rx=90 maps +y to +z", near(out.x, 0) && near(out.y, 0) && near(out.z, 1));

        // Extrinsic x -> y -> z: Rx applies to the vector first, then Ry.
        BoneTransformMath.eulerZyxDegrees(90, 90, 0, q);
        q.transform(new Vector3f(1, 0, 0), out);
        check("composition is Rz*Ry*Rx", near(out.x, 0) && near(out.y, 0) && near(out.z, -1));

        BoneTransformMath.yawFrame(0.0f, q);
        q.transform(new Vector3f(0, 0, -1), out);
        check("yaw 0 faces the rig front south", near(out.x, 0) && near(out.z, 1));

        check("head level", near(BoneTransformMath.headYawModel(30.0f, 30.0f), 0.0f));
        check("head right clamps at 75", near(BoneTransformMath.headYawModel(165.0f, 0.0f), -75.0f));
        check("head left sign", near(BoneTransformMath.headYawModel(-30.0f, 0.0f), 30.0f));
        check("pitch down is negative model pitch",
                near(BoneTransformMath.headPitchModel(45.0f), -45.0f));
        check("pitch clamps", near(BoneTransformMath.headPitchModel(90.0f), -75.0f));
    }

    private static void pivotAndNesting() {
        Quaternionf scratch = new Quaternionf();
        Matrix4f child = BoneTransformMath.localMatrix(16, 16, 0, 0, 0, 90, 1.0f,
                new Matrix4f(), scratch);
        Vector3f point = child.transformPosition(new Vector3f(4, 0, 0), new Vector3f());
        check("pivot-relative rotation about the pivot", near(point.x, 1) && near(point.y, 5));

        Matrix4f parent = BoneTransformMath.localMatrix(8, 0, 0, 0, 0, 0, 1.0f,
                new Matrix4f(), scratch);
        Matrix4f absolute = new Matrix4f(parent).mul(child);
        Vector3f nested = absolute.transformPosition(new Vector3f(4, 0, 0), new Vector3f());
        check("parent composes after child", near(nested.x, 1.5f) && near(nested.y, 5.0f));

        Matrix4f scaled = BoneTransformMath.localMatrix(0, 0, 0, 0, 0, 0, 2.0f,
                new Matrix4f(), scratch);
        Vector3f doubled = scaled.transformPosition(new Vector3f(1, 0, 0), new Vector3f());
        check("scale multiplies", near(doubled.x, 2.0f));
    }

    // ------------------------------------------------------------------
    // bbmodel fixture
    // ------------------------------------------------------------------

    private static void bbmodelFixture() {
        String pngBase64 = Base64.getEncoder().encodeToString(PngUtil.neutralPlaceholder(32, 32));
        String json = "{"
                + "\"meta\": {\"format_version\": \"4.10\", \"box_uv\": false},"
                + "\"resolution\": {\"width\": 32, \"height\": 32},"
                + "\"elements\": ["
                + "  {\"name\": \"torso\", \"uuid\": \"e1\", \"type\": \"cube\","
                + "   \"from\": [-4, 0, -2], \"to\": [4, 12, 2], \"origin\": [0, 0, 0],"
                + "   \"texture\": 0,"
                + "   \"faces\": {\"north\": {\"uv\": [0.0, 0.0, 0.5, 0.375]}}},"
                + "  {\"name\": \"cap\", \"uuid\": \"e2\", \"type\": \"cube\","
                + "   \"from\": [-3, 12, -3], \"to\": [3, 18, 3], \"origin\": [0, 12, 0],"
                + "   \"rotation\": [0, 0, 45], \"texture\": 0,"
                + "   \"faces\": {\"north\": {\"uv\": [0.5, 0.5, 0.75, 0.75]}}},"
                + "  {\"name\": \"hand_item_r\", \"uuid\": \"e_loc\", \"type\": \"locator\","
                + "   \"position\": [5, 6, 0]},"
                + "  {\"name\": \"loose\", \"uuid\": \"e3\", \"type\": \"cube\","
                + "   \"from\": [10, 0, 10], \"to\": [12, 2, 12], \"origin\": [0, 0, 0], \"texture\": 0}"
                + "],"
                + "\"outliner\": ["
                + "  {\"name\": \"body\", \"uuid\": \"g1\", \"origin\": [0, 0, 0],"
                + "   \"rotation\": [0, 0, 0], \"children\": [\"e1\","
                + "    {\"name\": \"head\", \"uuid\": \"g2\", \"origin\": [0, 12, 0],"
                + "     \"rotation\": [10, 0, 0], \"children\": [\"e2\", \"e_loc\"]}]},"
                + "  \"e3\""
                + "],"
                + "\"textures\": ["
                + "  {\"name\": \"skin\", \"width\": 32, \"height\": 32,"
                + "   \"source\": \"data:image/png;base64," + pngBase64 + "\"}"
                + "],"
                + "\"animations\": ["
                + "  {\"name\": \"Walk\", \"length\": 1.0, \"loop\": \"loop\","
                + "   \"animators\": {\"g1\": {\"rotation\": {"
                + "     \"0.0\": {\"data_point\": [0, 0, 0]},"
                + "     \"0.5\": {\"data_point\": [10, 0, 0], \"lerp_mode\": \"catmullrom\"},"
                + "     \"1.0\": {\"data_point\": [0, 0, 0]}}}}}"
                + "]"
                + "}";

        ModelData model = BbModelParser.parse("fixture.bbmodel",
                json.getBytes(StandardCharsets.UTF_8), reference -> null);

        // root + body + head + hand locator + head_rot0 (baked element rotation)
        check("fixture bone count", model.boneCount() == 5);

        int body = model.boneIndex("body");
        check("body exists", body == 1);
        ModelBone bodyBone = model.bone(body);
        check("body parent is root", bodyBone.parentIndex() == 0);
        check("body pivot", near(bodyBone.pivotX(), 0) && near(bodyBone.pivotY(), 0));
        check("body has one cube", bodyBone.cubes().size() == 1);
        ModelCube torso = bodyBone.cubes().get(0);
        check("cube is pivot-relative", near(torso.fromX(), -4) && near(torso.toY(), 12));
        ModelCube.Uv north = torso.face("north");
        check("per-face uv scaled to texture pixels",
                north != null && near(north.u2(), 16.0f) && near(north.v2(), 12.0f));

        ModelBone head = model.bone(model.boneIndex("head"));
        check("head rest x rotation", near(head.restRotationX(), 10.0f));
        check("head pivot", near(head.pivotY(), 12.0f));
        check("head parent is body", head.parentIndex() == body);

        ModelBone hand = model.bone(model.boneIndex("hand_item_r"));
        check("locator pivot", near(hand.pivotX(), 5.0f) && near(hand.pivotY(), 6.0f));
        check("locator has no geometry", hand.cubes().isEmpty());

        ModelBone rot = model.bone(model.boneIndex("head_rot0"));
        check("rotation sub-bone exists", rot != null);
        check("sub-bone parent is head", rot.parentIndex() == model.boneIndex("head"));
        check("sub-bone rest z", near(rot.restRotationZ(), 45.0f));
        check("sub-bone pivot at rotation origin", near(rot.pivotY(), 12.0f));
        check("sub-bone cube rebased", rot.cubes().size() == 1
                && near(rot.cubes().get(0).fromY(), 0.0f)
                && near(rot.cubes().get(0).toY(), 6.0f));

        check("loose element lands on root",
                model.bone(0).cubes().size() == 1);

        ModelAnimation walk = model.animations().get("walk");
        check("walk clip parsed (name lower-cased)", walk != null);
        check("walk loop mode", walk.loopMode() == ModelAnimation.LoopMode.LOOP);
        ModelAnimation.BoneTrack track = walk.track("body");
        check("walk drives body rotation", track != null && track.rotation() != null);
        float[] out = new float[3];
        track.rotation().sample(0.25f, out);
        check("linear segment before catmullrom key", near(out[0], 5.0f));
        track.rotation().sample(0.75f, out);
        check("catmullrom segment", near(out[0], 5.625f));
        check("walk wrap", near(walk.wrap(1.25f), 0.25f));
    }

    // ------------------------------------------------------------------
    // Emitter window fitting (through the real compiler + a captured pack)
    // ------------------------------------------------------------------

    private static void emitterWindowFitting() {
        CapturingPack pack = new CapturingPack();

        ModelCube left = new ModelCube(-30, 0, -2, -2, 4, 2, 0.0f, 0, Map.of());
        ModelCube right = new ModelCube(2, 0, -2, 30, 4, 2, 0.0f, 0, Map.of());
        Map<String, ModelCube.Uv> smallFaces = Map.of("north", new ModelCube.Uv(0, 0, 16, 12));
        ModelCube small = new ModelCube(0, 0, 0, 2, 2, 2, 0.0f, 0, smallFaces);

        List<ModelBone> bones = List.of(
                new ModelBone("root", "root", -1, 0, 0, 0, 0, 0, 0, List.of(), ModelBone.Attachment.NONE),
                new ModelBone("big", "big", 0, 0, 0, 0, 0, 0, 0, List.of(left, right),
                        ModelBone.Attachment.NONE),
                new ModelBone("small", "small", 0, 0, 0, 0, 0, 0, 0, List.of(small),
                        ModelBone.Attachment.NONE));
        ModelData model = new ModelData("window-test", 32, 32, bones,
                List.of(new ModelTexture("skin", 32, 32, PngUtil.neutralPlaceholder(32, 32), true)),
                Map.of(), List.of());

        ModelBundle bundle = ModelBundleCompiler.compile(pack, "testns", "big_mob", model);
        check("bundle presentable", bundle.presentable());
        check("oversized bone splits in two", bundle.pieces(1).size() == 2);
        check("in-window bone is one piece", bundle.pieces(2).size() == 1);

        // The split keeps each side inside the window with the smallest shift:
        // the low group needs +6px extra, the high group -6px.
        Vector3f lowOffset = bundle.pieces(1).get(0).offsetPx();
        Vector3f highOffset = bundle.pieces(1).get(1).offsetPx();
        check("low piece extra offset", near(lowOffset.x, 6.0f) && near(lowOffset.y, 0)
                && near(lowOffset.z, 0));
        check("high piece extra offset", near(highOffset.x, -6.0f) && near(highOffset.y, 0)
                && near(highOffset.z, 0));

        // Every emitted element must sit inside the client's element window,
        // and the anchor must place a small cube at +8px with pixel UVs
        // rescaled into the 0..16 item window (16px texture space * 16/32).
        int checked = 0;
        for (Map.Entry<String, byte[]> asset : pack.assets.entrySet()) {
            String path = asset.getKey();
            int colon = path.indexOf(':');
            String relative = colon >= 0 ? path.substring(colon + 1) : path;
            if (!relative.startsWith("models/item/") || !relative.endsWith(".json")) {
                continue;
            }
            JsonObject root = JsonParser.parseString(
                    new String(asset.getValue(), StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement element : root.getAsJsonArray("elements")) {
                for (float[] corner : new float[][]{vec(element.getAsJsonObject().getAsJsonArray("from")),
                        vec(element.getAsJsonObject().getAsJsonArray("to"))}) {
                    check("element coordinate in window (" + path + ")",
                            corner[0] >= -16f - EPS && corner[0] <= 32f + EPS
                                    && corner[1] >= -16f - EPS && corner[1] <= 32f + EPS
                                    && corner[2] >= -16f - EPS && corner[2] <= 32f + EPS);
                }
                checked++;
            }
        }
        check("emitted the expected element count", checked == 3);

        JsonObject smallModel = JsonParser.parseString(new String(
                pack.assets.get("testns:models/item/mob/big_mob/small.json"), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject element = smallModel.getAsJsonArray("elements").get(0).getAsJsonObject();
        float[] from = vec(element.getAsJsonArray("from"));
        check("anchor places small cube at +8px",
                near(from[0], 8.0f) && near(from[1], 8.0f) && near(from[2], 8.0f));
        float[] uv = vec4(element.getAsJsonObject("faces").getAsJsonObject("north")
                .getAsJsonArray("uv"));
        check("pixel uvs rescaled into the item window",
                near(uv[0], 0.0f) && near(uv[1], 0.0f) && near(uv[2], 8.0f) && near(uv[3], 6.0f));

        // The texture shipped beside the model and was not the error placeholder.
        check("texture registered",
                pack.assets.containsKey("testns:textures/item/mob/big_mob/skin.png"));
    }

    private static float[] vec(JsonArray array) {
        return new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat(),
                array.get(2).getAsFloat()};
    }

    private static float[] vec4(JsonArray array) {
        return new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat(),
                array.get(2).getAsFloat(), array.get(3).getAsFloat()};
    }

    /** PackApi stub: captures generated assets, everything else inert. */
    private static final class CapturingPack implements PackApi {

        final Map<String, byte[]> assets = new HashMap<>();

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public void registerItem(PackItemDefinition definition) {
        }

        @Override
        public void registerModel(String namespace, String path, String modelKey) {
        }

        @Override
        public void registerBlock(PackBlockDefinition definition) {
        }

        @Override
        public void registerTexture(String namespace, String path, File file) {
        }

        @Override
        public void registerRawAsset(String namespace, String path, File file) {
        }

        @Override
        public void registerGeneratedAsset(String namespace, String path, byte[] bytes) {
            assets.put(namespace + ":" + path, bytes);
        }

        @Override
        public ItemStack createItem(String namespace, String id) {
            return null;
        }

        @Override
        public PlayerPackState playerPackState(UUID playerId) {
            return PlayerPackState.UNKNOWN;
        }

        @Override
        public boolean deliverPack(Player player) {
            return false;
        }

        @Override
        public CompletableFuture<PackArtifact> recompile() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public PackArtifact currentArtifact() {
            return null;
        }
    }

    // ------------------------------------------------------------------

    private static void check(String name, boolean condition) {
        if (!condition) {
            failures.add(name);
        }
    }

    private static boolean near(float value, float expected) {
        return Math.abs(value - expected) <= EPS;
    }
}
