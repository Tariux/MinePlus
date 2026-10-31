package com.mineplus.infrastructure.mob.custom;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * One independently rendered piece of a custom mob (a head, a body, a leg),
 * with a pivot, an optional base rotation in vanilla entity-model space and an
 * optional parent part.
 *
 * <p>Coordinates are pixels, {@code y} grows downward from
 * {@code modelHeightPx}, and the model front is {@code -z}. Each part becomes
 * one {@link org.bukkit.entity.ItemDisplay} driven by the Core's rig renderer,
 * so parts animate independently: the role tells the renderer which vanilla
 * pose (head tracking, quadruped leg swing) applies, and an animation clip
 * drives the parts it names.</p>
 *
 * <p>A part's pivot is absolute, never relative to its parent; {@link #parent}
 * only says which part's rotation carries it. That is the same shape an
 * imported outliner has (each group names an absolute origin), so a model's
 * group tree becomes a part tree without re-basing any coordinate. A part with
 * no boxes is a pure bone: it renders nothing but still moves its children.</p>
 */
public final class MobPart {

    /** How the rig animates this part. */
    public enum Role {
        HEAD,
        BODY,
        LEG_FRONT_LEFT,
        LEG_FRONT_RIGHT,
        LEG_BACK_LEFT,
        LEG_BACK_RIGHT,
        STATIC
    }

    private final String name;
    private final Role role;
    private final int parent;
    private final float pivotX;
    private final float pivotY;
    private final float pivotZ;
    private final float rotationX;
    private final float rotationY;
    private final float rotationZ;
    private final List<MobBox> boxes;

    private MobPart(String name, Role role, int parent, float pivotX, float pivotY, float pivotZ,
                    float rotationX, float rotationY, float rotationZ, List<MobBox> boxes) {
        this.name = name;
        this.role = role;
        this.parent = parent;
        this.pivotX = pivotX;
        this.pivotY = pivotY;
        this.pivotZ = pivotZ;
        this.rotationX = rotationX;
        this.rotationY = rotationY;
        this.rotationZ = rotationZ;
        this.boxes = List.copyOf(boxes);
    }

    /** Stable part name, used in generated asset paths ({@code [a-z0-9_]}). */
    public String name() {
        return name;
    }

    public Role role() {
        return role;
    }

    /** Index of the part whose rotation carries this one, or {@code -1} for a root. */
    public int parent() {
        return parent;
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

    /** Base X rotation in vanilla degrees (converted to the rig's y-up space by the renderer). */
    public float rotationX() {
        return rotationX;
    }

    public float rotationY() {
        return rotationY;
    }

    public float rotationZ() {
        return rotationZ;
    }

    public List<MobBox> boxes() {
        return boxes;
    }

    public static Builder builder(String name) {
        return new Builder(name, Role.STATIC);
    }

    public static Builder builder(String name, Role role) {
        return new Builder(name, role);
    }

    public static final class Builder {

        private final String name;
        private final Role role;
        private int parent = -1;
        private float pivotX;
        private float pivotY;
        private float pivotZ;
        private float rotationX;
        private float rotationY;
        private float rotationZ;
        private final List<MobBox> boxes = new ArrayList<>();

        private Builder(String name, Role role) {
            String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty() || !normalized.matches("[a-z0-9_]+")) {
                throw new IllegalArgumentException("Invalid mob part name: '" + name + "'");
            }
            this.name = normalized;
            this.role = role == null ? Role.STATIC : role;
        }

        /** Index of the part whose rotation carries this one ({@code -1} = root). */
        public Builder parent(int index) {
            this.parent = index;
            return this;
        }

        /** Absolute pivot in vanilla model pixels (y grows downward). */
        public Builder pivot(float x, float y, float z) {
            this.pivotX = x;
            this.pivotY = y;
            this.pivotZ = z;
            return this;
        }

        /** Base rotation in vanilla degrees (x, y, z order). */
        public Builder rotation(float x, float y, float z) {
            this.rotationX = x;
            this.rotationY = y;
            this.rotationZ = z;
            return this;
        }

        public Builder box(MobBox box) {
            boxes.add(Objects.requireNonNull(box, "box"));
            return this;
        }

        public MobPart build() {
            return new MobPart(name, role, parent, pivotX, pivotY, pivotZ,
                    rotationX, rotationY, rotationZ, boxes);
        }
    }
}
