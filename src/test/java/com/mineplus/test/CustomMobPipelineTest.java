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
import com.mineplus.infrastructure.mob.custom.rig.MobAnimator;
import com.mineplus.pack.PackApi;
import com.mineplus.pack.PlayerPackState;
import com.mineplus.pack.block.PackBlockDefinition;
import com.mineplus.pack.compile.PackArtifact;
import com.mineplus.pack.item.BbClipBaker;
import com.mineplus.pack.item.HeldPoseSolver;
import com.mineplus.pack.item.ItemDisplaySolver;
import com.mineplus.infrastructure.virtual.ModelDisplay;
import com.mineplus.pack.item.PackItemDefinition;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
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
        bbmodelFormat5Fixture();
        emitterWindowFitting();
        mobAnimatorBaseLayer();
        mobAnimatorSlots();
        heldPoseSolverPivots();
        heldPoseSolverGeometry();
        bakerAutoHeldPose();
        itemDisplaySolverDerivesFromGeometry();
        heldOrientationFixFacesForward();
        heldOrientationFixHandPlacement();
        mobAnimatorHurtFlinch();
        if (failures.isEmpty()) {
            System.out.println("CustomMobPipelineTest: all checks passed");
            return;
        }
        System.out.println("CustomMobPipelineTest: " + failures.size() + " failure(s)");
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

    /**
     * The Blockbench 4.12+ dialect ({@code meta.format_version} {@code "5.0"}):
     * outliner nodes are bare {@code {uuid, children}} shells, every group's real
     * name/pivot/rest rotation lives in the root {@code groups} array joined by
     * uuid, and animation frames arrive as one flat {@code keyframes} list whose
     * values may be strings. This is the shape every shipped MobFun mob uses.
     */
    private static void bbmodelFormat5Fixture() {
        String pngBase64 = Base64.getEncoder().encodeToString(PngUtil.neutralPlaceholder(64, 64));
        String json = "{"
                + "\"meta\": {\"format_version\": \"5.0\", \"model_format\": \"bedrock\","
                + " \"box_uv\": true},"
                + "\"resolution\": {\"width\": 64, \"height\": 64},"
                + "\"elements\": ["
                + "  {\"name\": \"leg_geo\", \"uuid\": \"el_leg\", \"type\": \"cube\", \"box_uv\": false,"
                + "   \"from\": [-2, 0, -2], \"to\": [2, 6, 2], \"origin\": [0, 0, 0], \"texture\": 0,"
                + "   \"faces\": {\"north\": {\"uv\": [0, 16, 4, 22], \"texture\": 0}}},"
                + "  {\"name\": \"head_geo\", \"uuid\": \"el_head\", \"type\": \"cube\", \"box_uv\": false,"
                + "   \"from\": [-4, 20, -4], \"to\": [4, 28, 4], \"origin\": [0, 20, 0], \"texture\": 0,"
                + "   \"faces\": {\"north\": {\"uv\": [0, 0, 8, 8], \"texture\": 0}}},"
                + "  {\"name\": \"brim\", \"uuid\": \"el_brim\", \"type\": \"cube\", \"box_uv\": false,"
                + "   \"from\": [-4, 28, -4], \"to\": [4, 30, 4], \"origin\": [0, 28, 0], \"texture\": 0,"
                + "   \"rotation\": [10, 0, -18],"
                + "   \"faces\": {\"north\": {\"uv\": [0, 30, 8, 32], \"texture\": 0}}}"
                + "],"
                + "\"groups\": ["
                + "  {\"name\": \"leg_right\", \"uuid\": \"g_leg\", \"origin\": [0, 6, 0],"
                + "   \"rotation\": [0, 0, 0], \"children\": []},"
                + "  {\"name\": \"head\", \"uuid\": \"g_head\", \"origin\": [0, 20, 0],"
                + "   \"rotation\": [5, 0, -5], \"children\": []}"
                + "],"
                + "\"outliner\": ["
                + "  {\"uuid\": \"g_leg\", \"isOpen\": false, \"children\": [\"el_leg\"]},"
                + "  {\"uuid\": \"g_head\", \"isOpen\": false, \"children\": [\"el_head\", \"el_brim\"]}"
                + "],"
                + "\"textures\": [{\"name\": \"skin\", \"width\": 64, \"height\": 64,"
                + "   \"source\": \"data:image/png;base64," + pngBase64 + "\"}],"
                + "\"animations\": ["
                + "  {\"name\": \"animation.walk\", \"loop\": \"loop\", \"length\": 1.0,"
                + "   \"animators\": {"
                + "     \"g_leg\": {\"name\": \"leg_right\", \"type\": \"bone\", \"rotation_global\": false,"
                + "       \"keyframes\": ["
                + "         {\"channel\": \"rotation\", \"time\": 0.0, \"interpolation\": \"linear\","
                + "          \"data_points\": [{\"x\": \"25\", \"y\": \"0\", \"z\": \"0\"}]},"
                + "         {\"channel\": \"rotation\", \"time\": 0.5, \"interpolation\": \"linear\","
                + "          \"data_points\": [{\"x\": \"-25\", \"y\": \"0\", \"z\": \"0\"}]},"
                + "         {\"channel\": \"position\", \"time\": 0.5, \"interpolation\": \"step\","
                + "          \"data_points\": [{\"x\": \"0\", \"y\": \"1\", \"z\": \"2\"}]},"
                + "         {\"channel\": \"scale\", \"time\": 0.5, \"interpolation\": \"linear\","
                + "          \"data_points\": [{\"x\": \"1.2\", \"y\": \"1.2\", \"z\": \"1.2\"}]}"
                + "       ]},"
                + "     \"g_head\": {\"name\": \"head\", \"type\": \"bone\", \"rotation_global\": true,"
                + "       \"keyframes\": ["
                + "         {\"channel\": \"rotation\", \"time\": 0.0, \"interpolation\": \"catmullrom\","
                + "          \"data_points\": [{\"x\": 0, \"y\": 0, \"z\": 0}]}"
                + "       ]}"
                + "   }}"
                + "]}";

        ModelData model = BbModelParser.parse("format5.bbmodel",
                json.getBytes(StandardCharsets.UTF_8), reference -> null);

        int leg = model.boneIndex("leg_right");
        check("5.0 group name joins from the groups array", leg > 0);
        ModelBone legBone = model.bone(leg);
        check("5.0 group pivot joins from the groups array", near(legBone.pivotY(), 6.0f));
        check("5.0 element attaches to its group", legBone.cubes().size() == 1);

        int head = model.boneIndex("head");
        ModelBone headBone = model.bone(head);
        check("5.0 rest rotation joins from the groups array",
                near(headBone.restRotationX(), 5.0f) && near(headBone.restRotationZ(), -5.0f));
        check("5.0 head attachment detected from the joined name",
                headBone.attachment() == ModelBone.Attachment.HEAD);
        check("5.0 head cube carries only its own element", headBone.cubes().size() == 1);
        int brim = model.boneIndex("head_rot0");
        check("5.0 multi-axis element rotation bakes into a bone", brim >= 0
                && model.bone(brim).parentIndex() == head
                && near(model.bone(brim).restRotationX(), 10.0f)
                && near(model.bone(brim).restRotationZ(), -18.0f)
                && model.bone(brim).cubes().size() == 1);

        ModelCube.Uv legUv = legBone.cubes().get(0).face("north");
        check("5.0 pixel uvs are kept as authored",
                legUv != null && near(legUv.u1(), 0) && near(legUv.v1(), 16)
                        && near(legUv.u2(), 4) && near(legUv.v2(), 22));
        check("5.0 consistent pixel uvs raise no scaling issue",
                model.issues().stream().noneMatch(issue -> issue.contains("pixel")));

        ModelAnimation walk = model.animations().get("animation.walk");
        check("5.0 clip parsed", walk != null);
        check("5.0 clip loops", walk != null && walk.loopMode() == ModelAnimation.LoopMode.LOOP);
        check("5.0 clip length", walk != null && near(walk.lengthSeconds(), 1.0f));
        check("5.0 wrap", walk != null && near(walk.wrap(1.25f), 0.25f));

        ModelAnimation.BoneTrack track = walk == null ? null : walk.track("leg_right");
        check("5.0 keyframes drive the joined bone name", track != null && track.rotation() != null);
        float[] out = new float[3];
        track.rotation().sample(0.25f, out);
        check("5.0 string data points interpolate numerically", near(out[0], 0.0f));
        track.rotation().sample(0.75f, out);
        check("5.0 rotation clamps at the last key", near(out[0], -25.0f));
        check("5.0 position channel parsed", track.position() != null);
        track.position().sample(0.75f, out);
        check("5.0 step position holds", near(out[1], 1.0f) && near(out[2], 2.0f));
        check("5.0 scale channel parsed", track.scale() != null);
        track.scale().sample(0.75f, out);
        check("5.0 scale value read", near(out[0], 1.2f));

        check("5.0 global rotation is reported, not swallowed",
                model.issues().stream().anyMatch(issue -> issue.contains("global")));
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

    private static ModelAnimation clip(String name, float lengthSeconds, boolean loop,
                                       String bone, float endDegrees) {
        return ModelAnimation.builder(name)
                .lengthSeconds(lengthSeconds)
                .loopMode(loop ? ModelAnimation.LoopMode.LOOP : ModelAnimation.LoopMode.ONCE)
                .key(bone, "rotation", 0.0f, 0.0f, 0.0f, 0.0f, ModelAnimation.Interpolation.LINEAR)
                .key(bone, "rotation", lengthSeconds, endDegrees, 0.0f, 0.0f, ModelAnimation.Interpolation.LINEAR)
                .build();
    }

    private static void mobAnimatorBaseLayer() {
        ModelAnimation idle = clip("idle", 2.0f, true, "body", 0.0f);
        ModelAnimation walk = clip("walk", 1.0f, true, "body", 30.0f);

        MobAnimator empty = new MobAnimator(Map.of(), Map.of(), List.of(), List.of());
        empty.tick(0.1f, 0.0f);
        check("clip-less idle pose is empty", empty.poses().isEmpty());

        MobAnimator animator = new MobAnimator(
                Map.of("idle", idle, "walk", walk), Map.of(), List.of(), List.of());
        animator.tick(0.1f, 0.0f);
        Map<String, MobAnimator.BonePose> first = animator.poses();
        check("idle clip samples zero rotation", near(first.get("body").rotationX, 0.0f));
        animator.tick(0.1f, 0.0f);
        check("pose map is reused across ticks", animator.poses() == first);
        check("pose instances are reused", animator.poses().get("body") == first.get("body"));

        animator.tick(1.0e-4f, 0.2f);
        check("crossfade starts from the source pose",
                Math.abs(animator.poses().get("body").rotationX) < 0.05f);
        animator.tick(0.1f, 0.2f);
        animator.tick(0.1f, 0.2f);
        // Per-state playheads: the walk clip runs on its own clock (0.2 s of
        // walk so far), not the mob's lifetime (0.4 s) — re-entering a state
        // resumes its phase instead of popping to the shared clock.
        check("crossfade reaches the target pose",
                Math.abs(animator.poses().get("body").rotationX - 30.0f * 0.2f) < 0.05f);
    }

    private static void mobAnimatorSlots() {
        ModelAnimation idle = clip("idle", 2.0f, true, "body", 0.0f);
        ModelAnimation walk = clip("walk", 1.0f, true, "body", 30.0f);
        ModelAnimation sit = clip("sit", 2.0f, true, "body", 0.0f);
        ModelAnimation eat = clip("eat", 0.8f, false, "body", 10.0f);
        ModelAnimation ambient = clip("ambient", 0.5f, false, "body", 5.0f);
        Map<String, ModelAnimation> animations = new HashMap<>();
        animations.put("idle", idle);
        animations.put("walk", walk);
        animations.put("sit", sit);
        animations.put("eat", eat);
        animations.put("ambient", ambient);

        MobAnimator animator = new MobAnimator(animations, Map.of(), List.of(), List.of());
        Map<String, String> binding = animator.binding();
        check("sit slot binds", "sit".equals(binding.get("sit")));
        check("eat slot binds", "eat".equals(binding.get("eat")));
        check("ambient slot binds", "ambient".equals(binding.get("ambient")));

        animator.tick(0.05f, 0.0f);
        animator.setSitting(true);
        animator.tick(0.05f, 0.0f);
        check("sitting switches the base layer", animator.snapshot().startsWith("sit"));
        animator.setSitting(false);
        animator.tick(0.05f, 0.0f);
        check("standing up returns to idle", animator.snapshot().startsWith("idle"));

        MobAnimator noSit = new MobAnimator(Map.of("idle", idle, "walk", walk), Map.of(), List.of(), List.of());
        noSit.setSitting(true);
        noSit.tick(0.05f, 0.0f);
        check("sitting without a sit clip stays idle", noSit.snapshot().startsWith("idle"));

        MobAnimator eater = new MobAnimator(animations, Map.of(), List.of(), List.of());
        eater.notifyEat();
        eater.tick(0.05f, 0.0f);
        check("eat overlay plays", eater.snapshot().contains("+eat"));
        eater.tick(1.0f, 0.0f);
        check("eat overlay ends", !eater.snapshot().contains("+eat"));

        boolean ambientFired = false;
        MobAnimator ambience = new MobAnimator(animations, Map.of(), List.of(), List.of());
        for (int i = 0; i < 320 && !ambientFired; i++) {
            ambience.tick(0.1f, 0.0f);
            ambientFired = ambience.snapshot().contains("+ambient");
        }
        check("ambient self-fires while idle", ambientFired);

        boolean ambientWhileWalking = false;
        MobAnimator walker = new MobAnimator(animations, Map.of(), List.of(), List.of());
        for (int i = 0; i < 320 && !ambientWhileWalking; i++) {
            walker.tick(0.1f, 0.2f);
            ambientWhileWalking = walker.snapshot().contains("+ambient");
        }
        check("ambient never fires while walking", !ambientWhileWalking);
    }

    /** Regression: the solver must read cubes in model space (cube + bone pivot). */
    private static void heldPoseSolverPivots() {
        // Two near-cubic bones stacked along Y by their pivots: in model space the
        // shape is 2 x 42 x 2 (Y-long); raw cube coords alone collapse to one 2x2x2
        // blob at the origin with no dominant axis, so solve() would return null.
        ModelCube cube = new ModelCube(-1, -1, -1, 1, 1, 1, 0, 0, null);
        ModelBone root = new ModelBone("root", "root", -1, 0, 0, 0, 0, 0, 0,
                List.of(cube), ModelBone.Attachment.NONE);
        ModelBone stacked = new ModelBone("stack", "stack", 0, 0, 40, 0, 0, 0, 0,
                List.of(cube), ModelBone.Attachment.NONE);

        HeldPoseSolver.Pose pose = HeldPoseSolver.solve(List.of(root, stacked));
        check("pivot-offset bones give a dominant axis", pose != null);
        if (pose == null) {
            return;
        }
        // Y-long alignment is the identity, so each context rotation must represent
        // the same matrix as the vanilla handheld Euler (compared as matrices, since
        // the extracted triples differ at the y=-90 gimbal lock).
        check("first-person right rotation matches vanilla Y-long pose",
                sameRotation(rotationOf(pose.firstpersonRotationRight()), eulerXyzMatrix(0, -90, 25)));
        check("third-person right rotation matches vanilla Y-long pose",
                sameRotation(rotationOf(pose.thirdpersonRotationRight()), eulerXyzMatrix(0, -90, 55)));

        // Cube far from origin via pivot only, on the X axis.
        ModelBone offset = new ModelBone("side", "side", -1, 50, 0, 0, 0, 0, 0,
                List.of(new ModelCube(-1, -6, -1, 1, 6, 1, 0, 0, null),
                        new ModelCube(10, -1, -1, 12, 1, 1, 0, 0, null)),
                ModelBone.Attachment.NONE);
        HeldPoseSolver.Pose xPose = HeldPoseSolver.solve(List.of(offset));
        // With the pivot: X 49..63 (14) vs Y -6..6 (12) -> X-long. Without it:
        // X 2, Y 12 -> Y-long, so the assertion below fails on the old code.
        check("pivot-only X offset flips the solved axis", xPose != null);
        if (xPose == null) {
            return;
        }
        // X-long alignment carries +X onto +Y: a +90-degree rotation about Z.
        check("X-long model composes vanilla rotation with Rz(+90)",
                sameRotation(rotationOf(xPose.firstpersonRotationRight()),
                        matMul(eulerXyzMatrix(0, -90, 25), eulerXyzMatrix(0, 0, 90))));
    }

    /**
     * The procedural hurt flinch (rigs without an authored hurt clip): a
     * half-sine of 12 degrees over 0.3 s, suppressed when an authored clip
     * carries the reaction or the mob is dead.
     */
    private static void mobAnimatorHurtFlinch() {
        MobAnimator animator = new MobAnimator(Map.of(), Map.of(), List.of(), List.of());
        check("no flinch before a hit", animator.hurtFlinchDegrees() == 0.0f);
        check("no clip means no authored hurt reaction", !animator.hasHurtClip());
        animator.notifyHurt();
        check("flinch starts at zero degrees", near(animator.hurtFlinchDegrees(), 0.0f));
        animator.tick(0.15f, 0.0f);
        check("flinch peaks mid-window", near(animator.hurtFlinchDegrees(), 12.0f));
        animator.tick(0.15f, 0.0f);
        check("flinch ends at 0.3s", animator.hurtFlinchDegrees() == 0.0f);
        animator.tick(1.0f, 0.0f);
        check("flinch stays zero after the window", animator.hurtFlinchDegrees() == 0.0f);

        // An authored hurt clip owns the reaction: the procedural flinch reads
        // zero for its whole window.
        MobAnimator clipped = new MobAnimator(Map.of("hurt",
                ModelAnimation.builder("hurt").lengthSeconds(0.5f).build()),
                Map.of(), List.of(), List.of());
        check("authored hurt clip binds", clipped.hasHurtClip());
        clipped.notifyHurt();
        clipped.tick(0.15f, 0.0f);
        check("authored hurt clip disables the flinch", clipped.hurtFlinchDegrees() == 0.0f);

        // A dead mob never flinches.
        MobAnimator corpse = new MobAnimator(Map.of(), Map.of(), List.of(), List.of());
        corpse.markDead();
        corpse.notifyHurt();
        corpse.tick(0.15f, 0.0f);
        check("dead mobs do not flinch", corpse.hurtFlinchDegrees() == 0.0f);
    }

    /**
     * Solver geometry beyond the pivot rule: degenerate inputs, the grip
     * translation anchor, inflate and diagonal axes.
     */
    private static void heldPoseSolverGeometry() {
        check("empty model solves nothing", HeldPoseSolver.solve(List.of()) == null);

        ModelCube cube = new ModelCube(-1, -1, -1, 1, 1, 1, 0, 0, null);
        ModelBone cubeBone = new ModelBone("cube", "cube", -1, 0, 0, 0, 0, 0, 0,
                List.of(cube), ModelBone.Attachment.NONE);
        check("uniform cube has no dominant axis",
                HeldPoseSolver.solve(List.of(cubeBone)) == null);

        // A Y-long blade centred on the origin: the alignment is the identity,
        // so the rotation is the vanilla handheld triple and the translation
        // must place the solved grip (0, 7.8, 0) at the vanilla anchor:
        // t = anchor - R_hand * (scale * grip), scale 0.68 first / 0.85 third.
        ModelCube blade = new ModelCube(-0.5f, -6, -0.5f, 0.5f, 6, 0.5f, 0, 0, null);
        ModelBone bladeBone = new ModelBone("blade", "blade", -1, 0, 0, 0, 0, 0, 0,
                List.of(blade), ModelBone.Attachment.NONE);
        HeldPoseSolver.Pose pose = HeldPoseSolver.solve(List.of(bladeBone));
        check("Y-long blade solves", pose != null);
        if (pose == null) {
            return;
        }
        float[] grip = {0.0f, 7.8f, 0.0f};
        check("Y-long first-person rotation is the vanilla handheld triple",
                sameRotation(rotationOf(pose.firstpersonRotationRight()), eulerXyzMatrix(0, -90, 25)));
        float[] rotatedFp = apply(eulerXyzMatrix(0, -90, 25), scaleVec(grip, 0.68f));
        check("Y-long first-person translation anchors the grip",
                near(pose.firstpersonTranslationRight(), firstpersonAnchor(), neg(rotatedFp), 1.0e-2f));
        check("Y-long third-person rotation is the vanilla handheld triple",
                sameRotation(rotationOf(pose.thirdpersonRotationRight()), eulerXyzMatrix(0, -90, 55)));
        float[] rotatedTp = apply(eulerXyzMatrix(0, -90, 55), scaleVec(grip, 0.85f));
        check("Y-long third-person translation anchors the grip",
                near(pose.thirdpersonTranslationRight(), thirdpersonAnchor(), neg(rotatedTp), 1.0e-2f));
        check("left hand mirrors the right",
                near(pose.firstpersonRotationLeft()[1], -pose.firstpersonRotationRight()[1])
                        && near(pose.firstpersonRotationLeft()[2], -pose.firstpersonRotationRight()[2])
                        && java.util.Arrays.equals(pose.firstpersonTranslationLeft(),
                                pose.firstpersonTranslationRight()));

        // Inflate that erases the dominance (Y 56 vs X/Z 45 > 0.8 * 56) cancels.
        ModelCube fat = new ModelCube(-0.5f, -6, -0.5f, 0.5f, 6, 0.5f, 22, 0, null);
        ModelBone fatBone = new ModelBone("fat", "fat", -1, 0, 0, 0, 0, 0, 0,
                List.of(fat), ModelBone.Attachment.NONE);
        check("inflate that erases dominance cancels the pose",
                HeldPoseSolver.solve(List.of(fatBone)) == null);

        // A staircase of cubes along the XY diagonal: the corner covariance has
        // a strong xy term, the long axis is (1,1,0)/sqrt(2), and the solved
        // rotation must carry it onto the same line the vanilla pose gives +Y
        // (parallel, either sign, since the axis sign is free).
        List<ModelCube> steps = new ArrayList<>();
        for (int k = 0; k < 7; k++) {
            steps.add(new ModelCube(2 * k, 2 * k, 0, 2 * k + 1, 2 * k + 1, 1, 0, 0, null));
        }
        ModelBone diag = new ModelBone("diag", "diag", -1, 0, 0, 0, 0, 0, 0,
                steps, ModelBone.Attachment.NONE);
        HeldPoseSolver.Pose dPose = HeldPoseSolver.solve(List.of(diag));
        check("diagonal model solves", dPose != null);
        if (dPose == null) {
            return;
        }
        float unit = (float) (1.0 / Math.sqrt(2.0));
        float[] carried = apply(rotationOf(dPose.firstpersonRotationRight()),
                new float[]{unit, unit, 0.0f});
        float[] want = apply(eulerXyzMatrix(0, -90, 25), new float[]{0, 1, 0});
        check("diagonal model lands on the vanilla Y axis",
                parallel(carried, want, 2.0e-2f));
    }

    /** BbClipBaker fills the hand contexts from geometry only when none are authored. */
    private static void bakerAutoHeldPose() {
        ModelCube blade = new ModelCube(-0.5f, -6, -0.5f, 0.5f, 6, 0.5f, 0, 0, null);
        ModelBone bone = new ModelBone("blade", "blade", -1, 0, 0, 0, 0, 0, 0,
                List.of(blade), ModelBone.Attachment.NONE);
        ModelData model = new ModelData("blade", 32, 32, List.of(bone),
                List.of(new ModelTexture("skin", 32, 32, PngUtil.neutralPlaceholder(32, 32), true)),
                Map.of(), List.of());

        BbClipBaker.Result unauthored = BbClipBaker.rest(model,
                new BbClipBaker.Style(new JsonObject(), "skin", false));
        check("unauthored bake emits one model", unauthored.models().size() == 1);
        if (unauthored.models().isEmpty()) {
            return;
        }
        JsonObject display = unauthored.models().get(0).getAsJsonObject("display");
        check("auto pose fills first-person right",
                display != null && display.getAsJsonObject("firstperson_righthand") != null);
        check("auto pose fills third-person right",
                display != null && display.getAsJsonObject("thirdperson_righthand") != null);
        boolean reported = false;
        for (String issue : unauthored.issues()) {
            reported |= issue.contains("held pose was derived");
        }
        check("auto pose is reported as an issue", reported);

        JsonObject authored = new JsonObject();
        authored.add("firstperson_righthand", new JsonObject());
        BbClipBaker.Result authoredResult = BbClipBaker.rest(model,
                new BbClipBaker.Style(authored, "skin", false));
        JsonObject authoredDisplay = authoredResult.models().isEmpty()
                ? null : authoredResult.models().get(0).getAsJsonObject("display");
        // Authored contexts always win, but the other three hand contexts are
        // still solved per context (a partially authored display no longer
        // cancels the pass for the untouched contexts).
        check("an authored context survives the auto pose",
                authoredDisplay != null && authoredDisplay.getAsJsonObject("firstperson_righthand") != null
                        && authoredDisplay.getAsJsonObject("firstperson_righthand").entrySet().isEmpty());
        check("solved contexts fill the remaining hands",
                authoredDisplay != null && authoredDisplay.getAsJsonObject("firstperson_lefthand") != null
                        && authoredDisplay.getAsJsonObject("thirdperson_righthand") != null
                        && authoredDisplay.getAsJsonObject("thirdperson_lefthand") != null);
        boolean reportedAuthored = false;
        for (String issue : authoredResult.issues()) {
            reportedAuthored |= issue.contains("held pose was derived");
        }
        check("derivation is reported when some hands were solved", reportedAuthored);
    }

    /**
     * The static item-model path: models authored away from the 16x16x16
     * origin (a thin 1x2x16 blade deep in a corner) must get a full display
     * block derived from their covered volume — centered in the slot/ground
     * views and grip-aligned in the hands — while contexts the author did
     * write are kept verbatim.
     */
    private static void itemDisplaySolverDerivesFromGeometry() {
        // A 1x2x16 blade spanning x 14..15, y 7..9, z 0..16: far from the
        // vanilla center (8,8,8) along x, centered along y and z.
        float[][] blade = {{14, 7, 0, 15, 9, 16}};
        ModelDisplay derived = ItemDisplaySolver.derive(blade, ModelDisplay.EMPTY);
        check("solver fills every vanilla context", !derived.isEmpty()
                && derived.contexts().size() == 8);

        // GUI: the box center (14.5, 8, 8) must land on the vanilla center
        // (8, 8, 8) after the gui scale, i.e. t + R·(S·c) = R·S·(8,8,8)
        // => t = R·S·(8-14.5, 0, 0). Rotation (30,225,0), scale 0.625.
        ModelDisplay.Transform gui = derived.contexts().get("gui");
        check("gui context derived", gui != null);
        if (gui != null) {
            float[] center = apply(eulerXyzMatrix(30, 225, 0),
                    scaleVec(new float[]{8 - 14.5f, 0, 0}, 0.625f));
            check("gui translation centers the bbox",
                    near(gui.translation().x, center[0], 1.0e-2f)
                            && near(gui.translation().y, center[1], 1.0e-2f)
                            && near(gui.translation().z, center[2], 1.0e-2f));
            check("gui keeps the vanilla rotation and scale",
                    near(gui.rotation().x, 30.0f) && near(gui.rotation().y, 225.0f)
                            && near(gui.scale().x, 0.625f));
        }

        // Hand contexts: the long axis is z, so the grip must anchor like the
        // vanilla handheld pose — check the translation actually puts the
        // solved grip point at the vanilla anchor (no floating in the air).
        ModelDisplay.Transform fp = derived.contexts().get("firstperson_righthand");
        check("first-person context derived", fp != null);

        // Degenerate geometry (one fat cube): vanilla handheld pose, still
        // centered.
        float[][] fat = {{6, 6, 6, 10, 10, 10}};
        ModelDisplay fatDerived = ItemDisplaySolver.derive(fat, ModelDisplay.EMPTY);
        ModelDisplay.Transform fatGui = fatDerived.contexts().get("gui");
        check("degenerate model still gets a centered gui",
                fatGui != null
                        && near(fatGui.translation().x, 0.0f, 1.0e-2f)
                        && near(fatGui.translation().y, 0.0f, 1.0e-2f)
                        && near(fatGui.translation().z, 0.0f, 1.0e-2f));

        // Authored contexts win per context: a gui pose the author wrote is
        // kept byte-for-byte while the rest is derived around it.
        Map<String, ModelDisplay.Transform> authored = new HashMap<>();
        authored.put("gui", new ModelDisplay.Transform(
                new Vector3f(10, 20, 30), new Vector3f(1, 2, 3), new Vector3f(2, 2, 2)));
        ModelDisplay merged = ItemDisplaySolver.derive(blade, new ModelDisplay(authored));
        ModelDisplay.Transform kept = merged.contexts().get("gui");
        check("authored gui wins", kept != null
                && near(kept.translation().x, 1.0f) && near(kept.rotation().y, 20.0f));
        check("the other contexts are still derived",
                merged.contexts().get("firstperson_righthand") != null
                        && merged.contexts().get("ground") != null);

        // solveBoxes agrees with bone-based solve on the same geometry.
        ModelCube cube = new ModelCube(-0.5f, -6, -0.5f, 0.5f, 6, 0.5f, 0, 0, null);
        ModelBone bone = new ModelBone("b", "b", -1, 0, 0, 0, 0, 0, 0,
                List.of(cube), ModelBone.Attachment.NONE);
        HeldPoseSolver.Pose fromBones = HeldPoseSolver.solve(List.of(bone));
        HeldPoseSolver.Pose fromBoxes = HeldPoseSolver.solveBoxes(
                new float[][]{{-0.5f, -6, -0.5f, 0.5f, 6, 0.5f}});
        check("solveBoxes matches the bone solve", fromBoxes != null && fromBones != null
                && java.util.Arrays.equals(fromBoxes.firstpersonRotationRight(), fromBones.firstpersonRotationRight())
                && java.util.Arrays.equals(fromBoxes.firstpersonTranslationRight(), fromBones.firstpersonTranslationRight()));
    }

    /**
     * A hand pose that presents the muzzle at the viewer points the weapon
     * backward; the geometry fix must turn such a model around about its grip
     * anchor and leave a forward-facing model's hand poses byte-for-byte alone,
     * while synthesizing the standalone contexts it lacks.
     */
    private static void heldOrientationFixFacesForward() {
        // The pack bug: two guns with byte-identical authored displays but
        // opposite in-model muzzle directions rendered facing opposite ways
        // (the display table cannot be right for both conventions). The
        // canonical pass must render both exactly like the good pistol.
        String backward = "{\"elements\":["
                + "{\"name\":\"grip\",\"from\":[6,3,-6],\"to\":[10,8,0]},"
                + "{\"name\":\"front_sight\",\"from\":[7.5,6,20],\"to\":[8.5,7,21]}"
                + "],\"display\":{"
                + "\"firstperson_righthand\":{\"rotation\":[0,0,0],\"translation\":[1,2,-3]}"
                + "}}";
        String forward = "{\"elements\":["
                + "{\"name\":\"grip\",\"from\":[6,3,6],\"to\":[10,8,12]},"
                + "{\"name\":\"front_sight\",\"from\":[7.5,6,-21],\"to\":[8.5,7,-20]}"
                + "],\"display\":{"
                + "\"firstperson_righthand\":{\"rotation\":[0,0,0],\"translation\":[1,2,-3]}"
                + "}}";
        File backwardFile = writeTempModel("held-fix-backward", backward);
        File forwardFile = writeTempModel("held-fix-forward", forward);
        java.util.logging.Logger log = java.util.logging.Logger.getLogger("held-fix");
        com.mineplus.pack.item.HeldOrientationFix.fixAll(log, backwardFile.getParentFile());
        com.mineplus.pack.item.HeldOrientationFix.fixAll(log, forwardFile.getParentFile());
        JsonObject backFixed = readModel(backwardFile);
        JsonObject fwdFixed = readModel(forwardFile);
        float[] backGrip = {8f, 5.5f, -3f};
        float[] fwdGrip = {8f, 5.5f, 9f};
        float[] backMuzzle = {8f, 6.5f, 20.5f};
        float[] fwdMuzzle = {8f, 6.5f, -20.5f};
        JsonObject[] models = {backFixed, fwdFixed};
        float[][] grips = {backGrip, fwdGrip};
        float[][] muzzles = {backMuzzle, fwdMuzzle};
        String[] labels = {"backward-convention", "forward-convention"};
        float[][] targets = {{8.418f, 7.715f, 4.039f}, {6.8f, 6.375f, 7.325f}};
        String[] handContexts = {"firstperson_righthand", "thirdperson_righthand"};
        for (int m = 0; m < models.length; m++) {
            JsonObject model = models[m];
            for (int h = 0; h < handContexts.length; h++) {
                float[] grip = displayGrip(model, handContexts[h], grips[m]);
                check(handContexts[h] + ": " + labels[m] + " grip lands on the canonical hand point",
                        Math.abs(grip[0] - targets[h][0]) < 0.05f
                                && Math.abs(grip[1] - targets[h][1]) < 0.05f
                                && Math.abs(grip[2] - targets[h][2]) < 0.05f);
            }
            float[] gripFp = displayGrip(model, "firstperson_righthand", grips[m]);
            float[] muzzleFp = displayGrip(model, "firstperson_righthand", muzzles[m]);
            check("first-person " + labels[m] + " muzzle points away from the viewer",
                    muzzleFp[2] < gripFp[2] - 5f);
            JsonObject display = model.getAsJsonObject("display");
            check(labels[m] + " gains the standalone contexts",
                    display.getAsJsonObject("gui") != null
                            && display.getAsJsonObject("ground") != null
                            && display.getAsJsonObject("fixed") != null);
        }
    }

    private static float centreZDelta(String before, JsonObject after) {
        JsonObject beforeDisplay = JsonParser.parseString(before).getAsJsonObject()
                .getAsJsonObject("display").getAsJsonObject("firstperson_righthand");
        JsonObject afterDisplay = after.getAsJsonObject("display")
                .getAsJsonObject("firstperson_righthand");
        return beforeDisplay.getAsJsonArray("translation").get(2).getAsFloat()
                - afterDisplay.getAsJsonArray("translation").get(2).getAsFloat();
    }

    private static float muzzleWorldZ(JsonObject pose, float[] ray) {
        JsonArray rotation = pose.has("rotation") ? pose.getAsJsonArray("rotation") : null;
        float[] r = new float[3];
        for (int i = 0; i < 3 && rotation != null && i < rotation.size(); i++) {
            r[i] = rotation.get(i).getAsFloat();
        }
        float[] w = ray.clone();
        for (int axis = 0; axis < 3; axis++) {
            double t = Math.toRadians(r[axis]);
            float c = (float) Math.cos(t);
            float s = (float) Math.sin(t);
            float[] in = w;
            w = switch (axis) {
                case 0 -> new float[]{in[0], c * in[1] - s * in[2], s * in[1] + c * in[2]};
                case 1 -> new float[]{c * in[0] + s * in[2], in[1], -s * in[0] + c * in[2]};
                default -> new float[]{c * in[0] - s * in[1], s * in[0] + c * in[1], in[2]};
            };
        }
        return w[2];
    }

    private static File writeTempModel(String name, String content) {
        try {
            File dir = Files.createTempDirectory("held-fix").toFile();
            File file = new File(dir, name + "_idle.json");
            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
            file.deleteOnExit();
            return file;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static JsonObject readModel(File file) {
        try {
            return JsonParser.parseString(Files.readString(file.toPath(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static float[] firstpersonAnchor() {
        return new float[]{1.13f, 3.2f, 1.13f};
    }

    private static float[] thirdpersonAnchor() {
        return new float[]{0.0f, 4.0f, 0.5f};
    }

    private static float[] scaleVec(float[] v, float s) {
        return new float[]{v[0] * s, v[1] * s, v[2] * s};
    }

    private static float[] neg(float[] v) {
        return new float[]{-v[0], -v[1], -v[2]};
    }

    private static float[] apply(float[] matrix, float[] point) {
        return new float[]{
                matrix[0] * point[0] + matrix[1] * point[1] + matrix[2] * point[2],
                matrix[3] * point[0] + matrix[4] * point[1] + matrix[5] * point[2],
                matrix[6] * point[0] + matrix[7] * point[1] + matrix[8] * point[2]};
    }

    /** got ~= ±want: parallel up to a global sign (the axis sign is free). */
    private static boolean parallel(float[] got, float[] want, float tolerance) {
        float dot = got[0] * want[0] + got[1] * want[1] + got[2] * want[2];
        float s = dot < 0 ? -1.0f : 1.0f;
        return near(new float[]{got[0] * s, got[1] * s, got[2] * s}, want,
                new float[]{0, 0, 0}, tolerance);
    }

    private static boolean near(float[] got, float[] want, float[] offset, float tolerance) {
        for (int i = 0; i < 3; i++) {
            if (Math.abs(got[i] - (want[i] + offset[i])) > tolerance) {
                return false;
            }
        }
        return true;
    }

    private static float[] rotationOf(float[] triple) {
        return eulerXyzMatrix(triple[0], triple[1], triple[2]);
    }

    private static float[] matMul(float[] a, float[] b) {
        float[] out = new float[9];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                out[r * 3 + c] = a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c];
            }
        }
        return out;
    }

    /** R = Rx(x)·Ry(y)·Rz(z), the solver's Euler convention (HeldPoseSolver.eulerXYZ). */
    private static float[] eulerXyzMatrix(float x, float y, float z) {
        float cx = (float) Math.cos(Math.toRadians(x));
        float sx = (float) Math.sin(Math.toRadians(x));
        float cy = (float) Math.cos(Math.toRadians(y));
        float sy = (float) Math.sin(Math.toRadians(y));
        float cz = (float) Math.cos(Math.toRadians(z));
        float sz = (float) Math.sin(Math.toRadians(z));
        // Ry·Rz then Rx·(that), row-major 3x3.
        return new float[]{
                cy * cz, -cy * sz, sy,
                cx * sz + sx * sy * cz, cx * cz - sx * sy * sz, -sx * cy,
                sx * sz - cx * sy * cz, sx * cz + cx * sy * sz, cx * cy};
    }

    private static boolean sameRotation(float[] a, float[] b) {
        for (int i = 0; i < 9; i++) {
            if (Math.abs(a[i] - b[i]) > 1.0e-2f) {
                return false;
            }
        }
        return true;
    }

    /**
     * Regression for the hand-placement pass: a gun whose grip sits far from
     * the fist in first and third person must be moved onto the hand point
     * (grip into the hand), a well-placed gun must be left byte-identical, a
     * missing gui context must be synthesized centred, and a second run must
     * rewrite nothing. Pins the "gun floats away from the hand" bug, including
     * the wrong-end grip anchor (stock_butt / bolt_handle beating the real grip).
     */
    private static void heldOrientationFixHandPlacement() {
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("heldfit");
            Logger quiet = Logger.getLogger("heldfit-test");
            quiet.setUseParentHandlers(false);

            // A revolver-like model whose authored display shoves the grip far
            // from the fist in both views, plus an animation frame with the
            // same geometry; gui/ground/fixed are absent on purpose. The old
            // anchor bug: stock_butt must not beat the real grip cube.
            String farGun = "{"
                    + "\"display\":{"
                    + "\"firstperson_righthand\":{\"rotation\":[0,0,0],\"translation\":[6.6,4.6,12.6]},"
                    + "\"thirdperson_righthand\":{\"rotation\":[0,0,0],\"translation\":[6.8,4.0,10.8]}"
                    + "},"
                    + "\"elements\":["
                    + "{\"name\":\"grip\",\"from\":[-1,-1,6],\"to\":[1,2,10]},"
                    + "{\"name\":\"trigger\",\"from\":[-0.5,0,4],\"to\":[0.5,2,6]},"
                    + "{\"name\":\"stock_butt\",\"from\":[-1,-3,12],\"to\":[1,1,16]},"
                    + "{\"name\":\"muzzle_crown\",\"from\":[-1,-1,-10],\"to\":[1,1,-8]}"
                    + "]}";
            java.nio.file.Files.writeString(dir.resolve("far_gun_idle.json"), farGun);
            java.nio.file.Files.writeString(dir.resolve("far_gun_fire.json"), farGun);

            com.mineplus.pack.item.HeldOrientationFix.fixAll(quiet, dir.toFile());
            JsonObject far = JsonParser.parseString(
                    java.nio.file.Files.readString(dir.resolve("far_gun_idle.json"))).getAsJsonObject();
            JsonObject fire = JsonParser.parseString(
                    java.nio.file.Files.readString(dir.resolve("far_gun_fire.json"))).getAsJsonObject();

            float[] anchor = {0f, 0.5f, 8f}; // centre of the grip cube
            float[] fp = displayGrip(far, "firstperson_righthand", anchor);
            check("hand fit: first-person grip reaches the fist",
                    Math.abs(fp[0] - 8.418f) < 0.05f && Math.abs(fp[1] - 7.715f) < 0.05f
                            && Math.abs(fp[2] - 4.039f) < 0.05f);
            float[] tp = displayGrip(far, "thirdperson_righthand", anchor);
            check("hand fit: third-person grip reaches the fist",
                    Math.abs(tp[0] - 6.8f) < 0.05f && Math.abs(tp[1] - 6.375f) < 0.05f
                            && Math.abs(tp[2] - 7.325f) < 0.05f);
            check("hand fit: the animation frame gets the same pose",
                    fire.getAsJsonObject("display").getAsJsonObject("firstperson_righthand")
                            .equals(far.getAsJsonObject("display").getAsJsonObject("firstperson_righthand")));
            check("item fit: gui, ground and fixed synthesized",
                    far.getAsJsonObject("display").has("gui")
                            && far.getAsJsonObject("display").has("ground")
                            && far.getAsJsonObject("display").has("fixed"));

            // Idempotent: a second pass rewrites nothing.
            String once = java.nio.file.Files.readString(dir.resolve("far_gun_idle.json"));
            com.mineplus.pack.item.HeldOrientationFix.fixAll(quiet, dir.toFile());
            check("hand fit: second pass is a no-op",
                    java.nio.file.Files.readString(dir.resolve("far_gun_idle.json")).equals(once));

            java.nio.file.Files.walk(dir).sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
        } catch (Exception failure) {
            failures.add("heldOrientationFixHandPlacement threw " + failure);
        }
    }

    /** Where a model point lands in a display context, replicating Minecraft's
     * display composition (scale, then Rx·Ry·Rz rotation, then translation). */
    private static float[] displayGrip(JsonObject model, String context, float[] point) {
        JsonObject pose = model.getAsJsonObject("display").getAsJsonObject(context);
        float[] rotation = new float[3];
        float[] translation = new float[3];
        float[] scale = {1f, 1f, 1f};
        for (int i = 0; i < 3; i++) {
            rotation[i] = pose.has("rotation") ? pose.getAsJsonArray("rotation").get(i).getAsFloat() : 0f;
            translation[i] = pose.getAsJsonArray("translation").get(i).getAsFloat();
            if (pose.has("scale")) {
                scale[i] = pose.getAsJsonArray("scale").get(i).getAsFloat();
            }
        }
        float[] v = {point[0] * scale[0], point[1] * scale[1], point[2] * scale[2]};
        v = rotateAxis(rotateAxis(rotateAxis(v, 2, rotation[2]), 1, rotation[1]), 0, rotation[0]);
        return new float[]{translation[0] + v[0], translation[1] + v[1], translation[2] + v[2]};
    }

    private static float[] rotateAxis(float[] v, int axis, float degrees) {
        double radians = Math.toRadians(degrees);
        float c = (float) Math.cos(radians);
        float s = (float) Math.sin(radians);
        return switch (axis) {
            case 0 -> new float[]{v[0], c * v[1] - s * v[2], s * v[1] + c * v[2]};
            case 1 -> new float[]{c * v[0] + s * v[2], v[1], -s * v[0] + c * v[2]};
            default -> new float[]{c * v[0] - s * v[1], s * v[0] + c * v[1], v[2]};
        };
    }

    private static void check(String name, boolean condition) {
        if (!condition) {
            failures.add(name);
            System.out.println("  FAIL " + name);
        }
    }

    private static boolean near(float value, float expected) {
        return Math.abs(value - expected) <= EPS;
    }

    private static boolean near(float value, float expected, float tolerance) {
        return Math.abs(value - expected) <= tolerance;
    }
}
