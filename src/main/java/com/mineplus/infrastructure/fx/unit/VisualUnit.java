package com.mineplus.infrastructure.fx.unit;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;

/**
 * One composed visual effect: an ordered list of {@link VisualComponent}s with
 * a shared lifetime, a priority and an anchor.
 *
 * <p>A gunshot impact is one unit, not five independent listeners each
 * scheduling its own work: the flash, the dust, the decal and the sound are
 * admitted together under one budget, advance together and are released
 * together.</p>
 *
 * <p>The anchor is a location, optionally following an entity; the bone name is
 * metadata for content that already resolved a bone's world position (the Core
 * has no generic bone-to-world projection).</p>
 */
public final class VisualUnit {

    private final List<VisualComponent> components;
    private final int lifetimeTicks;
    private final int priority;
    private final Location location;
    private final UUID anchorEntity;
    private final String bone;

    private VisualUnit(List<VisualComponent> components, int lifetimeTicks, int priority,
                       Location location, UUID anchorEntity, String bone) {
        this.components = Collections.unmodifiableList(components);
        this.lifetimeTicks = lifetimeTicks;
        this.priority = priority;
        this.location = location;
        this.anchorEntity = anchorEntity;
        this.bone = bone;
    }

    public static Builder builder(Location location) {
        return new Builder(location);
    }

    public List<VisualComponent> components() {
        return components;
    }

    public int lifetimeTicks() {
        return lifetimeTicks;
    }

    public int priority() {
        return priority;
    }

    /** The anchor location (a copy), or {@code null} when the unit follows an entity only. */
    public Location location() {
        return location == null ? null : location.clone();
    }

    /** Entity the anchor follows, or {@code null}. */
    public UUID anchorEntity() {
        return anchorEntity;
    }

    /** Bone name for content that resolved its own world position, or {@code null}. */
    public String bone() {
        return bone;
    }

    public static final class Builder {

        private final java.util.ArrayList<VisualComponent> components = new java.util.ArrayList<>(4);
        private int lifetimeTicks = 40;
        private int priority;
        private Location location;
        private UUID anchorEntity;
        private String bone;

        private Builder(Location location) {
            this.location = location == null ? null : location.clone();
        }

        public Builder component(VisualComponent component) {
            if (component != null) {
                components.add(component);
            }
            return this;
        }

        /** How long the unit lives, in ticks. Clamped to at least one tick. */
        public Builder lifetime(int ticks) {
            this.lifetimeTicks = Math.max(1, ticks);
            return this;
        }

        /** Higher-priority units survive budget pressure; default 0. */
        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        /** Follows an entity's location instead of a fixed point. */
        public Builder anchorEntity(UUID entityId) {
            this.anchorEntity = entityId;
            return this;
        }

        /** Bone name carried for components that position themselves. */
        public Builder bone(String bone) {
            this.bone = bone;
            return this;
        }

        public VisualUnit build() {
            return new VisualUnit(components, lifetimeTicks, priority, location, anchorEntity, bone);
        }
    }
}
