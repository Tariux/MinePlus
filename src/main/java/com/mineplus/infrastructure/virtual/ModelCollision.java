package com.mineplus.infrastructure.virtual;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;

/**
 * The optional {@code collision} block of a {@code .meta.json} file.
 *
 * <p>It is the file-level counterpart of {@link
 * com.mineplus.infrastructure.collision.CollisionSpec}: what an author declares
 * here is turned into a spec by the {@code /mineplus collision fit} commands. Fields
 * left out fall back to {@code COLLISION} in {@code settings.mp.yml}.</p>
 *
 * <pre>{@code
 * { "collision": { "mode": "HYBRID", "profile": "FURNITURE", "yaw": 90,
 *                  "blueprint": "{\"schema\":1,\"size\":[1,2,1],"
 *                      + "\"regions\":[{\"type\":\"box\",\"from\":[0,1,0],"
 *                      + "\"to\":[0,1,0],\"directive\":\"TOP_SURFACE\"}]}" } }
 * }</pre>
 *
 * <p>The blueprint may be given as an object too; it is stored as text and
 * parsed by {@code Blueprint} on demand. Schema: {@code docs/collision/schema.md}.</p>
 */
public record ModelCollision(
        Mode mode,
        String profile,
        String blueprintJson,
        Integer resolution,
        Double yaw,
        Double insetBlocks,
        Boolean enabled
) {

    /** Occupancy source declared in the meta file. */
    public enum Mode {
        AUTO, BLUEPRINT, FILL, HYBRID, NONE;

        static Mode fromKey(String key, Mode fallback) {
            if (key == null || key.isBlank()) {
                return fallback;
            }
            for (Mode value : values()) {
                if (value.name().equalsIgnoreCase(key.trim())) {
                    return value;
                }
            }
            return fallback;
        }
    }

    public static final ModelCollision EMPTY =
            new ModelCollision(null, null, null, null, null, null, null);

    public ModelCollision {
        profile = profile == null || profile.isBlank() ? null : profile.trim().toUpperCase(Locale.ROOT);
        blueprintJson = blueprintJson == null || blueprintJson.isBlank() ? null : blueprintJson;
        resolution = resolution == null ? null : Math.max(1, Math.min(64, resolution));
        insetBlocks = insetBlocks == null ? null : Math.max(0, insetBlocks);
    }

    public boolean isEmpty() {
        return mode == null && profile == null && blueprintJson == null
                && resolution == null && yaw == null && insetBlocks == null && enabled == null;
    }

    /** True only for an explicit {@code "enabled": false} (legacy NONE means disabled). */
    public boolean disabled() {
        return Boolean.FALSE.equals(enabled) || mode == Mode.NONE;
    }

    /**
     * Reads the block. Unknown keys are skipped; a malformed blueprint keeps its
     * text and is rejected later by the blueprint parser with a line/column error.
     */
    public static ModelCollision fromJson(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return EMPTY;
        }
        JsonObject object = element.getAsJsonObject();
        Mode mode = Mode.fromKey(string(object, "mode"), Mode.fromKey(string(object, "source"), null));
        String profile = string(object, "profile");
        Integer resolution = integer(object, "resolution");
        Double yaw = decimal(object, "yaw");
        Double inset = decimal(object, "inset");
        Boolean enabled = bool(object, "enabled");
        String blueprint = null;
        JsonElement blueprintElement = object.get("blueprint");
        if (blueprintElement != null && !blueprintElement.isJsonNull()) {
            blueprint = blueprintElement.isJsonPrimitive()
                    ? blueprintElement.getAsString()
                    : blueprintElement.toString();
        }
        if (profile == null) {
            profile = string(object, "fitProfile");
        }
        return new ModelCollision(mode, profile, blueprint, resolution, yaw, inset, enabled);
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive()
                ? null : value.getAsString();
    }

    private static Integer integer(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsInt();
        } catch (RuntimeException broken) {
            return null;
        }
    }

    private static Double decimal(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsDouble();
        } catch (RuntimeException broken) {
            return null;
        }
    }

    private static Boolean bool(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
            return null;
        }
        try {
            return value.getAsBoolean();
        } catch (RuntimeException broken) {
            return null;
        }
    }
}
