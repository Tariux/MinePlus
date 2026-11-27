package com.mineplus.infrastructure.core.block;

import com.mineplus.infrastructure.render.RenderMode;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One loaded {@code blocks/<namespace>/<id>.json} definition. Immutable; the
 * loader validates every field before constructing it.
 */
public final class BlockDefinition {

    public enum Collision {
        GEOMETRY,
        SURFACE,
        SINGLE,
        BOX,
        NONE;

        public static Collision parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return GEOMETRY;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }
    }

    public record Tool(String type, String tier) {
        public static final Tool NONE = new Tool("any", "any");

        public Tool {
            type = type == null || type.isBlank() ? "any" : type.toLowerCase(Locale.ROOT);
            tier = tier == null || tier.isBlank() ? "any" : tier.toLowerCase(Locale.ROOT);
        }
    }

    public record Drop(String item, int min, int max, double chance) {
        public Drop {
            min = Math.max(1, min);
            max = Math.max(min, max);
            chance = chance <= 0.0 ? 1.0 : Math.min(1.0, chance);
        }
    }

    public record Breaking(double hardness, Tool tool, List<Drop> drops, int xp) {
        public Breaking {
            drops = Collections.unmodifiableList(drops);
        }
    }

    public record Placement(String support, List<String> replaceable, boolean spaceCheck) {
        public Placement {
            replaceable = Collections.unmodifiableList(replaceable);
        }
    }

    public record Events(List<BlockAction> onPlace, List<BlockAction> onBreak, List<BlockAction> onInteract) {
        public static final Events NONE = new Events(List.of(), List.of(), List.of());

        public Events {
            onPlace = Collections.unmodifiableList(onPlace);
            onBreak = Collections.unmodifiableList(onBreak);
            onInteract = Collections.unmodifiableList(onInteract);
        }
    }

    private final String id;
    private final String namespace;
    private final String path;
    private final String displayName;
    private final File source;
    private final String model;
    private final RenderMode renderMode;
    private final List<String> autoplay;
    private final int rotationSnap;
    private final int light;
    private final Collision collision;
    private final String collisionMaterial;
    private final double[] collisionBox;
    private final int width;
    private final int height;
    private final int depth;
    private final Breaking breaking;
    private final Placement placement;
    private final String itemMaterial;
    private final String itemModel;
    private final Events events;
    private final boolean waterloggable;
    private final boolean flammable;

    public BlockDefinition(
            String id,
            String displayName,
            File source,
            String model,
            RenderMode renderMode,
            List<String> autoplay,
            int rotationSnap,
            int light,
            Collision collision,
            String collisionMaterial,
            double[] collisionBox,
            int width,
            int height,
            int depth,
            Breaking breaking,
            Placement placement,
            String itemMaterial,
            String itemModel,
            Events events,
            boolean waterloggable,
            boolean flammable
    ) {
        int colon = id.indexOf(':');
        this.id = id;
        this.namespace = colon > 0 ? id.substring(0, colon) : "mineplus";
        this.path = colon > 0 ? id.substring(colon + 1) : id;
        this.displayName = displayName;
        this.source = source;
        this.model = model;
        this.renderMode = renderMode;
        this.autoplay = Collections.unmodifiableList(autoplay);
        this.rotationSnap = rotationSnap;
        this.light = light;
        this.collision = collision;
        this.collisionMaterial = collisionMaterial;
        this.collisionBox = collisionBox == null ? null : collisionBox.clone();
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.breaking = breaking;
        this.placement = placement;
        this.itemMaterial = itemMaterial;
        this.itemModel = itemModel;
        this.events = events == null ? Events.NONE : events;
        this.waterloggable = waterloggable;
        this.flammable = flammable;
    }

    public String id() {
        return id;
    }

    public String namespace() {
        return namespace;
    }

    public String path() {
        return path;
    }

    public String displayName() {
        return displayName;
    }

    public File source() {
        return source;
    }

    public String model() {
        return model;
    }

    public RenderMode renderMode() {
        return renderMode;
    }

    public List<String> autoplay() {
        return autoplay;
    }

    public int rotationSnap() {
        return rotationSnap;
    }

    public int light() {
        return light;
    }

    public Collision collision() {
        return collision;
    }

    public String collisionMaterial() {
        return collisionMaterial;
    }

    public double[] collisionBox() {
        return collisionBox == null ? null : collisionBox.clone();
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int depth() {
        return depth;
    }

    public Breaking breaking() {
        return breaking;
    }

    public Placement placement() {
        return placement;
    }

    public String itemMaterial() {
        return itemMaterial;
    }

    public String itemModel() {
        return itemModel;
    }

    public Events events() {
        return events;
    }

    public boolean waterloggable() {
        return waterloggable;
    }

    public boolean flammable() {
        return flammable;
    }
}
