package com.mineplus.infrastructure.virtual;

import com.mineplus.infrastructure.virtual.animation.AnimationClip;
import com.mineplus.infrastructure.virtual.animation.VirtualBone;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record VirtualModel(
        String name,
        List<BakedCube> cubes,
        Map<String, String> textureMappings,
        Resolution resolution,
        String modelFormat,
        List<VectorAnchor> anchors,
        List<VirtualBone> bones,
        List<AnimationClip> animations
) {

    public VirtualModel {
        cubes = List.copyOf(cubes);
        textureMappings = textureMappings == null ? Map.of() : Map.copyOf(textureMappings);
        resolution = resolution == null ? new Resolution(16, 16) : resolution;
        modelFormat = modelFormat == null || modelFormat.isBlank() ? null : modelFormat;
        anchors = anchors == null ? List.of() : List.copyOf(anchors);
        bones = bones == null ? List.of() : List.copyOf(bones);
        animations = animations == null ? List.of() : List.copyOf(animations);
    }

    public VirtualModel(String name, List<BakedCube> cubes, Map<String, String> textureMappings) {
        this(name, cubes, textureMappings, null, null, null, null, null);
    }

    public VirtualModel(String name, List<BakedCube> cubes, Map<String, String> textureMappings,
                        Resolution resolution, String modelFormat) {
        this(name, cubes, textureMappings, resolution, modelFormat, null, null, null);
    }

    public VirtualModel(String name, List<BakedCube> cubes, Map<String, String> textureMappings,
                        Resolution resolution, String modelFormat, List<VectorAnchor> anchors) {
        this(name, cubes, textureMappings, resolution, modelFormat, anchors, null, null);
    }

    /**
     * Distinct texture names referenced by this model, in deterministic order.
     * Drives the per-model texture-resolution report.
     */
    public Set<String> textureNames() {
        if (textureMappings == null || textureMappings.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(textureMappings.values());
    }

    public boolean hasAnimations() {
        return !animations.isEmpty() && !bones.isEmpty();
    }

    /** Clip by name (case-insensitive), or {@code null}. */
    public AnimationClip animation(String name) {
        if (name == null || name.isBlank() || animations.isEmpty()) {
            return null;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        for (AnimationClip clip : animations) {
            if (clip.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return clip;
            }
        }
        return null;
    }

    /** Bone index by name (case-insensitive), or {@code -1}. Bones are in preorder. */
    public int boneIndex(String name) {
        if (name == null || name.isBlank() || bones.isEmpty()) {
            return -1;
        }
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        List<VirtualBone> list = new ArrayList<>(bones);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return i;
            }
        }
        return -1;
    }

    /** Texture resolution in pixels (bbmodel {@code resolution.width/height}), default 16x16. */
    public record Resolution(int width, int height) {
        public Resolution {
            width = Math.max(1, width);
            height = Math.max(1, height);
        }
    }
}
