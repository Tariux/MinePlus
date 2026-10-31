package com.mineplus.infrastructure.mob.custom.model;

import java.util.List;
import java.util.Locale;

/**
 * One render bone of a parsed model: a pivot in model pixels (y-up), an
 * optional rest rotation and the cubes drawn relative to that pivot.
 *
 * <p>Bones form a tree through {@link #parentIndex()} (-1 for a root); a
 * {@link ModelData} stores them in preorder. Cubes are stored in bone-local
 * space (pivot-relative, y-up, front = {@code -z}) and re-based at parse time.</p>
 */
public final class ModelBone {

    /** What the rig does with this bone beyond its clip curves. */
    public enum Attachment {
        NONE,
        /** A bone the rig points at the base entity's head look direction. */
        HEAD,
        /** A locator/null-object the rig hangs an item on (right hand side). */
        HAND_R,
        /** A locator/null-object the rig hangs an item on (left hand side). */
        HAND_L,
        /** Any other locator / null object / item mount point. */
        ITEM
    }

    private final String name;
    private final String rawName;
    private final int parentIndex;
    private final float pivotX;
    private final float pivotY;
    private final float pivotZ;
    private final float restRotationX;
    private final float restRotationY;
    private final float restRotationZ;
    private final List<ModelCube> cubes;
    private final Attachment attachment;

    public ModelBone(String name, String rawName, int parentIndex,
                     float pivotX, float pivotY, float pivotZ,
                     float restRotationX, float restRotationY, float restRotationZ,
                     List<ModelCube> cubes, Attachment attachment) {
        this.name = name;
        this.rawName = rawName == null ? name : rawName;
        this.parentIndex = parentIndex;
        this.pivotX = pivotX;
        this.pivotY = pivotY;
        this.pivotZ = pivotZ;
        this.restRotationX = restRotationX;
        this.restRotationY = restRotationY;
        this.restRotationZ = restRotationZ;
        this.cubes = List.copyOf(cubes);
        this.attachment = attachment == null ? Attachment.NONE : attachment;
    }

    /** Pack-safe bone name ({@code [a-z0-9_]}), unique within the model. */
    public String name() {
        return name;
    }

    /** The authored name, for diagnostics and clip binding. */
    public String rawName() {
        return rawName;
    }

    public int parentIndex() {
        return parentIndex;
    }

    public float pivotX() {
        return pivotX;
    }

    public float pivotY() {
        return pivotY;
    }

    public float pivotZ() {
        return pivotZ;
    }

    public float restRotationX() {
        return restRotationX;
    }

    public float restRotationY() {
        return restRotationY;
    }

    public float restRotationZ() {
        return restRotationZ;
    }

    /** Cubes in bone-local model pixels (pivot-relative, y-up). */
    public List<ModelCube> cubes() {
        return cubes;
    }

    public Attachment attachment() {
        return attachment;
    }

    /** True when this bone draws nothing itself (pure structural bone / locator). */
    public boolean isLocator() {
        return cubes.isEmpty();
    }

    /** Attachment role detected from the authored name. */
    public static Attachment detectAttachment(String rawName) {
        String name = rawName == null ? "" : rawName.toLowerCase(Locale.ROOT).replace(' ', '_');
        if (name.equals("head") || name.endsWith("_head") || name.startsWith("head_")) {
            return Attachment.HEAD;
        }
        if (name.contains("hand_item_r") || name.contains("item_hand_r") || name.contains("weapon_r")) {
            return Attachment.HAND_R;
        }
        if (name.contains("hand_item_l") || name.contains("item_hand_l") || name.contains("weapon_l")) {
            return Attachment.HAND_L;
        }
        if (name.equals("weapon") || name.equals("item") || name.startsWith("item_")
                || name.endsWith("_item") || name.contains("weapon")) {
            return Attachment.ITEM;
        }
        return Attachment.NONE;
    }

    /** Pack-safe name derived from an authored bone name. */
    public static String sanitizeName(String rawName, int fallbackIndex) {
        String normalized = rawName == null ? "" : rawName.trim().toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            out.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' ? c : '_');
        }
        String result = out.length() == 0 ? "bone_" + fallbackIndex : out.toString();
        if (Character.isDigit(result.charAt(0))) {
            result = "bone_" + result;
        }
        return result;
    }
}
