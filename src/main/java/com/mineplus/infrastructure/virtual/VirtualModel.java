package com.mineplus.infrastructure.virtual;

import com.mineplus.infrastructure.virtual.animation.AnimationClip;
import com.mineplus.infrastructure.virtual.animation.VirtualBone;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One imported bbmodel ready for virtual rendering: baked cubes, texture table,
 * resolution, anchors, and the animation bone graph.
 *
 * <p>Immutable value type. The bone-name index is precomputed at construction so
 * the animation hot loop resolves bones with one map lookup instead of scanning
 * (and copying) the bone list on every call.</p>
 */
public final class VirtualModel {

    private final String name;
    private final List<BakedCube> cubes;
    private final Map<String, String> textureMappings;
    private final Resolution resolution;
    private final String modelFormat;
    private final List<VectorAnchor> anchors;
    private final List<VirtualBone> bones;
    private final List<AnimationClip> animations;
    /** Lowercased bone name -> preorder index; the first occurrence wins (import order). */
    private final Map<String, Integer> boneIndexByName;

    public VirtualModel(
            String name,
            List<BakedCube> cubes,
            Map<String, String> textureMappings,
            Resolution resolution,
            String modelFormat,
            List<VectorAnchor> anchors,
            List<VirtualBone> bones,
            List<AnimationClip> animations
    ) {
        this.name = name;
        this.cubes = List.copyOf(cubes);
        this.textureMappings = textureMappings == null ? Map.of() : Map.copyOf(textureMappings);
        this.resolution = resolution == null ? new Resolution(16, 16) : resolution;
        this.modelFormat = modelFormat == null || modelFormat.isBlank() ? null : modelFormat;
        this.anchors = anchors == null ? List.of() : List.copyOf(anchors);
        this.bones = bones == null ? List.of() : List.copyOf(bones);
        this.animations = animations == null ? List.of() : List.copyOf(animations);
        Map<String, Integer> boneIndex = new HashMap<>();
        for (int i = 0; i < this.bones.size(); i++) {
            VirtualBone bone = this.bones.get(i);
            if (bone != null && bone.name() != null) {
                String key = bone.name().trim().toLowerCase(Locale.ROOT);
                if (!key.isEmpty()) {
                    boneIndex.putIfAbsent(key, i);
                }
            }
        }
        this.boneIndexByName = Map.copyOf(boneIndex);
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
     * The same model under a different name. Used by the model registry: the
     * registry key is the single identity every auxiliary lookup (meta overrides,
     * texel bakes, occupancy cache) keys on, so a model registered under a key
     * that differs from its internal name is renamed to the key.
     *
     * @param newName the new model name
     * @return a model identical to this one but named {@code newName}, or this
     *         instance when the name already matches
     */
    public VirtualModel withName(String newName) {
        if (newName == null || newName.equals(name)) {
            return this;
        }
        return new VirtualModel(newName, cubes, textureMappings, resolution, modelFormat, anchors, bones, animations);
    }

    public String name() {
        return name;
    }

    public List<BakedCube> cubes() {
        return cubes;
    }

    public Map<String, String> textureMappings() {
        return textureMappings;
    }

    public Resolution resolution() {
        return resolution;
    }

    public String modelFormat() {
        return modelFormat;
    }

    public List<VectorAnchor> anchors() {
        return anchors;
    }

    public List<VirtualBone> bones() {
        return bones;
    }

    public List<AnimationClip> animations() {
        return animations;
    }

    /**
     * Distinct texture names referenced by this model, in deterministic order.
     * Drives the per-model texture-resolution report.
     */
    public Set<String> textureNames() {
        if (textureMappings.isEmpty()) {
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
        if (name == null || name.isBlank() || boneIndexByName.isEmpty()) {
            return -1;
        }
        Integer index = boneIndexByName.get(name.trim().toLowerCase(Locale.ROOT));
        return index != null ? index : -1;
    }

    /** Texture resolution in pixels (bbmodel {@code resolution.width/height}), default 16x16. */
    public record Resolution(int width, int height) {
        public Resolution {
            width = Math.max(1, width);
            height = Math.max(1, height);
        }
    }
}
