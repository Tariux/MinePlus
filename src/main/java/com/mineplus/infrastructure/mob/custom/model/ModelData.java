package com.mineplus.infrastructure.mob.custom.model;

import java.util.List;
import java.util.Map;

/**
 * The immutable, normalized result of parsing one model file — the only shape
 * every later stage (pack emission, rig posing, validation) sees.
 *
 * <p><b>Coordinate convention.</b> Everything here is in Blockbench model
 * space: pixels, {@code y} up, the model's visual front is {@code -z}. Cube
 * coordinates are already <em>relative to their bone's pivot</em>; element
 * rotation origins likewise. Parsers do that re-basing so no later stage has
 * to know where a bone's pivot was.</p>
 *
 * <p>This class never touches Bukkit; it is the {@code ModelData} of the
 * architecture (the {@code ModelError} half is {@link #issues()} plus
 * {@link ModelFormatException} for fatal parse failures).</p>
 */
public final class ModelData {

    private final String sourceName;
    private final int textureWidth;
    private final int textureHeight;
    private final List<ModelBone> bones;
    private final List<ModelTexture> textures;
    private final Map<String, ModelAnimation> animations;
    private final List<String> issues;

    public ModelData(String sourceName, int textureWidth, int textureHeight,
                     List<ModelBone> bones, List<ModelTexture> textures,
                     Map<String, ModelAnimation> animations, List<String> issues) {
        this.sourceName = sourceName == null ? "model" : sourceName;
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
        this.bones = List.copyOf(bones);
        this.textures = List.copyOf(textures);
        this.animations = Map.copyOf(animations);
        this.issues = List.copyOf(issues);
    }

    /** The file this was parsed from, for log lines and reports. */
    public String sourceName() {
        return sourceName;
    }

    public int textureWidth() {
        return textureWidth;
    }

    public int textureHeight() {
        return textureHeight;
    }

    /** Bones in preorder (parent before child); {@link ModelBone#parentIndex()} links the tree. */
    public List<ModelBone> bones() {
        return bones;
    }

    public ModelBone bone(int index) {
        return bones.get(index);
    }

    public int boneCount() {
        return bones.size();
    }

    /** Textures by index (cubes reference them by index). */
    public List<ModelTexture> textures() {
        return textures;
    }

    /** Authored clips keyed by lower-case name. */
    public Map<String, ModelAnimation> animations() {
        return animations;
    }

    /** Non-fatal problems found while parsing (unknown keys, skipped data, unparsable values). */
    public List<String> issues() {
        return issues;
    }

    /** True when at least one bone draws geometry. */
    public boolean hasGeometry() {
        for (ModelBone bone : bones) {
            if (!bone.cubes().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** The bone with the given name (case-insensitive), or {@code -1}. */
    public int boneIndex(String name) {
        if (name == null) {
            return -1;
        }
        for (int i = 0; i < bones.size(); i++) {
            if (bones.get(i).name().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }
}
