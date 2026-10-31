package com.mineplus.infrastructure.mob.custom;

import com.mineplus.infrastructure.mob.custom.model.ModelAnimation;
import com.mineplus.infrastructure.mob.custom.model.ModelBone;
import com.mineplus.infrastructure.mob.custom.model.ModelCube;
import com.mineplus.infrastructure.mob.custom.model.ModelData;
import com.mineplus.infrastructure.mob.custom.model.ModelTexture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a legacy {@link CustomMobModel} (hand-authored
 * {@link MobPart}/{@link MobBox} parts) into the new {@link ModelData} shape,
 * so the deprecated construction path rides the same pipeline as imported
 * files.
 *
 * <p>The legacy parts are in vanilla entity-model space ({@code y} down from
 * {@code modelHeightPx}); the conversion flips to y-up and negates the x/z
 * rest-rotation components — exactly what the old renderer did internally.</p>
 */
public final class LegacyModelAdapter {

    private LegacyModelAdapter() {
    }

    public static ModelData adapt(String sourceName, CustomMobModel model) {
        List<String> issues = new ArrayList<>();
        issues.add(sourceName + ": hand-authored parts use the deprecated custom-mob model API;"
                + " prefer a .bbmodel or Java JSON file.");

        int heightPx = model.modelHeightPx();
        List<ModelPartRef> refs = new ArrayList<>();
        List<MobPart> parts = model.parts();
        for (int i = 0; i < parts.size(); i++) {
            MobPart part = parts.get(i);
            float pivotY = heightPx - part.pivotY();
            refs.add(new ModelPartRef(i, part, part.parent(),
                    part.pivotX(), pivotY, part.pivotZ(),
                    -part.rotationX(), part.rotationY(), -part.rotationZ()));
        }

        List<ModelBone> bones = new ArrayList<>(parts.size());
        Map<Integer, List<ModelCube>> cubesByBone = new LinkedHashMap<>();
        for (ModelPartRef ref : refs) {
            List<ModelCube> cubes = new ArrayList<>();
            for (MobBox box : ref.part().boxes()) {
                // Pivot-relative y-down box → pivot-relative y-up cube.
                float x1 = box.fromX();
                float x2 = box.toX();
                float y1 = -box.toY();
                float y2 = -box.fromY();
                float z1 = box.fromZ();
                float z2 = box.toZ();
                Map<String, ModelCube.Uv> faces = new LinkedHashMap<>();
                if (box.hasFaceUvs()) {
                    MobFaceUvs uv = box.faceUvs();
                    copyFace(faces, "north", uv.north());
                    copyFace(faces, "south", uv.south());
                    copyFace(faces, "east", uv.east());
                    copyFace(faces, "west", uv.west());
                    copyFace(faces, "up", uv.up());
                    copyFace(faces, "down", uv.down());
                } else {
                    float u = box.textureU();
                    float v = box.textureV();
                    float w = box.width();
                    float h = box.height();
                    float d = box.depth();
                    faces.put("west", new ModelCube.Uv(u, v + d, u + d, v + d + h));
                    faces.put("north", new ModelCube.Uv(u + d, v + d, u + d + w, v + d + h));
                    faces.put("east", new ModelCube.Uv(u + d + w, v + d, u + d + w + d, v + d + h));
                    faces.put("south", new ModelCube.Uv(u + d + w + d, v + d, u + d + w + d + w, v + d + h));
                    faces.put("up", new ModelCube.Uv(u + d, v, u + d + w, v + d));
                    faces.put("down", new ModelCube.Uv(u + d + w, v + d, u + d + w + w, v + 2 * d));
                }
                cubes.add(new ModelCube(x1, y1, z1, x2, y2, z2, 0.0f, 0, faces));
            }
            cubesByBone.put(ref.index(), cubes);
        }

        for (ModelPartRef ref : refs) {
            MobPart part = ref.part();
            ModelBone.Attachment attachment = switch (part.role()) {
                case HEAD -> ModelBone.Attachment.HEAD;
                default -> ModelBone.detectAttachment(part.name());
            };
            bones.add(new ModelBone(part.name(), part.name(), ref.parentIndex(),
                    ref.pivotX(), ref.pivotY(), ref.pivotZ(),
                    ref.rotationX(), ref.rotationY(), ref.rotationZ(),
                    cubesByBone.get(ref.index()), attachment));
        }

        List<ModelTexture> textures = List.of(new ModelTexture(model.textureName(),
                model.textureWidth(), model.textureHeight(), model.texturePng(), true));

        Map<String, ModelAnimation> animations = new LinkedHashMap<>();
        for (MobAnimation legacy : model.animations()) {
            animations.put(legacy.name().toLowerCase(java.util.Locale.ROOT),
                    convertAnimation(legacy));
        }

        return new ModelData(sourceName, model.textureWidth(), model.textureHeight(),
                bones, textures, animations, issues);
    }

    private static void copyFace(Map<String, ModelCube.Uv> faces, String direction, MobFaceUvs.Uv uv) {
        if (uv != null) {
            faces.put(direction, new ModelCube.Uv(uv.u1(), uv.v1(), uv.u2(), uv.v2()));
        }
    }

    private static ModelAnimation convertAnimation(MobAnimation legacy) {
        ModelAnimation.Builder builder = ModelAnimation.builder(legacy.name());
        builder.lengthSeconds(legacy.lengthSeconds());
        builder.loopMode(legacy.loop() ? ModelAnimation.LoopMode.LOOP : ModelAnimation.LoopMode.ONCE);
        for (String partName : legacy.trackNames()) {
            MobAnimation.Track track = legacy.track(partName);
            if (track == null) {
                continue;
            }
            exportCurve(partName, "rotation", track.rotation(), builder);
            exportCurve(partName, "position", track.position(), builder);
        }
        return builder.build();
    }

    private static void exportCurve(String partName, String channel, MobAnimation.Curve curve,
                                    ModelAnimation.Builder builder) {
        if (curve == null) {
            return;
        }
        float[] value = new float[3];
        for (int key = 0; key < curve.keyCount(); key++) {
            curve.key(key, value);
            builder.key(partName, channel, curve.keyTime(key), value[0], value[1], value[2],
                    curve.isStep() ? ModelAnimation.Interpolation.STEP : ModelAnimation.Interpolation.LINEAR);
        }
    }

    private record ModelPartRef(int index, MobPart part, int parentIndex,
                                float pivotX, float pivotY, float pivotZ,
                                float rotationX, float rotationY, float rotationZ) {
    }
}
